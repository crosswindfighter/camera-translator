package com.example.cameratranslator

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Oyun altyazıları parlak ve renksiz (beyaz) olur; asfalt, gökyüzü, araba gibi
 * arka planlar ise daha koyu, dokulu ya da renklidir. Bu sınıf yalnızca altyazı
 * olabilecek pikselleri bırakıp ML Kit'e "beyaz zemin üstünde siyah yazı" verir.
 *
 * Eşik sabit değil: OCR alanındaki en parlak piksellere göre belirlenir, böylece
 * pozlama veya TV parlaklığı değişse de çalışır.
 *
 * Görüntü bu kalıba uymuyorsa (parlak bir şey yok ya da alanın büyük kısmı parlak)
 * null döner ve orijinal görüntü kullanılır; filtre işleri asla daha kötü yapmasın.
 */
object SubtitleTextMask {

    /** Eşiğin hesaplandığı parlaklık yüzdeliği (en parlak %0.5). */
    private const val PEAK_PERCENTILE = 0.995f

    /** Yazı sayılmak için en parlak bölgenin en az bu oranı kadar parlak olmalı. */
    private const val RELATIVE_THRESHOLD = 0.75f

    /** Eşik hiçbir zaman bunun altına inmez (koyu sahnede gri asfalt yazı sanılmasın). */
    private const val MIN_THRESHOLD = 120

    /** En parlak bölge bundan koyuysa ekranda beyaz altyazı yok demektir. */
    private const val MIN_PEAK = 110

    /** max(R,G,B) - min(R,G,B). Bunun üstü "renkli" sayılır (kırmızı HUD, mavi gökyüzü vb.). */
    private const val MAX_CHROMA = 140

    /** Kenar çizgisi, yazı eşiğinden en az bu kadar koyu olmalı. */
    private const val DARK_OUTLINE_MARGIN = 60

    /** Parlak pikselin kenar çizgisine en fazla bu kadar piksel uzak olabileceği (büyütülmüş görüntüde). */
    private const val OUTLINE_RADIUS = 6

    /** Yazı pikseli oranı bu aralık dışındaysa filtre güvenilmez, orijinal görüntü kullanılır. */
    private const val MIN_TEXT_RATIO = 0.002f
    private const val MAX_TEXT_RATIO = 0.35f

    fun apply(source: Bitmap): Bitmap? {
        val width = source.width
        val height = source.height
        val total = width * height
        if (total == 0) return null

        val pixels = IntArray(total)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. geçiş: parlaklık histogramı
        val luma = ByteArray(total)
        val histogram = IntArray(256)
        for (i in 0 until total) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            luma[i] = y.toByte()
            histogram[y]++
        }

        val peak = percentile(histogram, total, PEAK_PERCENTILE)
        if (peak < MIN_PEAK) return null
        val threshold = max(MIN_THRESHOLD, (peak * RELATIVE_THRESHOLD).toInt())

        // Oyun altyazılarında beyaz harfin etrafında koyu bir kenar çizgisi/gölge olur.
        // Açık renkli beton/asfalt da parlak ama yanında böyle koyu bir çizgi yok;
        // bu yüzden "yakınında koyu piksel olan parlak piksel" yazı sayılır.
        val darkLimit = threshold - DARK_OUTLINE_MARGIN
        val nearDark = ByteArray(total)
        for (i in 0 until total) {
            if ((luma[i].toInt() and 0xFF) <= darkLimit) nearDark[i] = 1
        }
        dilate(nearDark, width, height, OUTLINE_RADIUS)

        // 2. geçiş: yazı pikselleri siyah, geri kalan her şey beyaz
        var textPixels = 0
        for (i in 0 until total) {
            val y = luma[i].toInt() and 0xFF
            var isText = false
            if (y >= threshold && nearDark[i].toInt() != 0) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                isText = chroma <= MAX_CHROMA
            }
            if (isText) {
                pixels[i] = BLACK
                textPixels++
            } else {
                pixels[i] = WHITE
            }
        }

        val ratio = textPixels.toFloat() / total
        if (ratio < MIN_TEXT_RATIO || ratio > MAX_TEXT_RATIO) return null

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /** Kare biçimli genişletme (yatay + dikey geçiş): bir pikselin [radius] yakınında 1 varsa 1 olur. */
    private fun dilate(mask: ByteArray, width: Int, height: Int, radius: Int) {
        val temp = ByteArray(mask.size)
        for (row in 0 until height) {
            val base = row * width
            var lastOne = -radius - 1
            // soldan sağa: son görülen 1'in uzaklığı
            for (x in 0 until width) {
                if (mask[base + x].toInt() != 0) lastOne = x
                if (x - lastOne <= radius) temp[base + x] = 1
            }
            var nextOne = width + radius + 1
            // sağdan sola
            for (x in width - 1 downTo 0) {
                if (mask[base + x].toInt() != 0) nextOne = x
                if (nextOne - x <= radius) temp[base + x] = 1
            }
        }
        java.util.Arrays.fill(mask, 0)
        for (x in 0 until width) {
            var lastOne = -radius - 1
            for (row in 0 until height) {
                if (temp[row * width + x].toInt() != 0) lastOne = row
                if (row - lastOne <= radius) mask[row * width + x] = 1
            }
            var nextOne = height + radius + 1
            for (row in height - 1 downTo 0) {
                if (temp[row * width + x].toInt() != 0) nextOne = row
                if (nextOne - row <= radius) mask[row * width + x] = 1
            }
        }
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt()
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 255
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
