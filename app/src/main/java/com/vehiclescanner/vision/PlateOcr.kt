package com.vehiclescanner.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vehiclescanner.core.Box
import com.vehiclescanner.core.OcrLine
import com.vehiclescanner.core.PlateReading
import com.vehiclescanner.core.PlateTextAssembler
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Offline OCR for single plate crops using ML Kit's bundled Latin text model
 * (`com.google.mlkit:text-recognition` — the model ships inside the APK, no download).
 * OCR always runs on an individual, enhanced plate crop — never on the whole photo.
 */
class PlateOcr : Closeable {

    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readLines(bmp: Bitmap): List<OcrLine> {
        val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            // ML Kit reports per-line confidence; fall back to element average if it is missing (0).
            var conf = line.confidence
            if (conf <= 0f && line.elements.isNotEmpty()) conf = line.elements.map { it.confidence }.average().toFloat()
            if (conf <= 0f) conf = 0.5f
            lines += OcrLine(line.text, Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), conf)
        }
        return lines
    }

    /** Fast single pass, used to verify classical-detector candidates. */
    suspend fun quickRead(crop: Bitmap): PlateReading? {
        val up = PlateEnhancer.upscaledColor(crop)
        return try { PlateTextAssembler.assemble(readLines(up), "color") } finally { up.recycle() }
    }

    /** Full read: OCR every enhanced variant and pick the most plausible plate. */
    suspend fun read(crop: Bitmap): PlateReading? {
        val variants = PlateEnhancer.variants(crop)
        val readings = ArrayList<PlateReading>()
        try {
            for (v in variants) {
                PlateTextAssembler.assemble(readLines(v.bitmap), v.name)?.let { readings += it }
                // Early exit: a confident, valid, uncorrected reading needs no more passes.
                val last = readings.lastOrNull()
                if (last != null && last.norm.valid && last.norm.corrections == 0 && last.confidence >= 0.9f && readings.size >= 2) break
            }
        } finally {
            variants.forEach { it.bitmap.recycle() }
        }
        return PlateTextAssembler.pickBest(readings)
    }

    /**
     * Plate localisation by text detection: finds regions whose text looks like a
     * registration number (one line, or two stacked lines of one block). Runs on a
     * downscaled full view plus overlapping tiles so small, distant plates are found.
     * Returned boxes are only *locations* — every plate is then cropped, enhanced and
     * read again individually by [read].
     */
    suspend fun findPlateRegions(image: Bitmap, onTile: (Int, Int) -> Unit = { _, _ -> }): List<Box> {
        val views = ArrayList<Pair<android.graphics.Rect, Int>>()   // region of the image, max side for OCR
        views += android.graphics.Rect(0, 0, image.width, image.height) to 2048
        if (maxOf(image.width, image.height) > 1800) {
            val tw = (image.width * 0.6f).toInt(); val th = (image.height * 0.6f).toInt()
            for (y in listOf(0, image.height - th)) for (x in listOf(0, image.width - tw))
                views += android.graphics.Rect(x, y, x + tw, y + th) to 2048
        }
        val out = ArrayList<Box>()
        views.forEachIndexed { i, (r, maxSide) ->
            onTile(i + 1, views.size)
            val region = if (r.width() == image.width && r.height() == image.height) image
                else Bitmap.createBitmap(image, r.left, r.top, r.width(), r.height())
            val s = minOf(1f, maxSide.toFloat() / maxOf(region.width, region.height))
            val input = if (s < 1f) Bitmap.createScaledBitmap(region, (region.width * s).toInt(), (region.height * s).toInt(), true) else region
            try {
                val text = recognizer.process(InputImage.fromBitmap(input, 0)).await()
                fun add(rect: android.graphics.Rect?, t: String) {
                    if (rect == null || !looksLikePlate(t)) return
                    out += Box(rect.left / s + r.left, rect.top / s + r.top, rect.right / s + r.left, rect.bottom / s + r.top, 0.5f)
                }
                for (block in text.textBlocks) {
                    for (line in block.lines) add(line.boundingBox, line.text)
                    if (block.lines.size in 2..3) add(block.boundingBox, block.lines.joinToString("") { it.text })
                }
            } finally {
                if (input !== region) input.recycle()
                if (region !== image) region.recycle()
            }
        }
        return com.vehiclescanner.core.Nms.apply(out, 0.3f, 0.6f)
    }

    private fun looksLikePlate(raw: String): Boolean {
        val n = com.vehiclescanner.core.PlateNormalizer.normalize(raw)
        if (n.valid) return true
        val t = n.cleanedRaw
        return t.length in 6..12 && t.count { it.isDigit() } >= 3 && t.count { it.isLetter() } >= 2
    }

    override fun close() = recognizer.close()
}
