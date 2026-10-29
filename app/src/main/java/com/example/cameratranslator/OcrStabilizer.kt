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
    /** Okuma temiz mi (bütün kelimeler gerçek kelime)? İki temiz ve neredeyse aynı okuma hemen onaylanır. */
    private val isCleanReading: (String) -> Boolean = { false },
    /** Onay için bir okuma daha bekleniyorken en olası metin (önden çeviri için). */
    private val onLikelyCandidate: (String) -> Unit = {},
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
        var approved: String? = null
        synchronized(lock) {
            // Altyazı onay için 3. okumayı beklerken kaybolduysa elimizdeki en iyisini ver
            if (confirmed == null) {
                val pending = recent
                    .map { reading -> recent.filter { similar(it.text, reading.text) } }
                    .maxByOrNull { it.size }
                if (pending != null && pending.size >= REQUIRED_HITS) {
                    approved = vote(pending)
                    Log.d(TAG, "Confirmed on clear (${pending.size} hits): \"$approved\"")
                }
            }
            confirmed = null
            recent.clear()
        }
        approved?.let { onConfirmed(it) }
    }

    fun submit(raw: String, confidence: Float = 0f) {
        val text = raw.replace(SPACES_REGEX, " ").trim()
        if (text.length < 2) return

        var approved: String? = null
        var candidate: String? = null
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

            if (isReady(group, now)) {
                val voted = vote(group)
                confirmed = voted
                recent.clear()
                approved = voted
                Log.d(TAG, "Confirmed (${group.size} hits, ${now - group.first().at} ms after first reading): \"$voted\"")
            } else if (group.size == REQUIRED_HITS) {
                candidate = vote(group)
            }
        }

        candidate?.let { onLikelyCandidate(it) }
        approved?.let { onConfirmed(it) }
    }

    /**
     * Denendi ve geri alındı: temiz ilk okumayı hemen onaylamak ve 2 okumayla
     * yetinmek. Yarım okumalar (".Yeah, I saw her on") ve daha bozuk metinler onaylandı.
     *
     * İki okuma birebir aynıysa hemen onaylanır (gecikme yok). Farklıysa kelime
     * oylaması için bir okuma daha beklenir; o da gelmezse [MAX_WAIT_MS] sonra
     * eldekiyle devam edilir. Ekrana tek seferde, oylanmış sonuç gider.
     */
    private fun isReady(group: List<Reading>, now: Long): Boolean {
        if (group.size >= VOTE_HITS) return true
        if (group.size < REQUIRED_HITS) return false
        val keys = group.map { compareKey(it.text) }
        if (keys.distinct().size < keys.size) return true
        if (hasTwoCleanNearIdentical(group, keys)) return true
        return now - group.first().at >= MAX_WAIT_MS
    }

    /**
     * İki okuma neredeyse aynıysa ("brothers," / "brothers.", "Let's" / "Lets") ve ikisinde de
     * bütün kelimeler gerçek kelimeyse, üçüncü okumayı beklemeye gerek yok. Tek okumaya
     * güvenilmez (yarım okuma riski); iki ayrı karenin aynı temiz metni vermesi gerekir.
     */
    private fun hasTwoCleanNearIdentical(group: List<Reading>, keys: List<String>): Boolean {
        for (i in group.indices) for (j in i + 1 until group.size) {
            if (textSimilarity(keys[i], keys[j]) < NEAR_IDENTICAL) continue
            if (isCleanReading(group[i].text) && isCleanReading(group[j].text)) return true
        }
        return false
    }

    /**
     * Kareler arası kelime oylaması: en güvenilir okuma iskelet seçilir, her
     * kelimesi diğer okumalardaki karşılığıyla hizalanır ve en çok tekrarlanan
     * yazım kullanılır. Bir karede "rmessirig", ikisinde "messing"
     * okunduysa "messing" kazanır.
     */
    private fun vote(group: List<Reading>): String {
        if (group.size == 1) return group.first().text
        val keys = group.map { compareKey(it.text) }
        // İskelet: ML Kit güveni en yüksek okuma; diğerleriyle uyumu küçük bir katkı
        val backboneIndex = group.indices.maxBy { i ->
            val agreement = group.indices.filter { it != i }.sumOf { j -> textSimilarity(keys[i], keys[j]).toDouble() }
            agreement / group.size * 0.3 + score(group[i]) / 100.0
        }
        val backbone = group[backboneIndex].text.split(' ')
        val candidates = backbone.map { mutableListOf(it to group[backboneIndex].confidence) }

        for ((index, reading) in group.withIndex()) {
            if (index == backboneIndex) continue
            val aligned = alignWords(backbone, reading.text.split(' '))
            for (i in backbone.indices) {
                val word = aligned[i] ?: continue
                if (isSameWord(backbone[i], word)) candidates[i] += word to reading.confidence
            }
        }

        // Her yazım için oy = okuma sayısı + güven/2; hiçbiri tekrar etmiyorsa en güvenli okumanınki kalır
        return candidates.joinToString(" ") { options ->
            val votes = LinkedHashMap<String, Float>()
            for ((word, confidence) in options) {
                votes[word] = (votes[word] ?: 0f) + 1f + confidence.coerceIn(0f, 1f) * 0.5f
            }
            votes.maxBy { it.value }.key
        }
    }

    /** Kelime dizilerini hizalar: iskeletteki her kelimenin diğer okumadaki karşılığı (yoksa null). */
    private fun alignWords(backbone: List<String>, other: List<String>): Array<String?> {
        val n = backbone.size
        val m = other.size
        val cost = Array(n + 1) { FloatArray(m + 1) }
        for (i in 0..n) cost[i][0] = i * GAP_COST
        for (j in 0..m) cost[0][j] = j * GAP_COST
        for (i in 1..n) {
            for (j in 1..m) {
                val substitute = cost[i - 1][j - 1] + wordDistance(backbone[i - 1], other[j - 1])
                cost[i][j] = minOf(substitute, cost[i - 1][j] + GAP_COST, cost[i][j - 1] + GAP_COST)
            }
        }

        val result = arrayOfNulls<String>(n)
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (cost[i][j]) {
                cost[i - 1][j - 1] + wordDistance(backbone[i - 1], other[j - 1]) -> {
                    result[i - 1] = other[j - 1]
                    i--
                    j--
                }
                cost[i - 1][j] + GAP_COST -> i--
                else -> j--
            }
        }
        return result
    }

    /** Aynı kelimenin farklı okuması mı? Birleşik okunan kelimeler ("ifyou") "you" sayılmasın diye boy oranı da bakılır. */
    private fun isSameWord(a: String, b: String): Boolean {
        val left = wordKey(a)
        val right = wordKey(b)
        if (left.isEmpty() || right.isEmpty()) return a == b
        val lengthRatio = min(left.length, right.length).toFloat() / max(left.length, right.length)
        return lengthRatio >= 0.75f && wordDistance(a, b) <= 0.45f
    }

    private fun wordDistance(a: String, b: String): Float {
        val left = wordKey(a)
        val right = wordKey(b)
        val longest = max(left.length, right.length)
        if (longest == 0) return if (a == b) 0f else 1f
        return levenshtein(left, right).toFloat() / longest
    }

    private fun wordKey(word: String): String = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private fun textSimilarity(a: String, b: String): Float {
        val longest = max(a.length, b.length)
        if (longest == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / longest
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
        // Okumalar birbirini tutmuyorsa oylama için 3 okuma beklenir (~1 OCR turu, ~0.2 sn)
        private const val VOTE_HITS = 3
        // 3. benzer okuma bu sürede gelmezse 2 okumayla devam edilir
        private const val MAX_WAIT_MS = 500L
        private const val GAP_COST = 0.8f
        // "brothers," / "brothers." gibi 1-2 karakterlik OCR farkları
        private const val NEAR_IDENTICAL = 0.9f
        // ~6 OCR/sn ile 1.5 sn ≈ 9 okuma; araya giren bozuk kareleri tolere eder
        private const val WINDOW_MS = 1500L
        private const val WINDOW_SIZE = 8
        private const val SIMILARITY_THRESHOLD = 0.75f

        private val SPACES_REGEX = Regex("\\s+")
    }
}
