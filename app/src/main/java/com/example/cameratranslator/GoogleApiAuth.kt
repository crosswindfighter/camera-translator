package com.example.cameratranslator

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import okhttp3.Request
import java.security.MessageDigest

/**
 * Google Cloud API anahtarını URL yerine header ile gönderir.
 * Anahtar URL'de olursa log'lara, proxy'lere ve hata mesajlarına sızabilir.
 *
 * X-Android-Package / X-Android-Cert header'ları sayesinde Google Cloud
 * konsolunda anahtar "Android uygulamaları" ile kısıtlanabilir; böylece anahtar
 * çalınsa bile başka bir uygulamadan veya bilgisayardan kullanılamaz.
 */
object GoogleApiAuth {

    private const val TAG = "GoogleApiAuth"

    @Volatile private var packageName: String = ""
    @Volatile private var certSha1: String = ""

    fun init(context: Context) {
        packageName = context.packageName
        certSha1 = try {
            readSigningCertSha1(context)
        } catch (e: Exception) {
            Log.w(TAG, "Signing certificate could not be read", e)
            ""
        }
    }

    fun Request.Builder.googleApiKey(apiKey: String): Request.Builder {
        header("x-goog-api-key", apiKey)
        if (packageName.isNotEmpty()) header("X-Android-Package", packageName)
        if (certSha1.isNotEmpty()) header("X-Android-Cert", certSha1)
        return this
    }

    @Suppress("DEPRECATION")
    private fun readSigningCertSha1(context: Context): String {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull() ?: return ""
        val digest = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }
}
