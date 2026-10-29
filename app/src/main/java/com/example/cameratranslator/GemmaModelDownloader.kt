package com.example.cameratranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Gelişmiş OCR düzeltme için isteğe bağlı Gemma 4 E2B modelini (LiteRT-LM, Apache 2.0) indirir.
 *
 * Önce projenin GitHub Releases'ı denenir; GitHub tek dosyada 2 GB sınırı koyduğu için model
 * orada iki parça halindedir ve parçalar tek dosyada uç uca eklenir. GitHub'a ulaşılamazsa
 * aynı dosya Hugging Face'teki sabit sürümden (commit) kaldığı byte'tan devam ederek iner.
 * Sonunda dosyanın tamamı SHA-256 ile doğrulanır. Durumlar [OfflineModelDownloader] ile
 * aynıdır, ayarlar ekranı ikisini aynı şekilde gösterir.
 */
object GemmaModelDownloader {

    /** Model dosyasının [start] byte'ından başlayan [size] byte'lık kısmı [url]'de. */
    private data class Segment(val url: String, val start: Long, val size: Long)
    private data class Source(val name: String, val segments: List<Segment>)

    private const val TAG = "GemmaDownloader"
    const val MODEL_DIR_NAME = "gemma_fixer"
    const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val MODEL_SIZE = 2_588_147_712L
    private const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private const val HALF_SIZE = MODEL_SIZE / 2

    private const val GITHUB_BASE_URL =
        "https://github.com/crosswindfighter/camera-translator/releases/download/gemma-model-v1/"
    private const val HUGGING_FACE_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "1caa527c1e88b55f046fcd8d2b4265606680663d/$MODEL_FILE_NAME"

    private val SOURCES = listOf(
        Source(
            "GitHub",
            listOf(
                Segment("$GITHUB_BASE_URL$MODEL_FILE_NAME.001", 0L, HALF_SIZE),
                Segment("$GITHUB_BASE_URL$MODEL_FILE_NAME.002", HALF_SIZE, MODEL_SIZE - HALF_SIZE)
            )
        ),
        Source("Hugging Face", listOf(Segment(HUGGING_FACE_URL, 0L, MODEL_SIZE)))
    )

    /** İndirme + doğrulama sırasında model dosyası ve biraz pay için gereken boş alan. */
    private const val REQUIRED_FREE_BYTES = MODEL_SIZE + 300_000_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var directory: File? = null

    private val _state = MutableStateFlow<OfflineModelDownloader.State>(OfflineModelDownloader.State.NotInstalled)
    val state: StateFlow<OfflineModelDownloader.State> = _state

    fun init(context: Context) {
        directory = File(context.filesDir, MODEL_DIR_NAME)
        if (job?.isActive == true) return
        _state.value = if (isInstalled()) OfflineModelDownloader.State.Installed else OfflineModelDownloader.State.NotInstalled
    }

    fun modelFile(): File? = directory?.let { File(it, MODEL_FILE_NAME) }

    fun isInstalled(): Boolean = modelFile()?.let { it.isFile && it.length() == MODEL_SIZE } == true

    fun start() {
        if (job?.isActive == true) return
        val dir = directory ?: run {
            _state.value = OfflineModelDownloader.State.Failed("Depolama alanına erişilemiyor")
            return
        }
        job = scope.launch {
            try {
                download(dir)
                _state.value = OfflineModelDownloader.State.Installed
            } catch (e: CancellationException) {
                _state.value = if (isInstalled()) OfflineModelDownloader.State.Installed else OfflineModelDownloader.State.NotInstalled
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = OfflineModelDownloader.State.Failed(e.message ?: "İndirme başarısız")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Modeli ve yarım kalan indirmeyi siler. Önce [GemmaOcrFixer.release] çağrılmalı. */
    fun delete() {
        job?.cancel()
        directory?.listFiles()?.forEach { it.deleteRecursively() }
        _state.value = OfflineModelDownloader.State.NotInstalled
    }

    private suspend fun download(dir: File) {
        dir.mkdirs()
        val target = File(dir, MODEL_FILE_NAME)
        if (target.isFile && target.length() == MODEL_SIZE) return
        target.delete()

        val part = File(dir, "$MODEL_FILE_NAME.part")
        val alreadyDownloaded = if (part.isFile) part.length() else 0L
        if (dir.usableSpace + alreadyDownloaded < REQUIRED_FREE_BYTES) {
            val neededGb = (REQUIRED_FREE_BYTES - alreadyDownloaded) / 1_000_000_000.0
            throw IllegalStateException("Telefonda yeterli boş alan yok (~%.1f GB gerekli)".format(neededGb))
        }

        if (part.isFile && part.length() > MODEL_SIZE) part.delete()

        // Kaynaklar aynı dosyanın aynı byte'larını verir: biri yarıda kesilirse diğeri kaldığı yerden devam eder
        var lastError: Exception? = null
        for (source in SOURCES) {
            try {
                downloadFrom(source, part)
                Log.i(TAG, "Download complete via ${source.name}")
                lastError = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${source.name} failed, trying next source", e)
                lastError = e
            }
        }
        lastError?.let { throw it }

        _state.value = OfflineModelDownloader.State.Verifying
        if (!sha256(part).equals(MODEL_SHA256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException("Model bozuk indi, tekrar deneyin")
        }
        if (!part.renameTo(target)) throw IllegalStateException("Model kaydedilemedi")
    }

    private suspend fun downloadFrom(source: Source, part: File) {
        for ((index, segment) in source.segments.withIndex()) {
            val existing = if (part.isFile) part.length() else 0L
            val segmentEnd = segment.start + segment.size
            if (existing >= segmentEnd) continue
            Log.i(TAG, "${source.name}: part ${index + 1}/${source.segments.size} from byte $existing")
            downloadSegment(segment, part, existing - segment.start)
            if (part.length() != segmentEnd) {
                throw IllegalStateException("Model eksik indi (${source.name}), tekrar deneyin")
            }
        }
    }

    /** [segment]'in [offset]'inden sonrasını [part] dosyasının sonuna ekler. */
    private suspend fun downloadSegment(segment: Segment, part: File, offset: Long) {
        var skip = offset
        val request = Request.Builder()
            .url(segment.url)
            .apply { if (skip > 0) header("Range", "bytes=$skip-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    if (response.code == 404) "Model sunucuda bulunamadı (404)" else "Sunucu hatası: ${response.code}"
                )
            }
            // 206: kaldığı yerden devam; 200: sunucu bu parçayı baştan gönderiyor, parçanın başına geri dön
            if (response.code != 206 && skip > 0) {
                RandomAccessFile(part, "rw").use { it.setLength(segment.start) }
                skip = 0L
            }
            val body = response.body ?: throw IllegalStateException("Boş cevap")

            FileOutputStream(part, true).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = segment.start + skip
                    var lastReported = 0L
                    while (written < segment.start + segment.size) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= 2 * 1024 * 1024) {
                            lastReported = written
                            _state.value = OfflineModelDownloader.State.Downloading(written, MODEL_SIZE)
                        }
                    }
                }
            }
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
