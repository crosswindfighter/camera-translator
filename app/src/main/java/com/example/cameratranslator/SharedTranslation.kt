package com.example.cameratranslator

import android.content.Context

/**
 * Uygulamanın tek çeviri yöneticisi: ana ekran ve telefon ekranı hizmeti aynı motoru (ve aynı yüklü OPUS
 * modelini) kullanır; model bellekte iki kez tutulmaz. Mod ve dil yönü ana ekrandan ayarlanır.
 */
object SharedTranslation {
    @Volatile private var instance: TranslationManager? = null

    fun get(context: Context): TranslationManager = instance ?: synchronized(this) {
        instance ?: TranslationManager(
            CompositeTranslationEngine(offlinePlus = OpusTranslationEngine(context.applicationContext))
        ).also { instance = it }
    }

    /** Uygulama tamamen kapanırken (telefon ekranı modu da çalışmıyorsa) */
    fun close() = synchronized(this) {
        instance?.close()
        instance = null
    }
}
