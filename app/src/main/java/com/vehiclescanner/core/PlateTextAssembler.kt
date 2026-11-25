package com.vehiclescanner.core

/** One text line returned by the OCR engine for a plate crop. */
data class OcrLine(val text: String, val box: Box, val confidence: Float)

/** A plate reading candidate from one OCR pass over one preprocessed variant of the crop. */
data class PlateReading(
    val rawText: String,
    val norm: PlateNormalizer.Result,
    val confidence: Float,
    val variant: String
) {
    /** Ranking: valid format first, then fewer corrections, then confidence. */
    val rank: Float
        get() = (if (norm.valid) 10f else 0f) - norm.corrections * 0.6f + confidence +
            (if (norm.normalized.length in 8..10) 0.5f else 0f)
}

object PlateTextAssembler {

    /**
     * Turn OCR lines from one crop into a single plate string.
     * Handles single-line car plates and two-line (bike / truck / square) plates,
     * drops the "IND" hologram text and tiny noise lines.
     */
    fun assemble(lines: List<OcrLine>, variant: String): PlateReading? {
        val useful = lines
            .map { it.copy(text = PlateNormalizer.clean(it.text)) }
            .filter { it.text.isNotEmpty() && it.text != "IND" && it.text != "INDIA" }
        if (useful.isEmpty()) return null

        // Drop lines much shorter in height than the tallest (dealer text, stickers).
        val maxH = useful.maxOf { it.box.height }
        val main = useful.filter { it.box.height >= maxH * 0.45f }

        // Group into rows by vertical centre, then order rows top→bottom and items left→right.
        val sorted = main.sortedBy { it.box.cy }
        val rows = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row.first().box.cy - l.box.cy) < maxH * 0.5f) row += l
            else rows += mutableListOf(l)
        }
        val ordered = rows.flatMap { r -> r.sortedBy { it.box.left } }
        val text = ordered.joinToString("") { it.text }
        // Character-weighted confidence.
        val totalChars = ordered.sumOf { it.text.length }.coerceAtLeast(1)
        val conf = ordered.sumOf { (it.confidence * it.text.length).toDouble() }.toFloat() / totalChars

        val norm = PlateNormalizer.normalize(text)
        // If joining everything produced junk, try each row alone (e.g. a big plate + small sticker row).
        if (!norm.valid && rows.size > 1) {
            val alt = rows.map { r ->
                val t = r.sortedBy { it.box.left }.joinToString("") { it.text }
                PlateReading(t, PlateNormalizer.normalize(t), r.map { it.confidence }.average().toFloat(), variant)
            }.filter { it.norm.valid }.maxByOrNull { it.rank }
            if (alt != null) return alt
        }
        return PlateReading(text, norm, conf, variant)
    }

    /**
     * Combine readings from several preprocessing variants. When several
     * variants agree on the same normalised text, confidence is boosted;
     * disagreement between valid readings lowers it.
     */
    fun pickBest(readings: List<PlateReading>): PlateReading? {
        if (readings.isEmpty()) return null
        val best = readings.maxByOrNull { it.rank }!!
        val validTexts = readings.filter { it.norm.valid }.map { it.norm.normalized }.distinct()
        val agree = readings.count { it.norm.normalized == best.norm.normalized }
        var conf = best.confidence
        if (agree >= 2) conf = (conf + 0.05f * (agree - 1)).coerceAtMost(0.99f)
        if (validTexts.size > 1) conf *= 0.85f
        return best.copy(confidence = conf)
    }
}
