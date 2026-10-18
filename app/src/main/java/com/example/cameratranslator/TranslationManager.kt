package com.example.cameratranslator

import android.text.Html
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class TranslationMode {
    CLOUD, FREE, OFFLINE
}

sealed class TranslationStatus {
    object Idle : TranslationStatus()
    object DownloadingModel : TranslationStatus()
    object Ready : TranslationStatus()
    data class Error(val message: String) : TranslationStatus()
}

interface ITranslationEngine {
    val status: StateFlow<TranslationStatus>
    val isBusy: StateFlow<Boolean>
    var mode: TranslationMode
    var isTrToEn: Boolean
    suspend fun translate(text: String): String
    suspend fun prepare()
    fun close()
    fun updateApiKey(key: String) {}
}

class GoogleCloudTranslationEngine(
    private var apiKey: String
) : ITranslationEngine {

    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.CLOUD
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    override suspend fun prepare() {
        _status.value = if (apiKey.isNotBlank()) {
            Log.d(TAG, "Google Cloud Translation API ready")
            TranslationStatus.Ready
        } else {
            Log.w(TAG, "Google Cloud API key missing")
            TranslationStatus.Error("API anahtarınızı girin")
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext ""
        _isBusy.value = true
        try {
            val url = URL("https://translation.googleapis.com/language/translate/v2?key=$apiKey")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
            }

            val payload = JSONObject().apply {
                put("q", text)
                put("source", if (isTrToEn) "tr" else "en")
                put("target", if (isTrToEn) "en" else "tr")
                put("format", "text")
            }.toString()

            connection.outputStream.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val body = (if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.readText().orEmpty()
            connection.disconnect()

            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP $code: $body")
                return@withContext ""
            }

            val translated = JSONObject(body)
                .getJSONObject("data")
                .getJSONArray("translations")
                .getJSONObject(0)
                .getString("translatedText")
                .let { decodeHtmlEntities(it) }
                .trim()

            translated
        } catch (e: Exception) {
            Log.e(TAG, "Google translate error", e)
            ""
        } finally {
            _isBusy.value = false
        }
    }

    override fun close() = Unit

    override fun updateApiKey(key: String) {
        this.apiKey = key
    }

    private fun decodeHtmlEntities(text: String): String {
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    companion object {
        private const val TAG = "TranslationGoogle"
    }
}

/**
 * FREE Translation Engine: Optimized GTX Endpoint + Mobile Web Fallback.
 * No API key required.
 */
class FreeTranslationEngine : ITranslationEngine {
    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.FREE
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    override suspend fun prepare() {
        _status.value = TranslationStatus.Ready
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        _isBusy.value = true
        
        var resultText = ""
        val cleanText = text.trim()
        val sl = if (isTrToEn) "tr" else "en"
        val tl = if (isTrToEn) "en" else "tr"

        // STRATEGY 1: Lightweight GTX JSON API (Fastest response & minimal payload size)
        try {
            val encoded = URLEncoder.encode(cleanText, "UTF-8").replace("+", "%20")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.isNotBlank() && body.startsWith("[")) {
                        val root = JSONArray(body)
                        val firstPart = root.optJSONArray(0)
                        if (firstPart != null) {
                            val sb = StringBuilder()
                            for (i in 0 until firstPart.length()) {
                                val row = firstPart.optJSONArray(i)
                                val part = row?.optString(0, "") ?: ""
                                if (part != "null") sb.append(part)
                            }
                            resultText = sb.toString().trim()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FreeEngine", "GTX Endpoint Exception: ${e.message}")
        }

        // STRATEGY 2: Mobile Web HTML (Fallback if GTX returns empty)
        if (resultText.isBlank()) {
            try {
                val encoded = URLEncoder.encode(cleanText, "UTF-8")
                val url = "https://translate.google.com/m?hl=tr&sl=$sl&tl=$tl&q=$encoded"
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string() ?: ""
                        val startMarker = "<div class=\"result-container\">"
                        val endMarker = "</div>"
                        val startIndex = html.indexOf(startMarker)
                        if (startIndex != -1) {
                            val contentStart = startIndex + startMarker.length
                            val endIndex = html.indexOf(endMarker, contentStart)
                            if (endIndex != -1) {
                                val rawResult = html.substring(contentStart, endIndex)
                                resultText = Html.fromHtml(rawResult, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FreeEngine", "Mobile Web Fallback Exception: ${e.message}")
            }
        }

        _isBusy.value = false
        resultText
    }

    override fun close() = Unit
}

class MLKitTranslationEngine : ITranslationEngine {
    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.OFFLINE
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    private var enTrTranslator: Translator? = null
    private var trEnTranslator: Translator? = null

    override suspend fun prepare() {
        prepareDirection(false)
        prepareDirection(true)
    }

    private suspend fun prepareDirection(trToEn: Boolean) {
        val source = if (trToEn) TranslateLanguage.TURKISH else TranslateLanguage.ENGLISH
        val target = if (trToEn) TranslateLanguage.ENGLISH else TranslateLanguage.TURKISH
        
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        
        val client = Translation.getClient(options)
        _status.value = TranslationStatus.DownloadingModel
        
        try {
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).await()
            if (trToEn) trEnTranslator = client else enTrTranslator = client
            _status.value = TranslationStatus.Ready
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit model download error", e)
            _status.value = TranslationStatus.Error("Model indirilemedi")
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        val engine = if (isTrToEn) trEnTranslator else enTrTranslator
        if (engine == null) return@withContext ""
        _isBusy.value = true
        try {
            engine.translate(text).await()
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit translate error", e)
            ""
        } finally {
            _isBusy.value = false
        }
    }

    override fun close() {
        enTrTranslator?.close()
        trEnTranslator?.close()
        enTrTranslator = null
        trEnTranslator = null
    }

    override fun updateApiKey(key: String) {}

    companion object {
        private const val TAG = "TranslationMLKit"
    }
}

class CompositeTranslationEngine(
    apiKey: String = BuildConfig.GOOGLE_TRANSLATE_API_KEY,
    private val google: GoogleCloudTranslationEngine = GoogleCloudTranslationEngine(apiKey),
    private val free: ITranslationEngine = FreeTranslationEngine(),
    private val offline: ITranslationEngine = MLKitTranslationEngine()
) : ITranslationEngine {

    override var isTrToEn: Boolean = false
        set(value) {
            field = value
            google.isTrToEn = value
            free.isTrToEn = value
            offline.isTrToEn = value
        }

    override var mode: TranslationMode = TranslationMode.CLOUD
        set(value) {
            field = value
            syncStatus()
        }

    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status

    private val cacheLock = Any()
    private val translationCache = object : LinkedHashMap<String, String>(30, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 30
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun cacheKey(source: String): String {
        return source.lowercase()
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun prepare() {
        scope.launch { google.prepare() }
        scope.launch { free.prepare() }
        scope.launch { offline.prepare() }

        offline.status.onEach { syncStatus() }.launchIn(scope)
        google.status.onEach { syncStatus() }.launchIn(scope)
        free.status.onEach { syncStatus() }.launchIn(scope)
        
        merge(offline.isBusy, google.isBusy, free.isBusy)
            .onEach { _isBusy.value = it }
            .launchIn(scope)
    }

    private fun syncStatus() {
        val currentEngine = when (mode) {
            TranslationMode.CLOUD -> google
            TranslationMode.FREE -> free
            TranslationMode.OFFLINE -> offline
        }
        val newStatus = currentEngine.status.value
        if (_status.value != newStatus) {
            _status.value = newStatus
        }
    }

    override suspend fun translate(text: String): String {
        if (text.isBlank()) return ""

        val key = cacheKey(text)
        val cached = synchronized(cacheLock) {
            if (key.isNotEmpty()) translationCache[key] else null
        }
        if (cached != null) return cached

        val result = when (mode) {
            TranslationMode.CLOUD -> {
                google.translate(text)
            }
            TranslationMode.FREE -> {
                free.translate(text)
            }
            TranslationMode.OFFLINE -> {
                offline.translate(text)
            }
        }

        if (result.isNotBlank()) {
            synchronized(cacheLock) {
                if (key.isNotEmpty()) translationCache[key] = result
            }
            return result
        }

        return ""
    }

    override fun close() {
        google.close()
        free.close()
        offline.close()
        scope.cancel()
    }

    override fun updateApiKey(key: String) {
        google.updateApiKey(key)
        scope.launch {
            google.prepare()
            syncStatus()
        }
    }

    companion object {
        private const val TAG = "TranslationManager"
    }
}

class TranslationManager(
    engine: ITranslationEngine = CompositeTranslationEngine()
) : ITranslationEngine by engine
