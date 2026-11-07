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
 * - Eski ve yeni modelin boyutları aynı, sürüm ayrı bir dosyada tutulur. Eski modeli olan kullanıcı
 *   güncellemeyi kendi başlatır; yeni dosyaların hepsi doğrulanana kadar eski model çalışmaya devam eder.
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
        "https://github.com/crosswindfighter/camera-translator/releases/download/offline-model-v2/"
    private const val VERSION_FILE = "model_version.txt"
    private const val CURRENT_VERSION = "2"
    private const val OLD_VERSION = "1"

    private val ENCODER = ModelFile("encoder_model.onnx", 135_438_293L, "e39d3994c41b8ab1ab5834af8d921444e9893fca7a70fdf71628ed68cc461409")
    private val DECODER = ModelFile("decoder_model_merged.onnx", 219_934_409L, "26be24f5830aa9df384d2bb326a35a4a90ded44ff122f19e8fc8031e9eb1aa9c")
    private val FILES = listOf(
        ModelFile("source_pieces.tsv", 966_111L, "463da3a6d9eef5762efe111de0f2e28222c1ec6f40dd0b128c4487d03e4c80f8"),
        ModelFile("vocab.json", 1_558_348L, "6a41bb921b88e5b0d7991cfdc7adbefde24b4ab00613389506e3c605cc092101"),
        ENCODER,
        DECODER,
    )
    val totalBytes: Long = FILES.sumOf { it.size }
    /** Güncellemede yalnızca bu iki dosya değişiyor. */
    val updateBytes: Long = ENCODER.size + DECODER.size

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var directory: File? = null
    /** Eski konum (Android/data/...): Android 10 ve öncesinde başka uygulamalar değiştirebiliyordu. */
    private var legacyDirectory: File? = null

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state

    private val _updateAvailable = MutableStateFlow(false)
    /** Eski model yüklü ve yenisi indirilebilir. */
    val updateAvailable: StateFlow<Boolean> = _updateAvailable

    private val _updateError = MutableStateFlow<String?>(null)
    /** Güncelleme başarısız olduysa nedeni; eski model bu sırada çalışmaya devam eder. */
    val updateError: StateFlow<String?> = _updateError

    /** Uygulamanın özel alanı: başka uygulamalar model dosyalarını okuyamaz/değiştiremez. */
    fun modelDirectory(context: Context): File = File(context.filesDir, OpusTranslationEngine.MODEL_DIR_NAME)

    fun init(context: Context) {
        directory = modelDirectory(context)
        legacyDirectory = context.getExternalFilesDir(OpusTranslationEngine.MODEL_DIR_NAME)
        if (job?.isActive == true) return
        when {
            isInstalled(directory) -> {
                _state.value = State.Installed
                legacyDirectory?.deleteRecursively()
                checkModelVersion()
            }
            isInstalled(legacyDirectory) -> migrateFromLegacy()
            else -> _state.value = State.NotInstalled
        }
    }

    /**
     * Sürüm dosyası yoksa model bu kontrolden önce kurulmuştur: hangisi olduğu özetinden anlaşılır ve
     * sonuç yazılır, böylece büyük dosyalar yalnızca bir kez okunur.
     */
    private fun checkModelVersion() {
        val dir = directory ?: return
        val marker = File(dir, VERSION_FILE)
        if (marker.isFile) {
            _updateAvailable.value = marker.readText().trim() != CURRENT_VERSION
            return
        }
        job = scope.launch {
            try {
                val current = FILES.all { sha256(File(dir, it.name)).equals(it.sha256, ignoreCase = true) }
                marker.writeText(if (current) CURRENT_VERSION else OLD_VERSION)
                _updateAvailable.value = !current
            } catch (e: Exception) {
                Log.e(TAG, "Model version check failed", e)
            }
        }
    }

    /** Tüm dosyalar doğru boyutta mı? */
    private fun isInstalled(dir: File? = directory): Boolean {
        if (dir == null) return false
        return FILES.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.size } }
    }

    /**
     * Eski sürümün paylaşılan depolamaya indirdiği modeli özel alana taşır; tekrar
     * indirmeye gerek kalmaz. Taşınan her dosya SHA-256 ile doğrulanır.
     */
    private fun migrateFromLegacy() {
        val dir = directory ?: return
        val legacy = legacyDirectory ?: return
        job = scope.launch {
            _state.value = State.Verifying
            try {
                dir.mkdirs()
                for (file in FILES) {
                    val part = File(dir, file.name + ".part")
                    File(legacy, file.name).copyTo(part, overwrite = true)
                    if (!sha256(part).equals(file.sha256, ignoreCase = true)) {
                        part.delete()
                        throw IllegalStateException("${file.name} doğrulanamadı")
                    }
                    if (!part.renameTo(File(dir, file.name))) throw IllegalStateException("${file.name} taşınamadı")
                }
                legacy.deleteRecursively()
                File(dir, VERSION_FILE).writeText(CURRENT_VERSION)
                _state.value = State.Installed
            } catch (e: Exception) {
                Log.e(TAG, "Legacy model migration failed", e)
                dir.listFiles()?.forEach { it.delete() }
                // Eski konumdaki model doğrulanamıyorsa her açılışta 355 MB yeniden kopyalanmasın
                legacy.deleteRecursively()
                _state.value = State.NotInstalled
            }
        }
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
        _updateError.value = null
        job = scope.launch {
            try {
                download(dir)
                _updateAvailable.value = false
                _state.value = State.Installed
            } catch (e: CancellationException) {
                _state.value = if (isInstalled()) State.Installed else State.NotInstalled
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                val message = e.message ?: "İndirme başarısız"
                if (isInstalled()) {
                    // Güncelleme başarısız: eski model hâlâ yerinde ve çalışıyor
                    _updateError.value = message
                    _state.value = State.Installed
                } else {
                    _state.value = State.Failed(message)
                }
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
        legacyDirectory?.deleteRecursively()
        _updateAvailable.value = false
        _updateError.value = null
        _state.value = State.NotInstalled
    }

    private suspend fun download(dir: File) {
        dir.mkdirs()
        var completedBytes = 0L
        val verified = ArrayList<Pair<File, File>>()
        for (file in FILES) {
            val target = File(dir, file.name)
            // Boyut yetmez: eski ve yeni model aynı boyutta
            if (target.isFile && target.length() == file.size && sha256(target).equals(file.sha256, ignoreCase = true)) {
                completedBytes += file.size
                continue
            }
            val part = File(dir, file.name + ".part")
            downloadFile(file, part, completedBytes)

            _state.value = State.Verifying
            if (!sha256(part).equals(file.sha256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("${file.name} bozuk indi, tekrar deneyin")
            }
            verified += part to target
            completedBytes += file.size
        }
        // Eski ve yeni kodlayıcı/çözücü birlikte çalışmaz: hepsi inmeden hiçbiri yerine geçmez
        for ((part, target) in verified) {
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("${target.name} kaydedilemedi")
        }
        File(dir, VERSION_FILE).writeText(CURRENT_VERSION)
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
