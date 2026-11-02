package com.example.cameratranslator

import android.content.Context
import android.content.SharedPreferences

enum class AppTheme {
    LIGHT, DARK, SYSTEM
}

enum class VoiceGender {
    FEMALE, MALE
}

enum class TtsEngineType {
    NATIVE,    // Google Translate / Native TTS Engine
    CLOUD_TTS  // Google Cloud Text-to-Speech REST API
}

enum class DisplayMode {
    DEFAULT,     // Varsayılan Kamera Ekranı
    SCREEN_BETA  // Ekran Çevirisi
}

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun saveApiKey(key: String) {
        prefs.edit().putString("google_cloud_api_key", key).apply()
    }

    fun getApiKey(): String {
        return prefs.getString("google_cloud_api_key", "") ?: ""
    }

    fun saveTtsApiKey(key: String) {
        prefs.edit().putString("google_cloud_tts_api_key", key).apply()
    }

    fun getTtsApiKey(): String {
        return prefs.getString("google_cloud_tts_api_key", "") ?: ""
    }

    fun saveTheme(theme: AppTheme) {
        prefs.edit().putString("app_theme", theme.name).apply()
    }

    fun getTheme(): AppTheme {
        val themeName = prefs.getString("app_theme", AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name
        return try {
            AppTheme.valueOf(themeName)
        } catch (e: Exception) {
            AppTheme.SYSTEM
        }
    }

    fun saveDisplayMode(mode: DisplayMode) {
        prefs.edit().putString("display_mode", mode.name).apply()
    }

    fun getDisplayMode(): DisplayMode {
        val name = prefs.getString("display_mode", DisplayMode.DEFAULT.name) ?: DisplayMode.DEFAULT.name
        return try {
            DisplayMode.valueOf(name)
        } catch (e: Exception) {
            DisplayMode.DEFAULT
        }
    }

    fun saveShowDisplayModeQuickSwitch(show: Boolean) {
        prefs.edit().putBoolean("show_display_mode_quick_switch", show).apply()
    }

    fun getShowDisplayModeQuickSwitch(): Boolean {
        return prefs.getBoolean("show_display_mode_quick_switch", false)
    }

    fun saveOcrTextMaskEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ocr_text_mask_enabled", enabled).apply()
    }

    fun getOcrTextMaskEnabled(): Boolean {
        return prefs.getBoolean("ocr_text_mask_enabled", false)
    }

    fun saveOcrCenterOnlyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ocr_center_only_enabled", enabled).apply()
    }

    fun getOcrCenterOnlyEnabled(): Boolean {
        return prefs.getBoolean("ocr_center_only_enabled", true)
    }

    fun saveOpusCarefulEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("opus_careful_enabled", enabled).apply()
    }

    fun getOpusCarefulEnabled(): Boolean {
        return prefs.getBoolean("opus_careful_enabled", false)
    }

    /** Ana ekrandaki yakınlaştırma kontrolü: true = kaydırıcı (geniş açı–20x), false = 1x–5x düğmeleri. */
    fun saveZoomSliderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("zoom_slider_enabled", enabled).apply()
    }

    fun getZoomSliderEnabled(): Boolean {
        return prefs.getBoolean("zoom_slider_enabled", true)
    }

    /** Kameranın son bilinen zoom aralığı (kaydırıcı açılışta doğru yerde çizilsin diye). */
    fun saveZoomRange(min: Float, max: Float) {
        prefs.edit().putFloat("zoom_range_min", min).putFloat("zoom_range_max", max).apply()
    }

    fun getZoomRange(): ClosedFloatingPointRange<Float>? {
        if (!prefs.contains("zoom_range_min")) return null
        return prefs.getFloat("zoom_range_min", 1f)..prefs.getFloat("zoom_range_max", 1f)
    }

    fun saveCloudModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("cloud_mode_enabled", enabled).apply()
    }

    /**
     * Google Cloud modu ana ekranda görünsün mü? Varsayılan kapalı (ücretli servis);
     * daha önce API anahtarı girmiş kullanıcılarda açık başlar.
     */
    fun getCloudModeEnabled(): Boolean {
        return prefs.getBoolean("cloud_mode_enabled", getApiKey().isNotBlank())
    }

    /** İlk açılıştaki "OPUS modelini indir" önerisi gösterildi mi (bir kez gösterilir). */
    fun saveOpusPromptShown() {
        prefs.edit().putBoolean("opus_prompt_shown", true).apply()
    }

    fun getOpusPromptShown(): Boolean {
        return prefs.getBoolean("opus_prompt_shown", false)
    }

    fun saveLastUpdateCheckAt(timeMillis: Long) {
        prefs.edit().putLong("last_update_check_at", timeMillis).apply()
    }

    fun getLastUpdateCheckAt(): Long {
        return prefs.getLong("last_update_check_at", 0L)
    }

    /** Açılışta "yeni sürüm" penceresi gösterilmiş son sürüm (aynı sürüm için tekrar sorulmaz). */
    fun savePromptedUpdateVersion(version: String) {
        prefs.edit().putString("prompted_update_version", version).apply()
    }

    fun getPromptedUpdateVersion(): String {
        return prefs.getString("prompted_update_version", "") ?: ""
    }

    // Independent Subtitle Font Size for Default Mode vs Screen Mode
    fun saveSubtitleFontSizeDefault(size: Float) {
        prefs.edit().putFloat("subtitle_font_size_default", size.coerceIn(20f, 85f)).apply()
    }

    fun getSubtitleFontSizeDefault(): Float {
        return prefs.getFloat("subtitle_font_size_default", 30f).coerceIn(20f, 85f)
    }

    fun saveSubtitleFontSizeScreen(size: Float) {
        prefs.edit().putFloat("subtitle_font_size_screen", size.coerceIn(12f, 85f)).apply()
    }

    fun getSubtitleFontSizeScreen(): Float {
        return prefs.getFloat("subtitle_font_size_screen", 20f).coerceIn(12f, 85f)
    }

    fun saveTranslationMode(mode: TranslationMode) {
        prefs.edit().putString("translation_mode", mode.name).apply()
    }

    fun getTranslationMode(): TranslationMode {
        // Yeni kurulumlar OPUS (OFFLINE) ile açılır
        val modeName = prefs.getString("translation_mode", TranslationMode.OFFLINE.name) ?: TranslationMode.OFFLINE.name
        // Eski ayrı "Offline+" modu artık OPUS modunun içinde
        if (modeName == "OFFLINE_PLUS") return TranslationMode.OFFLINE
        val mode = try {
            TranslationMode.valueOf(modeName)
        } catch (e: Exception) {
            TranslationMode.OFFLINE
        }
        // Google Cloud ayarlardan kapatıldıysa OPUS'a dön
        return if (mode == TranslationMode.CLOUD && !getCloudModeEnabled()) TranslationMode.OFFLINE else mode
    }

    fun saveTranslationDirection(isTrToEn: Boolean) {
        prefs.edit().putBoolean("is_tr_to_en", isTrToEn).apply()
    }

    fun getTranslationDirection(): Boolean {
        return prefs.getBoolean("is_tr_to_en", false)
    }

    fun saveTtsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_enabled", enabled).apply()
    }

    fun getTtsEnabled(): Boolean {
        return prefs.getBoolean("tts_enabled", false)
    }

    fun saveTtsEngineType(engine: TtsEngineType) {
        prefs.edit().putString("tts_engine_type", engine.name).apply()
    }

    fun getTtsEngineType(): TtsEngineType {
        val name = prefs.getString("tts_engine_type", TtsEngineType.NATIVE.name) ?: TtsEngineType.NATIVE.name
        return try {
            TtsEngineType.valueOf(name)
        } catch (e: Exception) {
            TtsEngineType.NATIVE
        }
    }

    fun saveSpeechRate(rate: Float) {
        prefs.edit().putFloat("speech_rate", rate).apply()
    }

    fun getSpeechRate(): Float {
        return prefs.getFloat("speech_rate", 1.15f)
    }

    fun saveCloudSpeechRate(rate: Float) {
        prefs.edit().putFloat("cloud_speech_rate", rate.coerceIn(0.8f, 1.6f)).apply()
    }

    fun getCloudSpeechRate(): Float {
        return prefs.getFloat("cloud_speech_rate", 1.0f).coerceIn(0.8f, 1.6f)
    }

    fun saveSpeechPitch(pitch: Float) {
        prefs.edit().putFloat("speech_pitch", pitch).apply()
    }

    fun getSpeechPitch(): Float {
        return prefs.getFloat("speech_pitch", 1.0f)
    }

    fun saveVoiceGender(gender: VoiceGender) {
        prefs.edit().putString("voice_gender", gender.name).apply()
    }

    fun getVoiceGender(): VoiceGender {
        val name = prefs.getString("voice_gender", VoiceGender.FEMALE.name) ?: VoiceGender.FEMALE.name
        return try {
            VoiceGender.valueOf(name)
        } catch (e: Exception) {
            VoiceGender.FEMALE
        }
    }

    fun saveTrCloudVoice(voiceId: String) {
        prefs.edit().putString("preferred_tr_cloud_voice", voiceId).apply()
    }

    fun getTrCloudVoice(): String {
        return prefs.getString("preferred_tr_cloud_voice", "tr-TR-Chirp3-HD-Kore") ?: "tr-TR-Chirp3-HD-Kore"
    }

    fun saveEnCloudVoice(voiceId: String) {
        prefs.edit().putString("preferred_en_cloud_voice", voiceId).apply()
    }

    fun getEnCloudVoice(): String {
        return prefs.getString("preferred_en_cloud_voice", "en-US-Chirp3-HD-Kore") ?: "en-US-Chirp3-HD-Kore"
    }

    // Default Mode OCR Rects
    fun saveOcrRectDefaultPortrait(left: Float, top: Float, right: Float, bottom: Float) {
        prefs.edit()
            .putFloat("ocr_def_port_left", left)
            .putFloat("ocr_def_port_top", top)
            .putFloat("ocr_def_port_right", right)
            .putFloat("ocr_def_port_bottom", bottom)
            .apply()
    }

    fun getOcrRectDefaultPortrait(): FloatArray {
        val left = prefs.getFloat("ocr_def_port_left", 0.1f)
        val top = prefs.getFloat("ocr_def_port_top", 0.7f)
        val right = prefs.getFloat("ocr_def_port_right", 0.9f)
        val bottom = prefs.getFloat("ocr_def_port_bottom", 0.85f)
        return floatArrayOf(left, top, right, bottom)
    }

    fun saveOcrRectDefaultLandscape(left: Float, top: Float, right: Float, bottom: Float) {
        prefs.edit()
            .putFloat("ocr_def_land_left", left)
            .putFloat("ocr_def_land_top", top)
            .putFloat("ocr_def_land_right", right)
            .putFloat("ocr_def_land_bottom", bottom)
            .apply()
    }

    fun getOcrRectDefaultLandscape(): FloatArray {
        val left = prefs.getFloat("ocr_def_land_left", 0.15f)
        val top = prefs.getFloat("ocr_def_land_top", 0.7f)
        val right = prefs.getFloat("ocr_def_land_right", 0.85f)
        val bottom = prefs.getFloat("ocr_def_land_bottom", 0.88f)
        return floatArrayOf(left, top, right, bottom)
    }

    /** Sinematik altyazıda önceki çevirilerin akışı (varsayılan: yukarı, 5 satır) */
    fun getSubtitleFlow(): SubtitleFlow =
        runCatching { SubtitleFlow.valueOf(prefs.getString("subtitle_flow", null) ?: "UP") }.getOrDefault(SubtitleFlow.UP)

    fun saveSubtitleFlow(flow: SubtitleFlow) {
        prefs.edit().putString("subtitle_flow", flow.name).apply()
    }

    fun getFlowLines(): Int = prefs.getInt("subtitle_flow_lines", 5).coerceIn(1, 10)

    fun saveFlowLines(lines: Int) {
        prefs.edit().putInt("subtitle_flow_lines", lines.coerceIn(1, 10)).apply()
    }

    /** "Yenilikler" penceresinin en son gösterildiği (ya da gerek görülmediği) sürüm; null = hiç kaydedilmedi */
    fun getLastSeenVersion(): String? = prefs.getString("last_seen_version", null)

    fun saveLastSeenVersion(version: String) {
        prefs.edit().putString("last_seen_version", version).apply()
    }

    /** Çeviri kaynağı: kamera (TV) ya da telefonun kendi ekranı */
    fun isPhoneScreenSource(): Boolean = prefs.getBoolean("phone_screen_source", false)

    fun savePhoneScreenSource(enabled: Boolean) {
        prefs.edit().putBoolean("phone_screen_source", enabled).apply()
    }

    /** Telefon ekranı modundaki OCR alanı sayısı (1–3) */
    fun getPhoneAreaCount(): Int = prefs.getInt("phone_area_count", 1).coerceIn(1, 3)

    fun savePhoneAreaCount(count: Int) {
        prefs.edit().putInt("phone_area_count", count.coerceIn(1, 3)).apply()
    }

    /** Telefon ekranı modundaki OCR alanının yeri (ekrana oranla); varsayılanlar: altyazı, sol üst, sağ üst */
    fun getPhoneAreaRect(index: Int, landscape: Boolean): FloatArray {
        val key = "phone_area${index}_${if (landscape) "land" else "port"}"
        val defaults = when (index) {
            0 -> if (landscape) floatArrayOf(0.15f, 0.74f, 0.85f, 0.94f) else floatArrayOf(0.05f, 0.72f, 0.95f, 0.88f)
            1 -> floatArrayOf(0.03f, 0.08f, 0.45f, 0.26f)
            else -> floatArrayOf(0.55f, 0.08f, 0.97f, 0.26f)
        }
        return floatArrayOf(
            prefs.getFloat("${key}_left", defaults[0]),
            prefs.getFloat("${key}_top", defaults[1]),
            prefs.getFloat("${key}_right", defaults[2]),
            prefs.getFloat("${key}_bottom", defaults[3])
        )
    }

    fun savePhoneAreaRect(index: Int, landscape: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        val key = "phone_area${index}_${if (landscape) "land" else "port"}"
        prefs.edit()
            .putFloat("${key}_left", left)
            .putFloat("${key}_top", top)
            .putFloat("${key}_right", right)
            .putFloat("${key}_bottom", bottom)
            .apply()
    }

    /** İlk açılış rehberi görüldü mü (ya da uygulamayı zaten kullanan biri mi) */
    fun isGuideSeen(): Boolean = prefs.getBoolean("guide_seen", false)

    fun saveGuideSeen(seen: Boolean) {
        prefs.edit().putBoolean("guide_seen", seen).apply()
    }

    /** Ekran çevirisinde ana alana ek OCR alanı sayısı (0–2) */
    fun getScreenExtraAreaCount(): Int = prefs.getInt("scr_extra_area_count", 0).coerceIn(0, 2)

    fun saveScreenExtraAreaCount(count: Int) {
        prefs.edit().putInt("scr_extra_area_count", count.coerceIn(0, 2)).apply()
    }

    /** Ek OCR alanının yeri ([index] 0 = 2. alan); varsayılanlar ekranın sol ve sağ üstü */
    fun getExtraOcrRect(index: Int, landscape: Boolean): FloatArray {
        val key = "ocr_extra${index}_${if (landscape) "land" else "port"}"
        val defaults = if (index == 0) floatArrayOf(0.03f, 0.06f, 0.45f, 0.24f) else floatArrayOf(0.55f, 0.06f, 0.97f, 0.24f)
        return floatArrayOf(
            prefs.getFloat("${key}_left", defaults[0]),
            prefs.getFloat("${key}_top", defaults[1]),
            prefs.getFloat("${key}_right", defaults[2]),
            prefs.getFloat("${key}_bottom", defaults[3])
        )
    }

    fun saveExtraOcrRect(index: Int, landscape: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        val key = "ocr_extra${index}_${if (landscape) "land" else "port"}"
        prefs.edit()
            .putFloat("${key}_left", left)
            .putFloat("${key}_top", top)
            .putFloat("${key}_right", right)
            .putFloat("${key}_bottom", bottom)
            .apply()
    }

    // Screen Beta Mode OCR Rects
    fun saveOcrRectScreenPortrait(left: Float, top: Float, right: Float, bottom: Float) {
        prefs.edit()
            .putFloat("ocr_scr_port_left", left)
            .putFloat("ocr_scr_port_top", top)
            .putFloat("ocr_scr_port_right", right)
            .putFloat("ocr_scr_port_bottom", bottom)
            .apply()
    }

    fun getOcrRectScreenPortrait(): FloatArray {
        val left = prefs.getFloat("ocr_scr_port_left", 0.1f)
        val top = prefs.getFloat("ocr_scr_port_top", 0.75f)
        val right = prefs.getFloat("ocr_scr_port_right", 0.9f)
        val bottom = prefs.getFloat("ocr_scr_port_bottom", 0.88f)
        return floatArrayOf(left, top, right, bottom)
    }

    fun saveOcrRectScreenLandscape(left: Float, top: Float, right: Float, bottom: Float) {
        prefs.edit()
            .putFloat("ocr_scr_land_left", left)
            .putFloat("ocr_scr_land_top", top)
            .putFloat("ocr_scr_land_right", right)
            .putFloat("ocr_scr_land_bottom", bottom)
            .apply()
    }

    fun getOcrRectScreenLandscape(): FloatArray {
        val left = prefs.getFloat("ocr_scr_land_left", 0.15f)
        val top = prefs.getFloat("ocr_scr_land_top", 0.75f)
        val right = prefs.getFloat("ocr_scr_land_right", 0.85f)
        val bottom = prefs.getFloat("ocr_scr_land_bottom", 0.90f)
        return floatArrayOf(left, top, right, bottom)
    }
}
