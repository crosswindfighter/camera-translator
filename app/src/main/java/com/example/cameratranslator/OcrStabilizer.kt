package com.example.cameratranslator

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Raw OCR → stabilizer → approved text.
 * Delivers approved OCR text instantly with zero lag for short and fast dialogue.
 */
class OcrStabilizer(
    private val onConfirmed: (String) -> Unit
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var confirmed: String? = null
    private var candidate: String? = null
    private var candidateHits = 0
    private var confirmRunnable: Runnable? = null

    fun reset() {
        synchronized(lock) {
            confirmRunnable?.let { mainHandler.removeCallbacks(it) }
            confirmRunnable = null
            confirmed = null
            candidate = null
            candidateHits = 0
            Log.d(TAG, "Stabilizer fully reset")
        }
    }

    /**
     * Altyazı kaybolduğunda çağrılır; aynı metnin tekrar tespit edilmesine izin verir.
     */
    fun notifyEmpty() {
        synchronized(lock) {
            confirmed = null
        }
    }

    fun submit(raw: String) {
        val text = raw.replace(SPACES_REGEX, " ").trim()
        if (text.length < 2) return

        var approvedImmediately: String? = null
        synchronized(lock) {
            val currentConfirmed = confirmed
            if (currentConfirmed != null && similar(text, currentConfirmed)) {
                return
            }

            candidate = text
            approvedImmediately = confirmLocked()
        }

        approvedImmediately?.let { onConfirmed(it) }
    }

    private fun confirmLocked(): String {
        confirmRunnable?.let { mainHandler.removeCallbacks(it) }
        confirmRunnable = null
        val text = candidate ?: return ""
        confirmed = text
        candidate = null
        candidateHits = 0
        Log.d(TAG, "Confirmed: \"$text\"")
        return text
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
        private const val SIMILARITY_THRESHOLD = 0.75f

        private val SPACES_REGEX = Regex("\\s+")
    }
}
