package com.example.cameratranslator

/**
 * OCR'ın bozduğu İngilizce kelimeleri çeviriden önce düzeltir; yapay zekâ modeli değil,
 * OPUS sözlüğüne (≈25 bin tam kelime + sıklık puanı) dayalı hızlı bir kural sistemi.
 *
 * Yalnızca sözlükte olmayan kelimelere dokunur, sırayla şunları dener:
 *  1. OCR'ın tipik karışıklıkları: rn→m, ri→n, y→v, rn→n, çift harf (gota→gotta) …
 *  2. Tek harf hatası (fust→just, meke→make)
 *  3. Birleşik okunan iki kelime (ofyou→of you, theonly→the only)
 *  4. Uzun kelimede harf hatası + karışıklık
 *
 * Yanlış düzeltmeye karşı: isimler (sözlükte büyük harfle kayıtlı olanlar, cümle ortasında
 * büyük harfli kelimeler), tamamı büyük harf kısaltmalar ve 1-2 harfli kelimeler korunur;
 * cümle başındaki bilinmeyen büyük harfli kelime yalnızca yaygın bir kelimeye düzeltilir.
 * Gerçek OCR loglarındaki okumalarla bilgisayarda test edildi.
 */
class OcrWordCorrector(
    /** Küçük harfli tam kelimeler ve OPUS sıklık puanları (büyük = daha yaygın). */
    private val scores: Map<String, Float>,
    /** Sözlükte büyük harfle kayıtlı kelimeler (Wade, Trevor, Michael …): isim, düzeltilmez. */
    private val casedWords: Set<String>
) {
    private val rank: Map<String, Int> =
        scores.entries.sortedByDescending { it.value }.withIndex().associate { (index, entry) -> entry.key to index }

    fun correct(text: String): String {
        var sentenceStart = true
        val out = ArrayList<String>()
        for (token in text.split(SPACES)) {
            if (token.isEmpty()) continue
            out += correctToken(token, sentenceStart)
            sentenceStart = token.trimEnd('"', '\'', ')').lastOrNull()?.let { it in ".!?" } == true
        }
        return out.joinToString(" ")
    }

    private fun correctToken(token: String, sentenceStart: Boolean): String {
        val match = TOKEN.matchEntire(token) ?: return token
        val (pre, word, post) = match.destructured
        val stem = word.substringBefore('\'')
        val letters = word.filter { it.isLetter() }
        val allCaps = letters.isNotEmpty() && letters.all { it.isUpperCase() }
        val capitalized = word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }

        if (stem.isEmpty() || isKnown(stem.lowercase()) || stem.length <= 2 || allCaps) return token
        if ((capitalized && !sentenceStart) || word in casedWords) return token

        if (word.drop(1).any { it.isUpperCase() }) {
            // "factI", "thiSpenis": büyük harften bölmek kelimeleri (ve "I"yı) korur
            for (k in 1 until word.length) {
                if (!word[k].isUpperCase()) continue
                val left = word.substring(0, k)
                val right = word.substring(k)
                val leftOk = (left.length >= 2 || left.lowercase() in FUNCTION_WORDS) &&
                    (left.lowercase() in scores || left.lowercase() in FUNCTION_WORDS)
                if (leftOk && (right == "I" || right.lowercase() in scores)) {
                    return pre + left + " " + (if (right == "I") right else right.lowercase()) + post
                }
            }
            return token
        }
        if ('\'' in word) return token

        val lower = word.lowercase()
        if (!lower.all { it in 'a'..'z' || it.isDigit() }) return token
        val normalized = lower.replace('0', 'o').replace('9', 'g').replace('1', 'l')
        // Cümle başındaki büyük harfli bilinmeyen kelime isim olabilir: yalnızca yaygın kelimeye düzelt
        var fixed = correctWord(normalized, commonOnly = capitalized) ?: return token
        if (word[0].isUpperCase()) fixed = fixed.replaceFirstChar { it.uppercaseChar() }
        return pre + fixed + post
    }

    private fun correctWord(word: String, commonOnly: Boolean): String? {
        fun acceptable(candidate: String?): Boolean =
            candidate != null && (!commonOnly || (rank[candidate.substringBefore(' ')] ?: Int.MAX_VALUE) < COMMON_RANK)

        CONTRACTIONS[word]?.let { return it }
        // Kısa kelimede yanlış düzeltme riski yüksek: yalnızca OCR karışıklığıyla ve çok yaygın kelimeye
        if (word.length <= 3) {
            val candidate = best(confusionVariants(word))
            return candidate?.takeIf { (rank[it] ?: Int.MAX_VALUE) < SHORT_WORD_RANK }
        }

        best(confusionVariants(word)).let { if (acceptable(it)) return it }
        best(edits1(word)).let { if (acceptable(it)) return it }

        // Argo "-in" sonu (trippin, nothin) bölünmesin
        if (!commonOnly && !word.endsWith("in")) {
            var bestSplit: String? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in 1 until word.length) {
                val left = word.substring(0, i)
                val right = word.substring(i)
                val leftScore = scores[left] ?: continue
                val rightScore = scores[right] ?: continue
                if ((left.length < 3 && left !in FUNCTION_WORDS) || (right.length < 3 && right !in FUNCTION_WORDS)) continue
                if (leftScore + rightScore > bestScore) {
                    bestScore = leftScore + rightScore
                    bestSplit = "$left $right"
                }
            }
            bestSplit?.let { return it }
        }

        if (word.length >= 6) {
            val candidates = HashSet<String>()
            for (edit in edits1(word)) candidates += confusionVariants(edit)
            for (variant in confusionVariants(word)) candidates += edits1(variant)
            best(candidates).let { if (acceptable(it)) return it }
        }
        return null
    }

    private fun isKnown(word: String): Boolean =
        word in scores || word in EXTRA_WORDS ||
            (word.endsWith("s") && word.dropLast(1) in scores) ||
            (word.endsWith("in") && (word + "g") in scores) // trippin, nothin

    private fun best(candidates: Collection<String>): String? =
        candidates.filter { it in scores }.maxByOrNull { scores.getValue(it) }

    private fun confusionVariants(word: String): Set<String> {
        val out = HashSet<String>()
        for ((right, read) in CONFUSIONS) {
            var index = word.indexOf(read)
            while (index >= 0) {
                out += word.substring(0, index) + right + word.substring(index + read.length)
                index = word.indexOf(read, index + 1)
            }
        }
        return out
    }

    private fun edits1(word: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0..word.length) {
            val a = word.substring(0, i)
            val b = word.substring(i)
            if (b.isNotEmpty()) {
                out += a + b.substring(1)
                for (c in 'a'..'z') out += a + c + b.substring(1)
            }
            if (b.length > 1) out += a + b[1] + b[0] + b.substring(2)
            for (c in 'a'..'z') out += a + c + b
        }
        return out
    }

    companion object {
        private val SPACES = Regex("\\s+")
        private val TOKEN = Regex("^([^A-Za-z0-9']*)([A-Za-z0-9']+)([^A-Za-z0-9']*)$")
        private const val COMMON_RANK = 12_000
        private const val SHORT_WORD_RANK = 3_500

        /** OPUS sözlüğünde tek parça olmayan ama altyazılarda sık geçen kelimeler (düzeltilmez). */
        private val EXTRA_WORDS = setOf(
            "oh", "ohh", "uh", "uhh", "um", "hmm", "ah", "ahh", "huh", "whoa", "wow", "ok", "okay",
            "nah", "yep", "yup", "hey", "shh", "ugh", "yo", "ya", "homie", "homies", "dawg", "bro", "cuz", "fam"
        )
        private val FUNCTION_WORDS = setOf(
            "a", "i", "of", "to", "in", "on", "at", "is", "it", "be", "we", "he", "me", "my", "do", "so",
            "no", "up", "by", "or", "an", "as", "if", "us", "go", "you", "the", "and", "for"
        )
        private val CONTRACTIONS = mapOf(
            "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't",
            "cant" to "can't", "wont" to "won't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
            "shouldnt" to "shouldn't", "youre" to "you're", "theyre" to "they're", "im" to "I'm", "ive" to "I've",
            "youve" to "you've", "weve" to "we've", "hes" to "he's", "shes" to "she's", "thats" to "that's",
            "whats" to "what's", "theres" to "there's", "therell" to "there'll", "youll" to "you'll",
            "itll" to "it'll", "aint" to "ain't", "havent" to "haven't"
        )
        /** (doğru, OCR'ın okuduğu) */
        private val CONFUSIONS = listOf(
            "m" to "rn", "m" to "rm", "m" to "ri", "n" to "ri", "h" to "li", "d" to "cl", "w" to "vv",
            "u" to "ii", "n" to "rn", "d" to "c",
            "tt" to "t", "ll" to "l", "ss" to "s", "ee" to "e", "oo" to "o", "pp" to "p", "ff" to "f", "rr" to "r",
            "v" to "y", "y" to "v", "i" to "l", "l" to "i", "o" to "0", "g" to "9",
            "e" to "c", "c" to "e", "h" to "b", "b" to "h", "n" to "h", "i" to "j", "t" to "f"
        )
    }
}
