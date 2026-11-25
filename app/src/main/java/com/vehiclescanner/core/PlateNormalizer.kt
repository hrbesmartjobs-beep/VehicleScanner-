package com.vehiclescanner.core

/**
 * Normalises and validates Indian vehicle registration numbers.
 *
 * Supported formats (after removing spaces / hyphens / dots):
 *  - Standard:  SS DD L{0,3} NNNN   e.g. KL08AB1234, MH12XY9876, DL3CAB1234, KA011234 (old, no series)
 *  - Bharat (BH) series: YY BH NNNN L{1,2}  e.g. 22BH1234AA
 *
 * OCR correction is position-aware: a character is only "corrected" when the
 * plate template says it must be a letter (or a digit) at that position, and
 * every correction is counted so callers can lower confidence / ask for review.
 */
object PlateNormalizer {

    val STATE_CODES: Set<String> = setOf(
        "AN", "AP", "AR", "AS", "BR", "CH", "CG", "DD", "DL", "DN", "GA", "GJ", "HR", "HP",
        "JH", "JK", "KA", "KL", "LA", "LD", "MH", "ML", "MN", "MP", "MZ", "NL", "OD", "OR",
        "PB", "PY", "RJ", "SK", "TN", "TR", "TS", "TG", "UK", "UA", "UP", "WB"
    )

    /** Characters a reader may confuse when a DIGIT is required. */
    private val LETTER_TO_DIGIT = mapOf(
        'O' to '0', 'D' to '0', 'Q' to '0', 'U' to '0',
        'I' to '1', 'L' to '1', 'J' to '1', 'T' to '7',
        'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6',
        'A' to '4', 'Y' to '7'
    )

    /** Characters a reader may confuse when a LETTER is required. */
    private val DIGIT_TO_LETTER = mapOf(
        '0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B',
        '6' to 'G', '4' to 'A', '7' to 'T', '3' to 'B'
    )

    /** Common confusions between two state codes' letters (only used for state-code repair). */
    private val STATE_LETTER_ALTERNATIVES = mapOf(
        'K' to listOf('X', 'H'), 'X' to listOf('K'), 'H' to listOf('K', 'N', 'M'),
        'M' to listOf('H', 'N'), 'N' to listOf('M', 'H'), 'L' to listOf('I'),
        'I' to listOf('L', 'T'), 'T' to listOf('I'), 'P' to listOf('R', 'B'),
        'R' to listOf('P', 'K'), 'B' to listOf('R', 'P'), 'O' to listOf('D', 'Q'),
        'D' to listOf('O'), 'C' to listOf('G'), 'G' to listOf('C'),
        'V' to listOf('U'), 'U' to listOf('V')
    )

    private val STANDARD = Regex("^([A-Z]{2})([0-9]{1,2})([A-Z]{0,3})([0-9]{1,4})$")
    private val BH = Regex("^([0-9]{2})(BH)([0-9]{4})([A-Z]{1,2})$")

