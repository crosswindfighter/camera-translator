package com.example.cameratranslator

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases üzerinden yeni sürüm kontrolü.
 *
 * "latest" olarak işaretli sürüme bakılır; OPUS model sürümü (offline-model-v1)
 * latest olarak işaretlenmediği için karışmaz. Etiketler "v1.0.2" biçiminde olmalı.
 */
object UpdateChecker {

    /** [apkUrl]: sürüme eklenmiş APK (uygulama içi güncelleme için); yoksa null ve GitHub sayfası açılır. */
    data class ReleaseInfo(val version: String, val pageUrl: String, val notes: String, val apkUrl: String? = null)

    private const val TAG = "UpdateChecker"
    private const val LATEST_URL = "https://api.github.com/repos/crosswindfighter/camera-translator/releases/latest"
    const val RELEASES_PAGE = "https://github.com/crosswindfighter/camera-translator/releases"

    /** Açılışta en fazla bu sıklıkla kontrol edilir (GitHub'ı ve kullanıcıyı yormamak için). */
    private const val LAUNCH_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** En son yayınlanan sürüm; hata olursa exception fırlatır. */
    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CameraTranslator/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            ReleaseInfo(
                version = json.getString("tag_name").trim().removePrefix("v").removePrefix("V"),
                // Sunucudan gelen adres tarayıcıda açılacağı için sadece bu reponun sayfalarına izin ver
                pageUrl = json.optString("html_url", "").takeIf { it.startsWith("$RELEASES_PAGE/") } ?: RELEASES_PAGE,
                notes = json.optString("body", "").trim(),
                apkUrl = findApk(json)
            )
        }
    }

    /** Sürümdeki .apk dosyası; indirilecek adres yalnızca bu reponun sürüm dosyalarından biri olabilir. */
    private fun findApk(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url")
            if (url.startsWith("$RELEASES_PAGE/download/")) return url
        }
        return null
    }

    /**
     * Açılış kontrolü: yeni sürüm varsa ve bu sürüm için kullanıcıya daha önce
     * sorulmadıysa döner; aksi halde null. Her sürüm için tek bir kez gösterilir.
     */
    suspend fun checkOnLaunch(settings: SettingsManager): ReleaseInfo? {
        val now = System.currentTimeMillis()
        if (now - settings.getLastUpdateCheckAt() < LAUNCH_CHECK_INTERVAL_MS) return null
        settings.saveLastUpdateCheckAt(now)
        return try {
            val latest = fetchLatest()
            if (isNewer(latest.version, currentVersion) && latest.version != settings.getPromptedUpdateVersion()) latest else null
        } catch (e: Exception) {
            Log.w(TAG, "Launch update check failed: ${e.message}")
            null
        }
    }

    /** "1.0.10" > "1.0.9" gibi sayısal karşılaştırma. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
