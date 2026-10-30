package com.example.cameratranslator

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OCR teşhis kaydı. Açıkken birkaç karede bir şunları kaydeder:
 *  - fNNN_a_kamera.jpg : OCR alanının kameradan gelen hali
 *  - fNNN_b_ocr.jpg    : filtre sonrası ML Kit'e giden hali (filtre kapalıysa/devreye girmediyse yok)
 *  - ocr_log.txt       : her kaydedilen karede ML Kit'in okuduğu ve ayıklanmış metin
 *  - ceviri_log.txt    : her altyazı için onaylanan OCR metni, OPUS'a giden metin (kelime
 *                        düzeltme + argo kuralları sonrası) ve ekrana gelen çeviri
 *
 * Konum: Android/data/com.example.cameratranslator/files/ocr_debug
 * (Telefonu USB ile bilgisayara bağlayıp bu klasörden alınabilir.)
 */
object OcrDebugDumper {

    private const val TAG = "OCR_DEBUG"
    private const val MIN_INTERVAL_MS = 700L
    /** Toplu test için: ~600 kare ≈ 7 dakika oyun, ~180 MB (eskisi 40 kare ≈ 30 sn). */
    private const val MAX_SAVED_FRAMES = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val savedFrameIds = ArrayDeque<Long>()
    private val lock = Any()

    @Volatile private var directory: File? = null
    @Volatile private var lastSavedAt = 0L
    @Volatile private var lastSavedFrameId = -1L

    fun init(context: Context) {
        directory = context.getExternalFilesDir("ocr_debug")
    }

    /** Debug kaydı açıldığında eski kayıtları temizler. */
    fun clear() {
        val dir = directory ?: return
        scope.launch {
            dir.listFiles()?.forEach { it.delete() }
            synchronized(lock) { savedFrameIds.clear() }
        }
    }

    /**
     * Bu kare kaydedilecekse bitmap'lerin kopyasını alır (orijinaller hemen geri
     * dönüştürülebilir) ve dosyaya yazmayı arka planda yapar.
     */
    fun maybeSaveFrames(frameId: Long, camera: Bitmap, processed: Bitmap?) {
        if (!OcrTuning.debugDumpEnabled) return
        val dir = directory ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSavedAt < MIN_INTERVAL_MS) return
        lastSavedAt = now
        lastSavedFrameId = frameId

        val cameraCopy = camera.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val processedCopy = processed?.copy(Bitmap.Config.ARGB_8888, false)

        scope.launch {
            try {
                writeJpeg(File(dir, "f${frameId}_a_kamera.jpg"), cameraCopy)
                processedCopy?.let { writeJpeg(File(dir, "f${frameId}_b_ocr.jpg"), it) }
                trimOldFrames(dir, frameId)
            } catch (e: Exception) {
                Log.e(TAG, "Debug frame write failed", e)
            } finally {
                cameraCopy.recycle()
                processedCopy?.recycle()
            }
        }
    }

    /** Görüntüsü kaydedilen karenin OCR sonucunu log dosyasına ekler. */
    /** [source]: hangi görüntünün seçildiği ve güvenleri, ör. "filtre 0.81 | orijinal 0.62 → filtre" */
    fun logResult(frameId: Long, rawText: String, extractedText: String, confidence: Float, source: String) {
        if (!OcrTuning.debugDumpEnabled || frameId != lastSavedFrameId) return
        val dir = directory ?: return
        val line = buildString {
            append(timeFormat.format(Date()))
            append("  f").append(frameId)
            append("  [").append(source).append("]")
            append("  güven=").append(String.format(Locale.US, "%.2f", confidence))
            append("\n  ML Kit : ").append(rawText.replace('\n', '|'))
            append("\n  Seçilen: ").append(extractedText.replace('\n', '|'))
            append("\n\n")
        }
        scope.launch {
            try {
                File(dir, "ocr_log.txt").appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "Debug log write failed", e)
            }
        }
    }

    /** Çeviri hattının bir adımını ceviri_log.txt'ye ekler (ör. "Onaylanan", "OPUS'a giden", "Çeviri"). */
    fun logPipeline(label: String, text: String, endOfEntry: Boolean = false) {
        if (!OcrTuning.debugDumpEnabled) return
        val dir = directory ?: return
        val line = "${timeFormat.format(Date())}  $label: ${text.replace('\n', '|')}\n" + if (endOfEntry) "\n" else ""
        scope.launch {
            try {
                File(dir, "ceviri_log.txt").appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline log write failed", e)
            }
        }
    }

    private fun writeJpeg(file: File, bitmap: Bitmap) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    private fun trimOldFrames(dir: File, newFrameId: Long) {
        val toDelete = synchronized(lock) {
            savedFrameIds.addLast(newFrameId)
            val removed = mutableListOf<Long>()
            while (savedFrameIds.size > MAX_SAVED_FRAMES) removed.add(savedFrameIds.removeFirst())
            removed
        }
        for (id in toDelete) {
            File(dir, "f${id}_a_kamera.jpg").delete()
            File(dir, "f${id}_b_ocr.jpg").delete()
        }
    }
}
