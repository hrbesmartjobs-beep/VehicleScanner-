package com.vehiclescanner.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.vehiclescanner.core.CsvIO
import com.vehiclescanner.core.PlateNormalizer
import com.vehiclescanner.core.PlateStatus
import com.vehiclescanner.core.Table
import com.vehiclescanner.core.XlsxReader
import com.vehiclescanner.core.XlsxWriter
import com.vehiclescanner.core.XlsxWriter.Cell
import com.vehiclescanner.core.XlsxWriter.Fill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class FileFormat(val mime: String, val ext: String) {
    CSV("text/csv", "csv"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
}

/** Some document providers reject the "wt" (truncate) mode; fall back to "w". */
fun openOut(context: Context, uri: Uri): java.io.OutputStream? =
    runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
        ?: context.contentResolver.openOutputStream(uri, "w")

object Spreadsheets {
    /** Read a CSV or XLSX chosen by the user. Detects the type from content, not just the name. */
    suspend fun read(context: Context, uri: Uri): List<Table> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val header = cr.openInputStream(uri)?.use { s -> ByteArray(4).also { s.read(it) } }
            ?: throw IllegalArgumentException("Cannot open file")
        val isZip = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        val isOle = header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte()
        when {
            isOle -> throw IllegalArgumentException("Old Excel .xls format is not supported. In Excel choose File → Save As → .xlsx (or CSV) and import again.")
            isZip -> cr.openInputStream(uri)!!.use { XlsxReader.read(it) }
            else -> listOf(cr.openInputStream(uri)!!.use { CsvIO.read(it) })
        }
    }
}

class ExportService(private val context: Context, private val db: AppDatabase) {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun exportSession(sessionId: Long, uri: Uri, format: FileFormat, filter: PlateStatus? = null) = withContext(Dispatchers.IO) {
        val s = db.scans().session(sessionId) ?: error("Scan not found")
        val rs = db.scans().resultsOnce(sessionId).filter { filter == null || it.status == filter }
        val header = listOf("#", "Status", "Registration number", "Formatted", "OCR raw text", "Confidence %",
            "Database match / suggestion", "Verified", "Remarks", "Scan time")
        val rows = rs.mapIndexed { i, r ->
            listOf(
                "${i + 1}", statusLabel(r.status), r.number, PlateNormalizer.format(r.number), r.rawText,
                "${(r.confidence * 100).toInt()}", r.matchedNumber.orEmpty(), if (r.verified) "Yes" else "No",
                r.reason.orEmpty(), time.format(Date(s.timestamp))
            ) to r.status
        }
        write(uri, format, "Scan ${sessionId}", header, rows)
    }

    suspend fun exportVehicles(uri: Uri, format: FileFormat) = withContext(Dispatchers.IO) {
        val all = db.vehicles().all()
        val rows = all.mapIndexed { i, v ->
            listOf("${i + 1}", v.number, PlateNormalizer.format(v.number), v.note, time.format(Date(v.createdAt))) to null
        }
        write(uri, format, "Vehicles", listOf("#", "Vehicle number", "Formatted", "Note", "Added"), rows)
    }

    private fun write(uri: Uri, format: FileFormat, sheet: String, header: List<String>, rows: List<Pair<List<String>, PlateStatus?>>) {
        val out = openOut(context, uri) ?: error("Cannot write to the chosen file")
        out.use { os ->
            when (format) {
                FileFormat.CSV -> {
                    val w = os.bufferedWriter(Charsets.UTF_8)
                    w.write("﻿")   // so Excel opens UTF-8 correctly
                    CsvIO.write(w, listOf(header) + rows.map { it.first })
                }
                FileFormat.XLSX -> {
                    val cells = ArrayList<List<Cell>>()
                    cells += header.map { Cell(it, Fill.HEADER) }
                    for ((r, st) in rows) {
                        val fill = when (st) { PlateStatus.MATCH -> Fill.GREEN; PlateStatus.NOT_FOUND -> Fill.RED; PlateStatus.REVIEW -> Fill.YELLOW; null -> Fill.NONE }
                        cells += r.mapIndexed { i, v -> Cell(v, if (i == 1) fill else Fill.NONE, numeric = i == 0) }
                    }
                    XlsxWriter.write(os, sheet, cells)
                }
            }
        }
    }

    companion object {
        fun statusLabel(s: PlateStatus) = when (s) {
            PlateStatus.MATCH -> "MATCH FOUND"; PlateStatus.NOT_FOUND -> "NOT FOUND"; PlateStatus.REVIEW -> "REVIEW REQUIRED"
        }
    }
}

/**
 * Backup = one ZIP the user saves wherever they like (SD card, USB, another app):
 *   manifest.json, vehicles.csv, history.json and the scan photos/crops.
 */
class BackupService(private val context: Context, private val db: AppDatabase, private val vehicles: VehicleRepository) {

    data class RestoreSummary(val vehicles: Int, val sessions: Int)

    suspend fun backup(uri: Uri, includePhotos: Boolean) = withContext(Dispatchers.IO) {
        val out = openOut(context, uri) ?: error("Cannot write backup")
        val scansRoot = File(context.filesDir, "scans")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("app", "VehicleScanner").put("format", 1)
                .put("created", System.currentTimeMillis()).put("photos", includePhotos).toString().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("vehicles.csv"))
            val w = zip.bufferedWriter(Charsets.UTF_8)
            CsvIO.write(w, listOf(listOf("number", "note", "created_at")) +
                db.vehicles().all().map { listOf(it.number, it.note, it.createdAt.toString()) })
            zip.closeEntry()