    /** Uppercase and strip everything that is not A–Z / 0–9. Also maps common unicode lookalikes. */
    fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.uppercase()) {
            val c = when (ch) {
                'Ø', 'Ö', 'Ó', 'Ò', 'Ô' -> 'O'
                'Í', 'Ì', '|', '!' -> 'I'
                '$' -> 'S'
                else -> ch
            }
            if (c in 'A'..'Z' || c in '0'..'9') sb.append(c)
        }
        return sb.toString()
    }

    /** Pretty format, e.g. KL08AB1234 -> "KL 08 AB 1234", 22BH1234AA -> "22 BH 1234 AA". */
    fun format(normalized: String): String {
        BH.matchEntire(normalized)?.let { m ->
            return m.groupValues.drop(1).joinToString(" ")
        }
        STANDARD.matchEntire(normalized)?.let { m ->
            return m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return normalized
    }

    fun isValid(normalized: String): Boolean {
        BH.matchEntire(normalized)?.let { return true }
        val m = STANDARD.matchEntire(normalized) ?: return false
        return m.groupValues[1] in STATE_CODES
    }

    data class Result(
        /** Best normalised string (may be invalid if nothing fitted). */
        val normalized: String,
        /** True if [normalized] matches a known Indian format with a real state code. */
        val valid: Boolean,
        /** Number of characters changed by position-aware OCR correction. */
        val corrections: Int,
        /** Text exactly as cleaned from OCR (no correction). */
        val cleanedRaw: String
    )

    /**
     * Normalise OCR text into the most plausible registration number.
     * Tries the cleaned text as-is first, then every plate template with
     * minimal corrections, also trying substrings (OCR often adds "IND" or
     * stray border characters).
     */
    fun normalize(raw: String): Result {
        val cleaned = clean(raw).removePrefix("IND")
        if (cleaned.isEmpty()) return Result("", false, 0, cleaned)
        if (isValid(cleaned)) return Result(cleaned, true, 0, cleaned)

        var best: Candidate? = null
        // Consider substrings, because OCR may add junk at either end (max 3 chars trimmed each side).
        for (start in 0..minOf(3, cleaned.length - 1)) {
            for (end in cleaned.length downTo maxOf(start + 6, cleaned.length - 3)) {
                if (end - start < 6 || end - start > 11) continue
                val sub = cleaned.substring(start, end)
                val trimmed = start + (cleaned.length - end)
                for (cand in candidatesFor(sub)) {
                    val scored = cand.copy(cost = cand.cost + trimmed * 2)
                    if (best == null || scored.better(best)) best = scored
                }
            }
        }
        val b = best
        return if (b != null && b.cost <= MAX_COST) {
            Result(b.text, true, b.corrections + b.trimmedPenaltyCount(cleaned), cleaned)
        } else {
            Result(cleaned, false, 0, cleaned)
        }
    }

    private const val MAX_COST = 8

    private data class Candidate(val text: String, val corrections: Int, val cost: Int, val shapeScore: Int) {
        fun better(o: Candidate): Boolean =
            if (cost != o.cost) cost < o.cost else shapeScore > o.shapeScore

        fun trimmedPenaltyCount(cleaned: String): Int = if (cleaned.length != text.length) 1 else 0
    }

    /** Character class per position: 'L' letter, 'D' digit, 'B' fixed "BH". */
    private fun templates(len: Int): List<String> {
        val out = ArrayList<String>()
        // Standard: LL D{1,2} L{0,3} D{1,4}
        for (d1 in 1..2) for (s in 0..3) for (d2 in 1..4) {
            if (2 + d1 + s + d2 == len) out += "LL" + "D".repeat(d1) + "L".repeat(s) + "D".repeat(d2)
        }
        // BH: DD BH DDDD L{1,2}
        for (s in 1..2) if (8 + s == len) out += "DDBBDDDD" + "L".repeat(s)
        return out
    }

    private fun candidatesFor(s: String): List<Candidate> {
        val result = ArrayList<Candidate>()
        for (t in templates(s.length)) {
            val chars = CharArray(s.length)
            var corrections = 0
            var ok = true
            for (i in s.indices) {
                val c = s[i]
                when (t[i]) {
                    'L' -> if (c.isLetter()) chars[i] = c else {
                        val r = DIGIT_TO_LETTER[c]; if (r == null) { ok = false; break }
                        chars[i] = r; corrections++
                    }
                    'D' -> if (c.isDigit()) chars[i] = c else {
                        val r = LETTER_TO_DIGIT[c]; if (r == null) { ok = false; break }
                        chars[i] = r; corrections++
                    }
                    'B' -> {
                        val want = if (i == 2) 'B' else 'H'
                        if (c == want) chars[i] = c else if (want == 'B' && c == '8') {
                            chars[i] = 'B'; corrections++
                        } else { ok = false; break }
                    }
                }
            }
            if (!ok) continue
            var text = String(chars)
            var cost = corrections * 2
            val isBh = t.startsWith("DDBB")
            if (!isBh) {
                val state = text.substring(0, 2)
                if (state !in STATE_CODES) {
                    val repaired = repairState(state) ?: continue
                    text = repaired + text.substring(2)
                    corrections += 1
                    cost += 3
                }
            }
            // Shape preference: 2-digit district, 4-digit number, 1-2 letter series is the common case.
            var shape = 0
            if (!isBh) {
                // Group lengths straight from the template (regex is greedy and could split differently).
                val d1Len = t.drop(2).takeWhile { it == 'D' }.length
                val seriesLen = t.drop(2 + d1Len).takeWhile { it == 'L' }.length
                val numLen = t.length - 2 - d1Len - seriesLen
                if (d1Len == 2) shape += 2
                if (numLen == 4) shape += 3
                if (seriesLen in 1..2) shape += 1
                if (numLen < 4) cost += if (numLen < 3) 5 else 3   // registration numbers are 4 digits
            } else shape += 3
            result += Candidate(text, corrections, cost, shape)
        }
        return result
    }

    private fun repairState(state: String): String? {
        val a = state[0]; val b = state[1]
        for (x in listOf(a) + (STATE_LETTER_ALTERNATIVES[a] ?: emptyList()))
            for (y in listOf(b) + (STATE_LETTER_ALTERNATIVES[b] ?: emptyList())) {
                if (x == a && y == b) continue
                val s = "$x$y"
                if (s in STATE_CODES) {
                    // Only allow a single-letter repair, to avoid inventing a state.
                    if ((x != a) xor (y != b)) return s
                }
            }
        return null
    }
}
