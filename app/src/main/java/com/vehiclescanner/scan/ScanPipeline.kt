package com.vehiclescanner.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.vehiclescanner.core.Box
import com.vehiclescanner.core.Nms
import com.vehiclescanner.core.PlateNormalizer
import com.vehiclescanner.core.PlateReading
import com.vehiclescanner.core.PlateStatus
import com.vehiclescanner.core.Tiler
import com.vehiclescanner.data.ScanDao
import com.vehiclescanner.data.ScanResultEntity
import com.vehiclescanner.data.ScanSessionEntity
import com.vehiclescanner.data.SettingsRepository
import com.vehiclescanner.data.VehicleRepository
import com.vehiclescanner.vision.ImageUtils
import com.vehiclescanner.vision.OpenCvPlateDetector
import com.vehiclescanner.vision.PlateOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full offline pipeline for one photo:
 *   load → detect every plate (YOLO tiles and/or OpenCV) → de-duplicate boxes →
 *   crop each plate → enhance → OCR → normalise → match → save session.
 */
class ScanPipeline(
    private val context: Context,
    private val vehicles: VehicleRepository,
    private val scans: ScanDao,
    private val settings: SettingsRepository,
    private val models: ModelManager
) {
    private data class Candidate(val box: Box, val needsVerification: Boolean)

    suspend fun run(uri: Uri, progress: (Float, String) -> Unit): Long = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val cfg = settings.state.value

        progress(0.02f, "Loading photo")
        val image = ImageUtils.load(context, uri)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val dir = File(context.filesDir, "scans/$stamp").apply { mkdirs() }
        val original = File(dir, "original.jpg")
        ImageUtils.save(image, original, 90)

        try {
            // ---------------- detection
            val yolo = models.detector()
            val candidates = ArrayList<Candidate>()
            val detectorNames = ArrayList<String>()

            if (yolo != null) {
                detectorNames += yolo.name
                val tiles = if (cfg.tiledDetection) Tiler.tiles(image.width, image.height) else Tiler.tiles(image.width, image.height).take(1)
                val boxes = ArrayList<Box>()
                tiles.forEachIndexed { i, t ->
                    ensureActive()
                    progress(0.05f + 0.25f * i / tiles.size, "Detecting plates (${i + 1}/${tiles.size})")
                    val tileBmp = if (i == 0) image else Bitmap.createBitmap(image, t.x, t.y, t.w, t.h)
                    try {
                        boxes += yolo.detect(tileBmp, cfg.detectorThreshold).map { it.offset(t.x.toFloat(), t.y.toFloat()) }
                    } finally { if (tileBmp !== image) tileBmp.recycle() }
                }
                Nms.apply(boxes, 0.45f, 0.75f).forEach { candidates += Candidate(it, false) }
            }
            if (yolo == null || cfg.combineDetectors) {
                detectorNames += "OpenCV"
                ensureActive()
                progress(0.2f, "Locating plates (OpenCV)")
                val cv = OpenCvPlateDetector().detect(image, cfg.detectorThreshold)
                for (b in cv) {
                    if (candidates.none { it.box.iou(b) > 0.3f || it.box.overlapOfSmaller(b) > 0.6f }) candidates += Candidate(b, true)
                }
            }

            // ---------------- OCR every plate crop individually
            val matcher = vehicles.matcher()
            val results = ArrayList<ScanResultEntity>()
            PlateOcr().use { ocr ->
                candidates.forEachIndexed { i, c ->
                    ensureActive()
                    progress(0.35f + 0.6f * i / candidates.size.coerceAtLeast(1), "Reading plate ${i + 1} of ${candidates.size}")
                    val padded = c.box.expand(0.06f, 0.15f, image.width, image.height)
                    val crop = ImageUtils.crop(image, padded)
                    try {
                        if (c.needsVerification) {
                            val quick = ocr.quickRead(crop)
                            if (quick == null || !plausible(quick)) return@forEachIndexed
                        }
                        val reading = ocr.read(crop)
                        if (reading == null && c.needsVerification) return@forEachIndexed

                        val cropFile = File(dir, "plate_${i}.jpg")
                        ImageUtils.save(crop, cropFile, 95)
                        results += if (reading == null) {
                            entity(c, padded, cropFile, "", "", PlateStatus.REVIEW, 0f, null, "No text could be read – enter it manually", 0)
                        } else {
                            val d = matcher.decide(reading.norm, reading.confidence, cfg.minConfidence)
                            entity(c, padded, cropFile, reading.norm.cleanedRaw, d.normalized, d.status, d.confidence, d.matchedNumber, d.reason, reading.norm.corrections)
                        }
                    } finally { crop.recycle() }
                }
            }

            // ---------------- remove duplicate readings of the same plate (overlapping detections)
            val deduped = dedupe(results)

            progress(0.97f, "Saving results")
            val session = ScanSessionEntity(
                timestamp = System.currentTimeMillis(),
                imagePath = original.absolutePath,
                imageWidth = image.width, imageHeight = image.height,
                detector = detectorNames.joinToString(" + "),
                durationMs = System.currentTimeMillis() - started,
                total = deduped.size,
                matched = deduped.count { it.status == PlateStatus.MATCH },
                notFound = deduped.count { it.status == PlateStatus.NOT_FOUND },
                review = deduped.count { it.status == PlateStatus.REVIEW }
            )
            // Remove crops of dropped duplicates.
            val keep = deduped.map { it.cropPath }.toSet()
            results.filter { it.cropPath !in keep }.forEach { File(it.cropPath).delete() }
            val id = scans.insertScan(session, deduped)
            progress(1f, "Done")
            id
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        } finally {
            image.recycle()
        }
    }

    private fun plausible(r: PlateReading): Boolean {
        if (r.norm.valid) return true
        val t = r.norm.cleanedRaw
        return t.length in 6..12 && t.count { it.isDigit() } >= 3 && t.count { it.isLetter() } >= 2
    }

    private fun entity(
        c: Candidate, box: Box, crop: File, raw: String, number: String, status: PlateStatus,
        conf: Float, matched: String?, reason: String?, corrections: Int
    ) = ScanResultEntity(
        sessionId = 0, rawText = raw, number = number, status = status, confidence = conf,
        detectorScore = c.box.score, cropPath = crop.absolutePath,
        boxLeft = box.left, boxTop = box.top, boxRight = box.right, boxBottom = box.bottom,
        matchedNumber = matched, reason = reason, corrections = corrections
    )

    private fun dedupe(list: List<ScanResultEntity>): List<ScanResultEntity> {
        val out = ArrayList<ScanResultEntity>()
        for (r in list.sortedByDescending { it.confidence }) {
            val dup = r.number.length >= 6 && PlateNormalizer.isValid(r.number) && out.any { it.number == r.number }
            if (!dup) out += r
        }
        return out.sortedWith(compareBy({ it.boxTop }, { it.boxLeft }))
    }
}
