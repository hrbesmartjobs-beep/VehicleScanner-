package com.vehiclescanner.core

enum class PlateStatus { MATCH, NOT_FOUND, REVIEW }

data class MatchDecision(
    val status: PlateStatus,
    /** Normalised number used for the decision. */
    val normalized: String,
    /** Final confidence 0..1 after correction penalties. */
    val confidence: Float,
    /** Database number that was matched exactly (MATCH) or a near-miss suggestion (REVIEW). */
    val matchedNumber: String?,
    /** Human readable reason, shown in the UI for REVIEW. */
    val reason: String?
)

/**
 * In-memory matcher over the user's vehicle list.
 *
 * Exact lookup uses a canonical key so "KL7A1234", "KL07A1234" and "KL07A01234"-style
 * differences in how people type numbers still match. When there is no exact match, an
 * OCR-aware fuzzy search looks for a saved number that differs only by typical misreads
 * (8↔B, 0↔O, 5↔S …) or one missing/extra character, so a vehicle that IS in the list is
 * flagged for REVIEW instead of being reported as NOT FOUND.
 */
class VehicleMatcher(numbers: Collection<String>) {

    /** canonical key → number as stored in the database */
    private val exact: HashMap<String, String> = HashMap(numbers.size * 2)
    private val byLength: Map<Int, List<String>>

    init {
        numbers.forEach { exact.putIfAbsent(canonical(it), it) }
        byLength = numbers.distinct().groupBy { it.length }
    }

    val size: Int get() = exact.size

    fun contains(normalized: String) = canonical(normalized) in exact

    /** The database's own spelling of [normalized], or null when not saved. */
    fun lookup(normalized: String): String? = exact[canonical(normalized)]

    /**
     * Decide the status of one OCR reading.
     *
     * @param ocrConfidence engine confidence 0..1
     * @param minConfidence threshold below which a reading is never auto-classified
     */
    fun decide(norm: PlateNormalizer.Result, ocrConfidence: Float, minConfidence: Float): MatchDecision {
        val penalised = (ocrConfidence - 0.08f * norm.corrections).coerceIn(0f, 1f)
        val n = norm.normalized
        val hit = lookup(n)

        if (n.length < 4) {
            return MatchDecision(PlateStatus.REVIEW, n, penalised, null, "Plate text unreadable")
        }
        if (!norm.valid) {
            // Non-standard text: it can still be in the user's list (old / special plates).
            if (hit != null && penalised >= minConfidence) {
                return MatchDecision(PlateStatus.MATCH, n, penalised, hit, null)
            }
            val near = hit ?: nearest(n)
            return MatchDecision(
                PlateStatus.REVIEW, n, penalised, near,
                if (near != null) "Not a standard format; possible match $near" else "Not a standard Indian format"
            )
        }
        if (norm.uncertain) {
            val near = hit ?: nearest(n)
            return MatchDecision(
                PlateStatus.REVIEW, n, penalised, near,
                "State code unclear (part of the plate may be cut off) – please verify" + (near?.let { "; possible match $it" } ?: "")
            )
        }
        if (penalised < minConfidence) {
            val near = hit ?: nearest(n)
            return MatchDecision(
                PlateStatus.REVIEW, n, penalised, near,
                "Low confidence (${(penalised * 100).toInt()}%)" + (near?.let { "; possible match $it" } ?: "")
            )
        }
        if (hit != null) return MatchDecision(PlateStatus.MATCH, n, penalised, hit, null)

        val near = nearest(n)
        if (near != null) {
            return MatchDecision(PlateStatus.REVIEW, n, penalised, near, "Possible OCR misread – similar to $near")
        }
        return MatchDecision(PlateStatus.NOT_FOUND, n, penalised, null, null)
    }

    /**
     * Closest saved number within an OCR-aware edit budget, or null.
     * Costs: typical OCR confusion = 1, missing/extra character = 2, any other substitution = 3.
     * Budget 3 → one wrong character, up to three typical confusions, or one confusion plus one
     * dropped character ("KL0SBU836" ≈ "KL08BU6836") – but never two unrelated wrong characters.
     */
    fun nearest(n: String): String? {
        if (n.length < 6) return null
        var best: String? = null
        var bestCost = MAX_COST + 1
        for (len in (n.length - 1)..(n.length + 1)) {
            val list = byLength[len] ?: continue
            for (cand in list) {
                val c = ocrDistance(n, cand, MAX_COST)
                if (c < bestCost) { bestCost = c; best = cand }
            }
        }
        return if (bestCost <= MAX_COST) best else null
    }

    companion object {
        private const val MAX_COST = 3

        private val CONFUSABLE = listOf(
            "0O", "0D", "0Q", "1I", "1L", "2Z", "5S", "8B", "6G", "4A", "7T", "MN", "MH", "HN", "UV", "KX", "EF", "PR",
            "8S", "3B", "6B", "9G", "OD", "OQ", "UO"
        ).flatMap { listOf(it, it.reversed()) }.toHashSet()

        private fun subCost(a: Char, b: Char) = when {
            a == b -> 0
            "$a$b" in CONFUSABLE -> 1
            else -> 3
        }

        /** Weighted Levenshtein with early exit once every path exceeds [limit]. */
        fun ocrDistance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) * 2 > limit) return limit + 1
            var prev = IntArray(b.length + 1) { it * 2 }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i * 2
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val v = minOf(prev[j - 1] + subCost(a[i - 1], b[j - 1]), prev[j] + 2, cur[j - 1] + 2)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > limit) return limit + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        private val STD = Regex("^([A-Z]{2})([0-9]{1,2})([A-Z]{0,3})([0-9]{1,4})$")

        /** Matching key: district padded to 2 digits and serial to 4 (KL7A123 ≡ KL07A0123). */
        fun canonical(n: String): String {
            val m = STD.matchEntire(n) ?: return n
            val (s, d, series, num) = m.destructured
            return s + d.padStart(2, '0') + series + num.padStart(4, '0')
        }

        /** Returns 0 or 1 if distance ≤ 1, else -1. */
        fun editDistanceAtMostOne(a: String, b: String): Int {
            if (a == b) return 0
            val la = a.length; val lb = b.length
            if (kotlin.math.abs(la - lb) > 1) return -1
            if (la == lb) {
                var diff = 0
                for (i in 0 until la) if (a[i] != b[i] && ++diff > 1) return -1
                return 1
            }
            val (s, l) = if (la < lb) a to b else b to a
            var i = 0; var j = 0; var skipped = false
            while (i < s.length && j < l.length) {
                if (s[i] == l[j]) { i++; j++ } else {
                    if (skipped) return -1
                    skipped = true; j++
                }
            }
            return 1
        }
    }
}
