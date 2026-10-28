package com.example.cameratranslator

/**
 * OCR hattının çalışma anında açılıp kapatılabilen ayarları.
 * Ayarlar ekranından değişir, analyzer thread'i tarafından okunur.
 */
object OcrTuning {
    /** Beyaz altyazı filtresi: parlak/renksiz yazıyı bırakır, asfalt gibi dokulu arka planı siler. */
    @Volatile var textMaskEnabled: Boolean = false

    /**
     * OCR'a giden görüntüleri ve okunan metni telefona kaydeder (ayar yaparken teşhis için).
     * Geliştirici aracı: sürümde arayüzden kaldırıldı, gerektiğinde buradan true yapılır.
     */
    @Volatile var debugDumpEnabled: Boolean = false
}
