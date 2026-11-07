package com.example.cameratranslator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uygulama içinden güncelleme: GitHub'daki yeni sürümün APK'sı indirilir ve Android'in kurulum penceresi açılır.
 * Android kurulumu kullanıcı onaylamadan yapmaz ve kurulum sırasında uygulamayı kapatır; ilk seferde
 * "bilinmeyen uygulamaları yükleme" izni istenir. Bir şey ters giderse GitHub sayfası yedek olarak kalır.
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private const val APK_NAME = "update.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cleanedThisProcess = false

    private fun apkFile(context: Context) = File(File(context.cacheDir, "updates").apply { mkdirs() }, APK_NAME)

    /** İndirilen kurulum dosyasını siler. */
    fun deleteDownloaded(context: Context) {
        File(context.cacheDir, "updates").deleteRecursively()
    }

    /**
     * Uygulama her başladığında bir kez çalışır: kurulum bitince Android uygulamayı yeniden başlattığı için
     * kalan APK burada silinir. Süreç yeni başladığından o anda açık bir güncelleme penceresi olamaz.
     */
    fun cleanUpOnStart(context: Context) {
        if (cleanedThisProcess) return
        cleanedThisProcess = true
        deleteDownloaded(context)
    }

    /**
     * APK'yı indirir; [onProgress] 0..1 (boyut bilinmiyorsa -1). İptal edilirse yarım dosya silinir.
     * Eksik ya da bozuk inen dosya kurulum penceresine hiç gitmez; yoksa Android "paket ayrıştırma" hatası verir.
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val target = apkFile(context)
        val partial = File(target.parentFile, "$APK_NAME.part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            val request = Request.Builder().url(url).header("User-Agent", "CameraTranslator/${UpdateChecker.currentVersion}").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Boş yanıt")
                val total = body.contentLength()
                var done = 0L
                var lastReported = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != lastReported) {
                                lastReported = percent
                                withContext(Dispatchers.Main) { onProgress(if (total > 0) percent / 100f else -1f) }
                            }
                        }
                    }
                }
            }
            if (expectedSize > 0 && partial.length() != expectedSize) {
                throw IllegalStateException("Eksik indi: ${partial.length()} / $expectedSize bayt")
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !sha256.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalStateException("Dosya doğrulanamadı")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /** Android 8+: kullanıcı bu uygulamaya "bilinmeyen uygulamaları yükleme" izni verdi mi? */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** İzin ekranını açar (yalnızca bu uygulama için). */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open install permission screen", e)
        }
    }

    /** Android'in kurulum penceresini açar; kurulumu kullanıcı onaylar. */
    fun install(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Cannot start installer", e)
        false
    }

    fun openPage(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open release page", e)
        }
    }
}

private sealed class UpdateStep {
    data object Ask : UpdateStep()
    data class Downloading(val progress: Float) : UpdateStep()
    data class Ready(val apk: File) : UpdateStep()
    data class Failed(val message: String) : UpdateStep()
}

/**
 * Yeni sürüm penceresi: "Şimdi güncelle" APK'yı uygulama içinde indirir, sonra kurulum penceresini açar.
 * APK bulunamazsa ya da indirme başarısızsa GitHub sayfası açılabilir. [onDismiss] pencere kapanınca çağrılır.
 */
@Composable
fun UpdateDialog(release: UpdateChecker.ReleaseInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<UpdateStep>(UpdateStep.Ask) }
    var job by remember { mutableStateOf<Job?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun startDownload() {
        val url = release.apkUrl ?: return
        step = UpdateStep.Downloading(0f)
        job = scope.launch {
            step = try {
                UpdateStep.Ready(
                    UpdateInstaller.download(context, url, release.apkSize, release.apkSha256) { step = UpdateStep.Downloading(it) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("UpdateDialog", "Download failed: ${e.message}")
                UpdateStep.Failed("İndirme başarısız oldu. İnternet bağlantınızı kontrol edip tekrar deneyin.")
            }
        }
    }

    fun install(apk: File) {
        if (!UpdateInstaller.canInstall(context)) {
            needsPermission = true
            UpdateInstaller.openInstallPermission(context)
            return
        }
        needsPermission = false
        if (!UpdateInstaller.install(context, apk)) {
            step = UpdateStep.Failed("Kurulum başlatılamadı. Yeni sürümü GitHub'dan indirebilirsiniz.")
        }
    }

    // Kurmadan kapatılırsa indirilen dosya telefonda kalmasın
    fun close() {
        UpdateInstaller.deleteDownloaded(context)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (step !is UpdateStep.Downloading) close() },
        title = { Text("🎉 Yeni sürüm mevcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = step) {
                    UpdateStep.Ask -> Text(
                        "Camera Translator v${release.version} yayınlandı (yüklü sürüm: v${UpdateChecker.currentVersion}). " +
                            if (release.apkUrl != null) "Güncelleme uygulama içinde indirilir; kurulum sırasında uygulama kısa bir süre kapanır."
                            else "Yeni sürümü GitHub sayfasından indirebilirsiniz."
                    )
                    is UpdateStep.Downloading -> {
                        Text(if (s.progress >= 0f) "İndiriliyor… %${(s.progress * 100).toInt()}" else "İndiriliyor…")
                        if (s.progress >= 0f) {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateStep.Ready -> Text(
                        if (needsPermission) {
                            "Açılan ekranda \"Bu kaynaktan izin ver\" seçeneğini açın, sonra geri dönüp \"Kur\"a basın. Bu izin yalnızca bir kez istenir."
                        } else {
                            "İndirme tamamlandı. \"Kur\"a basınca Android'in kurulum penceresi açılır; orada \"Güncelle\"ye basın."
                        }
                    )
                    is UpdateStep.Failed -> Text(s.message)
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                UpdateStep.Ask -> TextButton(onClick = {
                    if (release.apkUrl != null) startDownload() else {
                        UpdateInstaller.openPage(context, release.pageUrl)
                        onDismiss()
                    }
                }) { Text(if (release.apkUrl != null) "Şimdi güncelle" else "GitHub'da aç") }
                is UpdateStep.Downloading -> TextButton(onClick = {
                    job?.cancel()
                    step = UpdateStep.Ask
                }) { Text("İptal") }
                is UpdateStep.Ready -> TextButton(onClick = { install(s.apk) }) { Text("Kur") }
                is UpdateStep.Failed -> TextButton(onClick = {
                    UpdateInstaller.openPage(context, release.pageUrl)
                    close()
                }) { Text("GitHub'da aç") }
            }
        },
        dismissButton = {
            if (step !is UpdateStep.Downloading) {
                TextButton(onClick = { close() }) { Text("Daha sonra") }
            }
        }
    )
}
