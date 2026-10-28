package com.example.cameratranslator

/**
 * OCR hattının çalışma anında açılıp kapatılabilen ayarları.
 * Ayarlar ekranından değişir, analyzer thread'i tarafından okunur.
 */
object OcrTuning {
    /** Beyaz altyazı filtresi: parlak/renksiz yazıyı bırakır, asfalt gibi dokulu arka planı siler. */
    @Volatile var textMaskEnabled: Boolean = true

    /** OCR'a giden görüntüleri ve okunan metni telefona kaydeder (ayar yaparken teşhis için). */
    @Volatile var debugDumpEnabled: Boolean = false
}
