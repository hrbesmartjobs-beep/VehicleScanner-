package com.vehiclescanner.core

data class ColumnGuess(val column: Int, val hasHeader: Boolean, val validRatio: Float)

data class InvalidEntry(val row: Int, val value: String)

data class ImportPreview(
    val totalRows: Int,
    /** Normalised numbers that will be inserted. */
    val toImport: List<String>,
    /** Valid-format numbers among [toImport] (the rest are non-standard, only when allowed). */
    val validCount: Int,
    val duplicatesInFile: Int,
    val alreadyInDatabase: Int,
    val invalid: List<InvalidEntry>,
    val emptyRows: Int
)

object ImportAnalyzer {
    private val HEADER_WORDS = listOf("VEHICLE", "REG", "REGISTRATION", "PLATE", "NUMBER", "VEH", "RC", "NO")

    /** Pick the column that most looks like registration numbers. */
    fun guessColumn(table: Table): ColumnGuess? {
        if (table.rows.isEmpty() || table.columnCount == 0) return null
        val sample = table.rows.take(500)
        var best: ColumnGuess? = null
        for (c in 0 until table.columnCount) {
            val values = sample.drop(1).map { it.getOrNull(c).orEmpty() }.filter { it.isNotBlank() }
            if (values.isEmpty() && sample.size > 1) continue
            val valid = values.count { PlateNormalizer.isValid(PlateNormalizer.clean(it)) }
            val ratio = if (values.isEmpty()) 0f else valid.toFloat() / values.size
            val headerCell = PlateNormalizer.clean(sample[0].getOrNull(c).orEmpty())
            val headerText = sample[0].getOrNull(c).orEmpty().uppercase()
            val headerLike = !PlateNormalizer.isValid(headerCell) &&
                HEADER_WORDS.any { headerText.contains(it) }
            val score = ratio + if (headerLike) 0.15f else 0f
            val bestScore = best?.let { it.validRatio + if (it.hasHeader) 0.15f else 0f } ?: -1f
            if (score > bestScore) {
                val hasHeader = headerLike || (!PlateNormalizer.isValid(headerCell) && ratio > 0.5f)
                best = ColumnGuess(c, hasHeader, ratio)
            }
        }
        return best?.takeIf { it.validRatio > 0f || it.hasHeader }
    }

    /** Header detection for a manually chosen column. */
    fun looksLikeHeader(table: Table, column: Int): Boolean {
        val first = table.cell(0, column)
        if (first.isBlank()) return false
        return !PlateNormalizer.isValid(PlateNormalizer.clean(first)) &&
            HEADER_WORDS.any { first.uppercase().contains(it) }
    }

    fun analyze(
        table: Table,
        column: Int,
        hasHeader: Boolean,
        existing: Set<String>,
        allowNonStandard: Boolean
    ): ImportPreview {
        val seen = HashSet<String>()
        val toImport = ArrayList<String>()
        val invalid = ArrayList<InvalidEntry>()
        var dupFile = 0; var inDb = 0; var empty = 0; var validCount = 0
        val start = if (hasHeader) 1 else 0
        for (r in start until table.rows.size) {
            val raw = table.cell(r, column)
            if (raw.isBlank()) { empty++; continue }
            val cleaned = PlateNormalizer.clean(raw)
            val valid = PlateNormalizer.isValid(cleaned)
            // Database entries are typed by people, so we never "correct" them — only clean.
            val accept = valid || (allowNonStandard && cleaned.length in 4..12)
            if (!accept) { invalid += InvalidEntry(r + 1, raw); continue }
            if (!seen.add(cleaned)) { dupFile++; continue }
            if (cleaned in existing) { inDb++; continue }
            toImport += cleaned
            if (valid) validCount++
        }
        return ImportPreview(table.rows.size - start, toImport, validCount, dupFile, inDb, invalid, empty)
    }
}
