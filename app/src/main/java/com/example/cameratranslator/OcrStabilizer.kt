package com.example.cameratranslator

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Confirms a subtitle only after repeated OCR samples agree.
 *
 * Asfalt gibi dokulu arka planlarda aynı altyazı her karede biraz farklı okunur
 * ve araya tamamen bozuk okumalar girer. Bu yüzden "arka arkaya 2 benzer okuma"
 * yerine son [WINDOW_MS] içindeki okumalara bakılır: araya bozuk kareler girse
 * bile aynı altyazının 2 benzer okuması bir araya gelince onaylanır ve grubun
 * en güvenilir (ML Kit güveni en yüksek) okuması seçilir.
 *
 * Once confirmed, small OCR fluctuations are ignored until the subtitle clears
 * or a different reading repeats enough to be a new subtitle.
 */
class OcrStabilizer(
    private val onConfirmed: (String) -> Unit
) {
    private data class Reading(val text: String, val confidence: Float, val at: Long)

    private val lock = Any()
    private var confirmed: String? = null
    private val recent = ArrayDeque<Reading>()

    fun reset() {
        synchronized(lock) {
            confirmed = null
            recent.clear()
            Log.d(TAG, "Stabilizer fully reset")
        }
    }

    /**
     * Altyazı kaybolduğunda çağrılır; aynı metnin tekrar tespit edilmesine izin verir.
     */
    fun notifyEmpty() {
        synchronized(lock) {
            confirmed = null
            recent.clear()
        }
    }

    fun submit(raw: String, confidence: Float = 0f) {
        val text = raw.replace(SPACES_REGEX, " ").trim()
        if (text.length < 2) return

        var approved: String? = null
        synchronized(lock) {
            val currentConfirmed = confirmed
            if (currentConfirmed != null && similar(text, currentConfirmed)) {
                // OCR readings that fluctuate slightly while a subtitle remains visible
                // belong to the already confirmed subtitle. Keep it locked.
                recent.clear()
                return
            }

            val now = System.currentTimeMillis()
            while (recent.isNotEmpty() && now - recent.first().at > WINDOW_MS) recent.removeFirst()

            val reading = Reading(text, confidence, now)
            val group = recent.filter { similar(it.text, text) } + reading
            recent.addLast(reading)
            while (recent.size > WINDOW_SIZE) recent.removeFirst()

            if (group.size >= REQUIRED_HITS) {
                val best = group.maxBy { score(it) }
                confirmed = best.text
                recent.clear()
                approved = best.text
                Log.d(TAG, "Confirmed (${group.size} hits, conf=${best.confidence}): \"${best.text}\"")
            }
        }

        approved?.let { onConfirmed(it) }
    }

    /** Önce ML Kit güveni; güven yoksa/eşitse cümle sonu noktalaması ve uzunluk. */
    private fun score(reading: Reading): Float {
        val punctuationBonus = if (reading.text.lastOrNull()?.let { it in ".?!" } == true) 4f else 0f
        return reading.confidence * 100f + punctuationBonus + reading.text.length * 0.05f
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
        private const val REQUIRED_HITS = 2
        // ~6 OCR/sn ile 1.5 sn ≈ 9 okuma; araya giren bozuk kareleri tolere eder
        private const val WINDOW_MS = 1500L
        private const val WINDOW_SIZE = 8
        private const val SIMILARITY_THRESHOLD = 0.75f

        private val SPACES_REGEX = Regex("\\s+")
    }
}
