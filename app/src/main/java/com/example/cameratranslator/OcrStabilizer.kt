package com.example.cameratranslator

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Confirms a subtitle only after repeated, temporally stable OCR samples.
 * Once confirmed, small OCR fluctuations are ignored until the subtitle clears
 * or a different reading remains stable long enough to be a new subtitle.
 */
class OcrStabilizer(
    private val onConfirmed: (String) -> Unit
) {
    private val lock = Any()
    private var confirmed: String? = null
    private var candidate: String? = null
    private var candidateHits = 0
    private var candidateStartedAt = 0L

    fun reset() {
        synchronized(lock) {
            confirmed = null
            candidate = null
            candidateHits = 0
            candidateStartedAt = 0L
            Log.d(TAG, "Stabilizer fully reset")
        }
    }

    /**
     * Altyazı kaybolduğunda çağrılır; aynı metnin tekrar tespit edilmesine izin verir.
     */
    fun notifyEmpty() {
        synchronized(lock) {
            confirmed = null
            candidate = null
            candidateHits = 0
            candidateStartedAt = 0L
        }
    }

    fun submit(raw: String) {
        val text = raw.replace(SPACES_REGEX, " ").trim()
        if (text.length < 2) return

        var approved: String? = null
        synchronized(lock) {
            val currentConfirmed = confirmed
            if (currentConfirmed != null && similar(text, currentConfirmed)) {
                // OCR readings that fluctuate slightly while a subtitle remains visible
                // belong to the already confirmed subtitle. Keep it locked.
                candidate = null
                candidateHits = 0
                return
            }

            if (candidate != null && similar(text, candidate!!)) {
                candidateHits++
                candidate = pickBetter(candidate!!, text)
            } else {
                candidate = text
                candidateHits = 1
                candidateStartedAt = System.currentTimeMillis()
            }

            val requiredHits = if (currentConfirmed == null) INITIAL_REQUIRED_HITS else NEXT_REQUIRED_HITS
            val requiredDuration = if (currentConfirmed == null) INITIAL_STABLE_MS else NEXT_STABLE_MS
            if (candidateHits >= requiredHits && System.currentTimeMillis() - candidateStartedAt >= requiredDuration) {
                approved = confirmLocked()
            }
        }

        approved?.let { onConfirmed(it) }
    }

    private fun confirmLocked(): String {
        val text = candidate ?: return ""
        confirmed = text
        candidate = null
        candidateHits = 0
        candidateStartedAt = 0L
        Log.d(TAG, "Confirmed: \"$text\"")
        return text
    }

    private fun pickBetter(a: String, b: String): String {
        val aScore = a.length + if (a.lastOrNull()?.let { it in ".?!" } == true) 4 else 0
        val bScore = b.length + if (b.lastOrNull()?.let { it in ".?!" } == true) 4 else 0
        return if (bScore > aScore) b else a
    }

    private fun similar(a: String, b: String): Boolean {
        val left = compareKey(a)
        val right = compareKey(b)
        if (left == right) return true
        if (left.isEmpty() || right.isEmpty()) return false

        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        if (longer.contains(shorter) && shorter.length.toFloat() / longer.length >= 0.78f) {
            return true
        }

        val distance = levenshtein(left, right)
        val ratio = 1f - distance.toFloat() / max(left.length, right.length)
        return ratio >= SIMILARITY_THRESHOLD
    }

    private fun compareKey(text: String): String {
        return text.lowercase()
            .replace(SPACES_REGEX, " ")
            .trim()
            .trimEnd('.', ',', '!', '?', ';', ':')
    }

    private fun levenshtein(left: String, right: String): Int {
        val n = left.length
        val m = right.length
        if (n == 0) return m
        if (m == 0) return n
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            for (j in 0..m) prev[j] = curr[j]
        }
        return prev[m]
    }

    companion object {
        private const val TAG = "OCR_STABILIZER"
        // Keep first subtitle latency low while still requiring repeated OCR evidence.
        private const val INITIAL_REQUIRED_HITS = 2
        private const val NEXT_REQUIRED_HITS = 2
        private const val INITIAL_STABLE_MS = 0L
        private const val NEXT_STABLE_MS = 0L
        private const val SIMILARITY_THRESHOLD = 0.75f

        private val SPACES_REGEX = Regex("\\s+")
    }
}
