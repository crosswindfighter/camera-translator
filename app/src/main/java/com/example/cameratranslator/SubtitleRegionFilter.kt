package com.example.cameratranslator

import kotlin.math.abs

/**
 * OCR alanında yalnızca ortadaki altyazıyı bırakır; kenarlardaki HUD yazılarını
 * (GTA V'te sol alttaki harita, sağ alttaki telefon, görev yazıları) atar.
 *
 * Altyazılar kısa da olsa uzun da olsa yatayda ortalanır. Bu yüzden bir satırdaki
 * kelimeler büyük boşluklarla gruplara ayrılır ve yalnızca
 *  - alanın ortasını kapsayan, ya da
 *  - orta noktası alanın ortasına yakın olan
 * gruplar tutulur. ML Kit kenardaki HUD yazısını altyazıyla aynı satırda
 * birleştirse bile grup ayrımı sayesinde altyazı korunur.
 *
 * Kullanıcı OCR alanını altyazıya kabaca ortalar; tolerans bunu affedecek kadar geniş.
 */
object SubtitleRegionFilter {

    /** Bir satırdaki tek kelime: yatay sınırları ve yüksekliği (piksel). */
    data class Word(val text: String, val left: Int, val right: Int, val height: Int)

    /** Orta noktası alanın ortasından en fazla bu oran kadar uzak gruplar tutulur. */
    private const val CENTER_TOLERANCE = 0.22f

    /** İki kelime arası boşluk satır yüksekliğinin bu katından büyükse ayrı grup sayılır. */
    private const val GAP_FACTOR = 2.0f

    /**
     * Satırdaki ortadaki kelime gruplarını boşlukla birleştirip döndürür;
     * ortada hiçbir şey yoksa null.
     */
    fun keepCentered(words: List<Word>, imageWidth: Int): String? {
        if (words.isEmpty() || imageWidth <= 0) return null
        val sorted = words.sortedBy { it.left }
        val heights = sorted.map { it.height }.sorted()
        val lineHeight = heights[heights.size / 2].coerceAtLeast(1)
        val maxGap = lineHeight * GAP_FACTOR

        val groups = mutableListOf(mutableListOf(sorted.first()))
        for (word in sorted.drop(1)) {
            val previous = groups.last().last()
            if (word.left - previous.right > maxGap) groups += mutableListOf(word) else groups.last() += word
        }

        val center = imageWidth / 2f
        val tolerance = imageWidth * CENTER_TOLERANCE
        val kept = groups.filter { group ->
            val left = group.first().left
            val right = group.maxOf { it.right }
            val coversCenter = left <= center && right >= center
            val midNearCenter = abs((left + right) / 2f - center) <= tolerance
            coversCenter || midNearCenter
        }
        if (kept.isEmpty()) return null
        return kept.flatten().joinToString(" ") { it.text }
    }
}
