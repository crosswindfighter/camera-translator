package com.example.cameratranslator

/**
 * Bir OCR okuması "temiz" mi: bütün kelimeler gerçek İngilizce kelime (OPUS sözlüğünde
 * tek parça) ya da isim mi, içlerinde rakam/garip büyük harf/simge var mı?
 *
 * Stabilizer bunu, iki temiz ve neredeyse aynı okumayı üçüncüyü beklemeden onaylamak için
 * kullanır. Bozuk kelimeler ("sorne", "riind", "wejuSrask") sözlükte olmadığından bu yola
 * giremez; eskisi gibi oylamayla onaylanır.
 */
object CleanReading {

    /** OPUS sözlüğünde tek parça olmayan ama altyazılarda sık geçen ünlemler. */
    private val EXTRA_WORDS = setOf(
        "oh", "ohh", "uh", "uhh", "um", "hmm", "ah", "ahh", "huh", "whoa", "wow", "ok", "okay",
        "nah", "yep", "yup", "hey", "shh", "ugh", "yo", "ya"
    )
    private val SPACES = Regex("\\s+")
    private val MIXED_DIGIT = Regex("\\p{L}\\d|\\d\\p{L}")

    fun isClean(text: String, isKnownWord: (String) -> Boolean): Boolean {
        var sentenceStart = true
        var words = 0
        for (token in text.split(SPACES)) {
            if (token.isBlank()) continue
            val core = token.trim { !it.isLetterOrDigit() && it != '\'' }.trim('\'')
            if (core.isNotEmpty()) {
                if (!isCleanWord(core, sentenceStart, isKnownWord)) return false
                words++
            }
            sentenceStart = token.trimEnd('"', '\'', ')').lastOrNull()?.let { it in ".!?" } == true
        }
        return words > 0
    }

    private fun isCleanWord(word: String, sentenceStart: Boolean, isKnownWord: (String) -> Boolean): Boolean {
        if (MIXED_DIGIT.containsMatchIn(word)) return false // b9, cowD9y
        if (word.all { it.isDigit() }) return true
        // Kısaltmalar: don't -> don, I've -> I, cousin's -> cousin
        val stem = word.substringBefore('\'')
        if (stem.isEmpty()) return true
        if (!stem.all { it in 'a'..'z' || it in 'A'..'Z' }) return false // şapkalı/yabancı harf: OCR hatası
        if (stem.length == 1) return stem.lowercase() in setOf("a", "i", "o")
        // Tamamı büyük harf: kısaltma ya da bağırarak yazılmış altyazı (FIB, NO)
        if (stem.all { it.isUpperCase() }) return true
        // Kelime içinde rastgele büyük harf: "wejuSrask", "minuteS"
        if (stem.drop(1).any { it.isUpperCase() }) return false
        // Cümle ortasında büyük harfle başlayan kelime büyük ihtimalle isim (Townley, Los Santos)
        if (stem[0].isUpperCase() && !sentenceStart) return true

        val lower = stem.lowercase()
        return isKnownWord(lower) || lower in EXTRA_WORDS ||
            (lower.endsWith("s") && isKnownWord(lower.dropLast(1)))
    }
}
