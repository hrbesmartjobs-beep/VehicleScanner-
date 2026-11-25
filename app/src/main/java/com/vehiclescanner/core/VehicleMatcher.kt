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
 * In-memory matcher over the user's vehicle list. Exact lookup is O(1);
 * near-miss search (edit distance 1, OCR-confusion aware) is linear and only
 * runs when there is no exact match — fast enough for tens of thousands of rows.
 */
class VehicleMatcher(numbers: Collection<String>) {

    private val exact: HashSet<String> = HashSet(numbers.size * 2)
    private val byLength: Map<Int, List<String>>

    init {
        numbers.forEach { exact += it }
        byLength = exact.groupBy { it.length }
    }

    val size: Int get() = exact.size

    fun contains(normalized: String) = normalized in exact

    /**
     * Decide the status of one OCR reading.
     *
     * @param ocrConfidence engine confidence 0..1
     * @param minConfidence threshold below which a reading is never auto-classified
     */
    fun decide(norm: PlateNormalizer.Result, ocrConfidence: Float, minConfidence: Float): MatchDecision {
        val penalised = (ocrConfidence - 0.08f * norm.corrections).coerceIn(0f, 1f)
        val n = norm.normalized

        if (n.length < 4) {
            return MatchDecision(PlateStatus.REVIEW, n, penalised, null, "Plate text unreadable")
        }
        if (!norm.valid) {
            // Non-standard text: it can still be in the user's list (old / special plates).
            if (n in exact && penalised >= minConfidence) {
                return MatchDecision(PlateStatus.MATCH, n, penalised, n, null)
            }
            val near = nearest(n)
            return MatchDecision(
                PlateStatus.REVIEW, n, penalised, near,
                if (near != null) "Not a standard format; similar to $near" else "Not a standard Indian format"
            )
        }
        if (penalised < minConfidence) {
            val near = if (n in exact) n else nearest(n)
            return MatchDecision(
                PlateStatus.REVIEW, n, penalised, near,
                "Low confidence (${(penalised * 100).toInt()}%)" + (near?.let { "; possible match $it" } ?: "")
            )
        }
        if (n in exact) return MatchDecision(PlateStatus.MATCH, n, penalised, n, null)

        val near = nearest(n)
        if (near != null) {
            return MatchDecision(PlateStatus.REVIEW, n, penalised, near, "One character differs from $near")
        }
        return MatchDecision(PlateStatus.NOT_FOUND, n, penalised, null, null)
    }

    /** A database number at distance ≤ 1 (substitution/insertion/deletion), or null. */
    fun nearest(n: String): String? {
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (len in (n.length - 1)..(n.length + 1)) {
            val list = byLength[len] ?: continue
            for (cand in list) {
                val d = editDistanceAtMostOne(n, cand)
                if (d < 0) continue
                // Prefer substitutions that are typical OCR confusions.
                val score = d * 2 - if (d == 1 && len == n.length && isConfusionPair(n, cand)) 1 else 0
                if (score < bestScore) { bestScore = score; best = cand }
            }
        }
        return best
    }

    companion object {
        private val CONFUSABLE = listOf(
            "0O", "0D", "0Q", "1I", "1L", "2Z", "5S", "8B", "6G", "4A", "7T", "MN", "MH", "HN", "UV", "KX", "EF", "PR"
        ).flatMap { listOf(it, it.reversed()) }.toHashSet()

        private fun isConfusionPair(a: String, b: String): Boolean {
            for (i in a.indices) if (a[i] != b[i]) return "${a[i]}${b[i]}" in CONFUSABLE
            return false
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
