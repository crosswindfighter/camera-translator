package com.example.cameratranslator

import android.content.Context
import android.net.ConnectivityManager
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * OPUS modelini GitHub Releases'tan indirir.
 *
 * - Dosyalar tek tek iner; yarıda kalan indirme ".part" dosyasından kaldığı yerden devam eder.
 * - Her dosya SHA-256 ile doğrulanır; bozuk dosya silinir.
 * - Uygulama ekranından bağımsız çalışır (Ayarlar'dan çıkınca indirme durmaz).
 */
object OfflineModelDownloader {

    sealed class State {
        object NotInstalled : State()
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State() {
            val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
        }
        object Verifying : State()
        object Installed : State()
        data class Failed(val message: String) : State()
    }

    private data class ModelFile(val name: String, val size: Long, val sha256: String)

    private const val TAG = "ModelDownloader"
    private const val BASE_URL =
        "https://github.com/crosswindfighter/camera-translator/releases/download/offline-model-v1/"

    private val FILES = listOf(
        ModelFile("source_pieces.tsv", 966_111L, "463da3a6d9eef5762efe111de0f2e28222c1ec6f40dd0b128c4487d03e4c80f8"),
        ModelFile("vocab.json", 1_558_348L, "6a41bb921b88e5b0d7991cfdc7adbefde24b4ab00613389506e3c605cc092101"),
        ModelFile("encoder_model.onnx", 135_438_293L, "8c63bfc09b08e0b1156f9fcc430ef9cca5b28cc07e4b162a22d88f6da1d82a40"),
        ModelFile("decoder_model_merged.onnx", 219_934_409L, "28c173d33b0068e7a1b31bccaeba65f2b9aa1a964d122567b966cfe963d078db"),
    )
    val totalBytes: Long = FILES.sumOf { it.size }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var directory: File? = null

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state

    fun init(context: Context) {
        directory = context.getExternalFilesDir(OpusTranslationEngine.MODEL_DIR_NAME)
        if (job?.isActive != true) _state.value = if (isInstalled()) State.Installed else State.NotInstalled
    }

    /** Elle kopyalanmış dosyalar da kabul edilir: tüm dosyalar doğru boyutta mı? */
    private fun isInstalled(): Boolean {
        val dir = directory ?: return false
        return FILES.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.size } }
    }

    fun isOnMeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    fun start() {
        if (job?.isActive == true) return
        val dir = directory ?: run {
            _state.value = State.Failed("Depolama alanına erişilemiyor")
            return
        }
        job = scope.launch {
            try {
                download(dir)
                _state.value = State.Installed
            } catch (e: CancellationException) {
                _state.value = if (isInstalled()) State.Installed else State.NotInstalled
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = State.Failed(e.message ?: "İndirme başarısız")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Model dosyalarını ve yarım kalan indirmeleri siler. Önce motor kapatılmalı. */
    fun delete() {
        job?.cancel()
        directory?.listFiles()?.forEach { it.delete() }
        _state.value = State.NotInstalled
    }

    private suspend fun download(dir: File) {
        dir.mkdirs()
        var completedBytes = 0L
        for (file in FILES) {
            val target = File(dir, file.name)
            if (target.isFile && target.length() == file.size) {
                completedBytes += file.size
                continue
            }
            target.delete()
            val part = File(dir, file.name + ".part")
            downloadFile(file, part, completedBytes)

            _state.value = State.Verifying
            if (!sha256(part).equals(file.sha256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("${file.name} bozuk indi, tekrar deneyin")
            }
            if (!part.renameTo(target)) throw IllegalStateException("${file.name} kaydedilemedi")
            completedBytes += file.size
        }
    }

    private suspend fun downloadFile(file: ModelFile, part: File, completedBefore: Long) {
        var existing = if (part.isFile) part.length() else 0L
        if (existing > file.size) {
            part.delete()
            existing = 0L
        }
        if (existing == file.size) return

        val request = Request.Builder()
            .url(BASE_URL + file.name)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    if (response.code == 404) "Model sunucuda bulunamadı (404)" else "Sunucu hatası: ${response.code}"
                )
            }
            // 206: kaldığı yerden devam; 200: sunucu baştan gönderiyor
            val append = response.code == 206 && existing > 0
            if (!append) existing = 0L
            val body = response.body ?: throw IllegalStateException("Boş cevap")

            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = existing
                    var lastReported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= 512 * 1024) {
                            lastReported = written
                            _state.value = State.Downloading(completedBefore + written, totalBytes)
                        }
                    }
                }
            }
        }
        if (part.length() != file.size) throw IllegalStateException("${file.name} eksik indi, tekrar deneyin")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
