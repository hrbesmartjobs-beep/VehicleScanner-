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

    override fun close() = recognizer.close()
}
