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
        // Uzatılmış ünlem: Nooo, Ahhh, Yesss ("Nooo" -> "Noon" oluyordu)
        if (hasTripleLetter(word.lowercase())) return token
        // Virgülle biten büyük harfli kelime çoğunlukla hitap/isimdir ("Tracey, go home!"):
        // yalnızca harf ekleyen düzeltmeye izin ver (Alrigh, -> Alright, ama Tracey, -/-> Trace,)
        val vocative = capitalized && post.startsWith(",")
        // "I" kelimeye yapışmış: Ihate -> I hate, Ijust -> I just
        if (word.length >= 3 && word[0] == 'I' && word.drop(1).all { it.isLowerCase() } && isKnown(word.substring(1))) {
            return pre + "I " + word.substring(1) + post
        }

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
        var fixed = correctWord(normalized, commonOnly = capitalized, noShortening = vocative) ?: return token
        if (word[0].isUpperCase()) fixed = fixed.replaceFirstChar { it.uppercaseChar() }
        return pre + fixed + post
    }

    private fun correctWord(word: String, commonOnly: Boolean, noShortening: Boolean): String? {
        fun acceptable(candidate: String?): Boolean {
            if (candidate == null) return false
            if (commonOnly && rankOf(candidate.substringBefore(' ')) >= COMMON_RANK) return false
            return !noShortening || candidate.length >= word.length
        }

        CONTRACTIONS[word]?.let { return it }
        // Kısa kelimede yanlış düzeltme riski yüksek: yalnızca OCR karışıklığıyla ve çok yaygın kelimeye
        if (word.length <= 3) {
            val candidate = best(confusionVariants(word))
            return candidate?.takeIf { rankOf(it) < SHORT_WORD_RANK }
        }

        // Argo "-in" sonu (trippin, nothin) bölünmesin
        val split = if (commonOnly || word.endsWith("in")) null else bestSplit(word)

        best(confusionVariants(word)).let { if (acceptable(it)) return it }
        best(edits1(word)).let { candidate ->
            if (acceptable(candidate)) {
                // "saton": iki çok yaygın kelimeye bölünebiliyorsa nadir bir kelimeye düzeltme ("salon")
                if (split != null && isCommonSplit(split) && rankOf(candidate!!) >= SPLIT_PART_RANK) {
                    return split.joinToString(" ")
                }
                return candidate
            }
        }
        split?.let { return it.joinToString(" ") }

        if (word.length >= 6) {
            val candidates = HashSet<String>()
            for (edit in edits1(word)) candidates += confusionVariants(edit)
            for (variant in confusionVariants(word)) candidates += edits1(variant)
            best(candidates).let { if (acceptable(it)) return it }
        }
        return null
    }

    /** Sözlükte ya da çekimli hali sözlükte mi? relapsed, racer, jacking, daddies, stopped … */
    private fun isKnown(word: String): Boolean {
        if (word in scores || word in EXTRA_WORDS) return true
        if (word.endsWith("in") && (word + "g") in scores) return true // trippin, nothin
        for ((suffix, replacement) in SUFFIXES) {
            if (!word.endsWith(suffix) || word.length - suffix.length < 2) continue
            val stem = word.dropLast(suffix.length) + replacement
            if (stem in scores) return true
            // Çift ünsüz: stopped -> stop, jogging -> jog
            if (replacement.isEmpty() && stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] &&
                stem.dropLast(1) in scores
            ) return true
        }
        return false
    }

    private fun rankOf(word: String): Int = rank[word] ?: Int.MAX_VALUE

    /** İki kelimeye en olası bölme ("ofyou" -> of + you); kısa parça yalnızca gerçek kısa kelime olabilir. */
    private fun bestSplit(word: String): List<String>? {
        var result: List<String>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 1 until word.length) {
            val left = word.substring(0, i)
            val right = word.substring(i)
            val leftScore = scores[left] ?: continue
            val rightScore = scores[right] ?: continue
            if ((left.length < 3 && left !in FUNCTION_WORDS) || (right.length < 3 && right !in FUNCTION_WORDS)) continue
            if (leftScore + rightScore > bestScore) {
                bestScore = leftScore + rightScore
                result = listOf(left, right)
            }
        }
        return result
    }

    private fun isCommonSplit(parts: List<String>): Boolean =
        parts.all { it in FUNCTION_WORDS || rankOf(it) < SPLIT_PART_RANK }

    private fun hasTripleLetter(word: String): Boolean =
        (2 until word.length).any { word[it] == word[it - 1] && word[it] == word[it - 2] }

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
        /** Bölünmüş kelimenin iki parçası da bu kadar yaygınsa nadir bir tek kelimeye tercih edilir. */
        private const val SPLIT_PART_RANK = 3_000

        /** Çekim ekleri (ek, köke eklenecek): relapsed -> relapse, racer -> race, daddies -> daddy. */
        private val SUFFIXES = listOf(
            "ies" to "y", "ied" to "y", "ier" to "y", "iest" to "y", "ing" to "", "ing" to "e", "ed" to "", "ed" to "e",
            "er" to "", "er" to "e", "ers" to "", "ers" to "e", "est" to "", "ly" to "", "es" to "", "s" to "",
            "d" to "", "r" to ""
        )

        /** OPUS sözlüğünde tek parça olmayan ama altyazılarda sık geçen kelimeler (düzeltilmez). */
        private val EXTRA_WORDS = setOf(
            "oh", "ohh", "uh", "uhh", "um", "hmm", "ah", "ahh", "huh", "whoa", "wow", "ok", "okay",
            "nah", "yep", "yup", "hey", "shh", "ugh", "yo", "ya", "homie", "homies", "dawg", "bro", "cuz", "fam",
            "argh", "aargh", "ew", "eww", "yikes", "nope", "gee", "jeez", "damn", "dammit", "goddamn", "puke",
            "kinda", "sorta", "gotcha", "wassup", "sup", "lol"
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