            val sessions = JSONArray()
            for (s in db.scans().allSessions()) {
                val rel = { p: String -> File(p).relativeToOrNull(scansRoot)?.invariantSeparatorsPath ?: "" }
                val results = JSONArray()
                for (r in db.scans().resultsOnce(s.id)) results.put(JSONObject()
                    .put("raw", r.rawText).put("number", r.number).put("status", r.status.name)
                    .put("confidence", r.confidence.toDouble()).put("detectorScore", r.detectorScore.toDouble())
                    .put("crop", rel(r.cropPath)).put("l", r.boxLeft.toDouble()).put("t", r.boxTop.toDouble())
                    .put("r", r.boxRight.toDouble()).put("b", r.boxBottom.toDouble())
                    .put("matched", r.matchedNumber ?: JSONObject.NULL).put("reason", r.reason ?: JSONObject.NULL)
                    .put("verified", r.verified).put("corrections", r.corrections))
                sessions.put(JSONObject().put("timestamp", s.timestamp).put("image", rel(s.imagePath))
                    .put("w", s.imageWidth).put("h", s.imageHeight).put("detector", s.detector)
                    .put("duration", s.durationMs).put("results", results))
            }
            zip.putNextEntry(ZipEntry("history.json"))
            zip.write(sessions.toString().toByteArray())
            zip.closeEntry()

            if (includePhotos && scansRoot.exists()) {
                scansRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("scans/" + f.relativeTo(scansRoot).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun restore(uri: Uri, replaceExisting: Boolean): RestoreSummary = withContext(Dispatchers.IO) {
        val scansRoot = File(context.filesDir, "scans")
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var manifest: JSONObject? = null
        var vehiclesCsv: String? = null
        var history: String? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    when {
                        name == "manifest.json" -> manifest = JSONObject(zin.readBytes().toString(Charsets.UTF_8))
                        name == "vehicles.csv" -> vehiclesCsv = zin.readBytes().toString(Charsets.UTF_8)
                        name == "history.json" -> history = zin.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("scans/") && !e.isDirectory && !name.contains("..") -> {
                            val f = File(staging, name.removePrefix("scans/"))
                            if (f.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                                f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) }
                            }
                        }
                    }
                    e = zin.nextEntry
                }
            }
        } ?: error("Cannot open backup")
        if (manifest?.optString("app") != "VehicleScanner") error("This is not a Vehicle Scanner backup file")

        val numbers = CsvIO.parse(vehiclesCsv ?: "").drop(1).mapNotNull { r ->
            val n = PlateNormalizer.clean(r.getOrNull(0).orEmpty())
            if (n.length < 4) null else VehicleEntity(number = n, note = r.getOrNull(1).orEmpty(),
                createdAt = r.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis())
        }

        var sessionCount = 0
        db.withTransaction {
            if (replaceExisting) {
                db.vehicles().deleteAll(); db.scans().deleteAllResults(); db.scans().deleteAllSessions()
            }
            numbers.chunked(500).forEach { db.vehicles().insertAll(it) }
        }
        if (replaceExisting) scansRoot.deleteRecursively()
        // Merge photos into app storage.
        staging.walkTopDown().filter { it.isFile }.forEach { f ->
            val dest = File(scansRoot, f.relativeTo(staging).path)
            if (!dest.exists()) { dest.parentFile?.mkdirs(); f.copyTo(dest) }
        }
        staging.deleteRecursively()

        history?.let { h ->
            val arr = JSONArray(h)
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val abs = { p: String -> if (p.isEmpty()) "" else File(scansRoot, p).absolutePath }
                val rs = s.getJSONArray("results")
                val list = (0 until rs.length()).map { j ->
                    val r = rs.getJSONObject(j)
                    ScanResultEntity(
                        sessionId = 0, rawText = r.optString("raw"), number = r.optString("number"),
                        status = runCatching { PlateStatus.valueOf(r.optString("status")) }.getOrDefault(PlateStatus.REVIEW),
                        confidence = r.optDouble("confidence", 0.0).toFloat(), detectorScore = r.optDouble("detectorScore", 0.0).toFloat(),
                        cropPath = abs(r.optString("crop")), boxLeft = r.optDouble("l").toFloat(), boxTop = r.optDouble("t").toFloat(),
                        boxRight = r.optDouble("r").toFloat(), boxBottom = r.optDouble("b").toFloat(),
                        matchedNumber = if (r.isNull("matched")) null else r.optString("matched"),
                        reason = if (r.isNull("reason")) null else r.optString("reason"),
                        verified = r.optBoolean("verified"), corrections = r.optInt("corrections")
                    )
                }
                db.scans().insertScan(ScanSessionEntity(
                    timestamp = s.getLong("timestamp"), imagePath = abs(s.optString("image")),
                    imageWidth = s.optInt("w"), imageHeight = s.optInt("h"), detector = s.optString("detector"),
                    durationMs = s.optLong("duration"), total = list.size,
                    matched = list.count { it.status == PlateStatus.MATCH },
                    notFound = list.count { it.status == PlateStatus.NOT_FOUND },
                    review = list.count { it.status == PlateStatus.REVIEW }
                ), list)
                sessionCount++
            }
        }
        vehicles.invalidateCache()
        RestoreSummary(numbers.size, sessionCount)
    }

    /** Erase every vehicle, every scan and every stored photo. */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.vehicles().deleteAll(); db.scans().deleteAllResults(); db.scans().deleteAllSessions()
        }
        File(context.filesDir, "scans").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        vehicles.invalidateCache()
    }
}
