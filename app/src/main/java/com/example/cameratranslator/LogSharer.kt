package com.example.cameratranslator

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Geliştirici modu kaydını kullanıcının kolayca gönderebileceği küçük bir zip yapar ve Android'in paylaşım
 * menüsünü açar (e-posta, WhatsApp, Drive …). Zip'te yalnızca metin var: çeviri logu, OCR logu ve cihaz/ayar
 * bilgisi. Görüntüler Android/data'da kalır; e-postayla gönderilemeyecek kadar büyükler.
 *
 * Hiçbir şey kendiliğinden gönderilmez; kullanıcı nereye göndereceğini kendisi seçer. Zip uygulamanın geçici
 * alanında (cache/logs) hazırlanır ve bir sonraki paylaşımda silinir.
 */
object LogSharer {
    private const val LOG_DIR = "logs"
    private val LOG_FILES = listOf("ceviri_log.txt", "ocr_log.txt")

    /** Zip'i hazırlayıp paylaşım menüsünü açar; paylaşılacak log yoksa false. */
    suspend fun share(context: Context): Boolean {
        val zip = withContext(Dispatchers.IO) { buildZip(context) } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Camera Translator kaydı (v${BuildConfig.VERSION_NAME})")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "Kaydı paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun buildZip(context: Context): File? {
        val source = context.getExternalFilesDir("ocr_debug") ?: return null
        val logs = LOG_FILES.map { File(source, it) }.filter { it.isFile && it.length() > 0 }
        if (logs.isEmpty()) return null

        val dir = File(context.cacheDir, LOG_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(dir, "camera-translator-kayit-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("bilgi.txt"))
            out.write(deviceInfo(context).toByteArray())
            out.closeEntry()
            for (file in logs) {
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    /** Uzaktan sorun çözerken ilk sorulan şeyler: cihaz, sürüm ve kayıt sırasındaki önemli ayarlar. */
    private fun deviceInfo(context: Context): String {
        val settings = SettingsManager(context)
        fun rect(values: FloatArray) = values.joinToString(", ") { String.format(Locale.US, "%.2f", it) }
        val opus = when (OfflineModelDownloader.state.value) {
            is OfflineModelDownloader.State.Installed ->
                if (OfflineModelDownloader.updateAvailable.value) "yüklü (eski model)" else "yüklü"
            else -> "yüklü değil"
        }
        return buildString {
            appendLine("Camera Translator v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Tarih: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Görünüm modu: ${settings.getDisplayMode().label}")
            appendLine(
                "Çeviri motoru: " + when (settings.getTranslationMode()) {
                    TranslationMode.OFFLINE -> "OPUS"
                    TranslationMode.FREE -> "Google Translate"
                    TranslationMode.CLOUD -> "Google Cloud"
                }
            )
            appendLine("Yön: ${if (settings.getTranslationDirection()) "TR -> EN" else "EN -> TR"}")
            appendLine("OPUS modeli: $opus")
            val flow = when (settings.getSubtitleFlow()) {
                SubtitleFlow.UP -> "Akıllı akış"
                SubtitleFlow.CENTER -> "Ortadan akış"
                SubtitleFlow.OFF -> "Kapalı"
            }
            appendLine("Geçmiş çeviriler: $flow (${settings.getFlowLines()} satır)")
            appendLine("Göz modunda büyük yazı: ${settings.getEyeBigText()}")
            appendLine("Sadece ortadaki altyazı: ${settings.getOcrCenterOnlyEnabled()}")
            appendLine("Beyaz altyazı filtresi: ${settings.getOcrTextMaskEnabled()}")
            appendLine("Zoom kaydırıcısı: ${settings.getZoomSliderEnabled()}")
            appendLine("OCR alanı Sinematik (dikey / yatay): ${rect(settings.getOcrRectDefaultPortrait())} / ${rect(settings.getOcrRectDefaultLandscape())}")
            appendLine("OCR alanı Yazının üstünde (dikey / yatay): ${rect(settings.getOcrRectScreenPortrait())} / ${rect(settings.getOcrRectScreenLandscape())}")
            appendLine("Ek OCR alanı (Yazının üstünde): ${settings.getScreenExtraAreaCount()}")
            appendLine("Ekran Çevirisi alan sayısı: ${settings.getPhoneAreaCount()}")
        }
    }
}
