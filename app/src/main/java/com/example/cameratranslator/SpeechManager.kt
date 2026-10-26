package com.example.cameratranslator

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private data class CloudQueueItem(
    val text: String,
    val isTrToEn: Boolean,
    var audioFile: File? = null,
    var isSynthesizing: Boolean = false,
    var isSynthesized: Boolean = false,
    var synthesisFailed: Boolean = false
)

class SpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    @Volatile private var isLanguageSupported = false

    private val settingsManager = SettingsManager(context)

    var isEnabled: Boolean = false
        private set

    @Volatile private var isGlobalStopped = false

    var engineType: TtsEngineType = TtsEngineType.NATIVE
        set(value) {
            field = value
            if (value == TtsEngineType.CLOUD_TTS) {
                warmupCloudConnection()
                startKeepAliveHeartbeat()
            } else {
                stopKeepAliveHeartbeat()
            }
        }

    var ttsApiKey: String = ""
        set(value) {
            field = value
            if (value.isNotBlank() && engineType == TtsEngineType.CLOUD_TTS) {
                warmupCloudConnection()
                startKeepAliveHeartbeat()
            }
        }

    var trCloudVoice: String = "tr-TR-Chirp3-HD-Kore"
    var enCloudVoice: String = "en-US-Chirp3-HD-Kore"

    var speechRate: Float = 1.15f
        private set

    var cloudSpeechRate: Float = 1.0f
        private set

    var pitch: Float = 1.0f
        private set

    var voiceGender: VoiceGender = VoiceGender.FEMALE
        private set

    private var lastSpokenText: String? = null
    private var pendingFallbackText: String? = null
    private var isUsingNetworkVoice: Boolean = false
    private var pendingSpeechOnInit: Pair<String, Boolean>? = null

    private var currentLocale: Locale? = null
    private var currentAppliedGender: VoiceGender? = null
    private var currentAppliedRate: Float = -1f
    private var currentAppliedPitch: Float = -1f

    private var activeMediaPlayer: MediaPlayer? = null
    private var nextMediaPlayer: MediaPlayer? = null

    // Cancellable Coroutine Scope for Cloud TTS Jobs
    private val cloudJob = SupervisorJob()
    private val cloudScope = CoroutineScope(Dispatchers.IO + cloudJob)
    private var keepAliveJob: Job? = null

    // Cloud TTS FIFO Queue State
    private val cloudQueue = mutableListOf<CloudQueueItem>()
    @Volatile private var isCloudQueuePlaying = false
    private val cloudQueueLock = Any()
    private val activeSynthesisCount = AtomicInteger(0)

    // Single shared OkHttpClient instance across app with HTTP/2 enabled and persistent socket pool
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 10, TimeUnit.MINUTES))
        .build()

    private val recentSpokenTexts = object : LinkedHashMap<String, Long>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 20
        }
    }

    var activeVoiceName: String = ""
        private set

    init {
        initTts()
    }

    fun resumeFromStop() {
        isGlobalStopped = false
    }

    fun warmupCloudConnection() {
        val apiKey = ttsApiKey.trim()
        if (apiKey.isBlank() || engineType != TtsEngineType.CLOUD_TTS) return
        cloudScope.launch {
            try {
                val url = "https://texttospeech.googleapis.com/v1/voices?languageCode=tr-TR&key=$apiKey"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { /* Keep TLS socket active */ }
            } catch (_: Exception) {
                // Silently ignore warm-up errors
            }
        }
    }

    private fun startKeepAliveHeartbeat() {
        keepAliveJob?.cancel()
        if (engineType != TtsEngineType.CLOUD_TTS) return
        keepAliveJob = cloudScope.launch {
            while (engineType == TtsEngineType.CLOUD_TTS) {
                val apiKey = ttsApiKey.trim()
                if (apiKey.isNotBlank()) {
                    try {
                        val url = "https://texttospeech.googleapis.com/v1/voices?languageCode=tr-TR&key=$apiKey"
                        val request = Request.Builder().url(url).get().build()
                        httpClient.newCall(request).execute().use { /* Keep TLS socket permanently warm */ }
                    } catch (_: Exception) {}
                }
                delay(8_000)
            }
        }
    }

    private fun stopKeepAliveHeartbeat() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun initTts() {
        Log.d(TAG, "Initializing TextToSpeech with system default engine...")
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default TTS engine", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TextToSpeech initialized successfully with default engine")

            setupUtteranceListener()

            try {
                tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
                currentLocale = Locale.forLanguageTag("tr-TR")
            } catch (e: Exception) {
                Log.w(TAG, "setLanguage TR error", e)
            }

            voiceGender = settingsManager.getVoiceGender()
            speechRate = settingsManager.getSpeechRate()
            cloudSpeechRate = settingsManager.getCloudSpeechRate()
            pitch = settingsManager.getSpeechPitch()
            engineType = settingsManager.getTtsEngineType()
            ttsApiKey = settingsManager.getTtsApiKey()
            trCloudVoice = settingsManager.getTrCloudVoice()
            enCloudVoice = settingsManager.getEnCloudVoice()

            selectVoiceForGender(voiceGender, isFallbackLocal = false)

            isInitialized = true
            isLanguageSupported = true
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)
            currentAppliedRate = speechRate
            currentAppliedPitch = pitch

            if (engineType == TtsEngineType.CLOUD_TTS) {
                warmupCloudConnection()
                startKeepAliveHeartbeat()
            }

            pendingSpeechOnInit?.let { (text, isTrToEn) ->
                pendingSpeechOnInit = null
                Log.d(TAG, "Speaking pending initial sentence after TTS init: '$text'")
                speak(text, isTrToEn)
            }
        } else {
            Log.w(TAG, "TextToSpeech initialization failed with status: $status")
            isInitialized = false
        }
    }

    private fun setupUtteranceListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    pendingFallbackText = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handlePlaybackError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handlePlaybackError(utteranceId)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting UtteranceProgressListener", e)
        }
    }

    private fun handlePlaybackError(utteranceId: String?) {
        Log.w(TAG, "TTS Utterance onError ($utteranceId). isUsingNetworkVoice=$isUsingNetworkVoice")
        val fallbackText = pendingFallbackText
        if (!fallbackText.isNullOrBlank() && isUsingNetworkVoice) {
            pendingFallbackText = null
            Handler(Looper.getMainLooper()).post {
                try {
                    Log.d(TAG, "Network voice playback error. Falling back to LOCAL voice for gender $voiceGender")
                    selectVoiceForGender(voiceGender, isFallbackLocal = true)
                    tts?.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, "SpeechManager_Fallback_Utterance")
                } catch (e: Exception) {
                    Log.e(TAG, "Error speaking fallback text", e)
                }
            }
        }
    }

    private fun selectVoiceForGender(gender: VoiceGender, isFallbackLocal: Boolean = false) {
        try {
            val allVoices = tts?.voices ?: return
            val turkishVoices = allVoices.filter { voice ->
                val lang = voice.locale?.language?.lowercase().orEmpty()
                val name = voice.name.lowercase()
                lang == "tr" || name.contains("tr-tr") || name.contains("turkish")
            }

            if (turkishVoices.isEmpty()) return

            val selectedVoice = when (gender) {
                VoiceGender.FEMALE -> {
                    if (isFallbackLocal) {
                        turkishVoices.firstOrNull { it.name.lowercase().contains("tr-tr-x-efu-local") }
                            ?: turkishVoices.firstOrNull { it.name.lowercase().contains("efu") && !it.isNetworkConnectionRequired }
                            ?: turkishVoices.firstOrNull { !it.isNetworkConnectionRequired && isFemaleVoice(it.name) }
                            ?: turkishVoices.firstOrNull { !it.isNetworkConnectionRequired }
                            ?: turkishVoices.first()
                    } else {
                        turkishVoices.firstOrNull { it.name.lowercase().contains("tr-tr-x-efu-network") }
                            ?: turkishVoices.firstOrNull { it.name.lowercase().contains("efu") }
                            ?: turkishVoices.firstOrNull { isFemaleVoice(it.name) }
                            ?: turkishVoices.first()
                    }
                }
                VoiceGender.MALE -> {
                    if (isFallbackLocal) {
                        turkishVoices.firstOrNull { it.name.lowercase().contains("tr-tr-x-tmc-local") }
                            ?: turkishVoices.firstOrNull { it.name.lowercase().contains("tmc") && !it.isNetworkConnectionRequired }
                            ?: turkishVoices.firstOrNull { !it.isNetworkConnectionRequired && isMaleVoice(it.name) }
                            ?: turkishVoices.lastOrNull { !it.isNetworkConnectionRequired }
                            ?: turkishVoices.last()
                    } else {
                        turkishVoices.firstOrNull { it.name.lowercase().contains("tr-tr-x-tmc-network") }
                            ?: turkishVoices.firstOrNull { it.name.lowercase().contains("tmc") }
                            ?: turkishVoices.firstOrNull { isMaleVoice(it.name) }
                            ?: turkishVoices.last()
                    }
                }
            }

            tts?.voice = selectedVoice
            activeVoiceName = selectedVoice.name
            currentAppliedGender = gender
            isUsingNetworkVoice = selectedVoice.isNetworkConnectionRequired
            Log.d(TAG, "Selected Voice for gender [$gender] (isFallbackLocal=$isFallbackLocal): ${selectedVoice.name} (networkRequired=${selectedVoice.isNetworkConnectionRequired})")
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting voice for gender", e)
        }
    }

    private fun isFemaleVoice(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("female") || lower.contains("woman") || lower.contains("girl") || lower.contains("efu") || lower.contains("sfg") || lower.contains("cfs")
    }

    private fun isMaleVoice(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("male") || lower.contains("man") || lower.contains("boy") || lower.contains("tmc") || lower.contains("smg")
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isRecentlySpoken(normalizedText: String): Boolean {
        if (normalizedText.isEmpty()) return true
        val now = System.currentTimeMillis()
        synchronized(recentSpokenTexts) {
            val lastTime = recentSpokenTexts[normalizedText]
            if (lastTime != null && (now - lastTime) < 15_000) {
                return true
            }
            return false
        }
    }

    private fun recordSpokenText(normalizedText: String) {
        if (normalizedText.isNotEmpty()) {
            synchronized(recentSpokenTexts) {
                recentSpokenTexts[normalizedText] = System.currentTimeMillis()
            }
        }
    }

    private fun isSimilarString(s1: String, s2: String): Boolean {
        if (s1 == s2) return true
        if (s1.length > 5 && s2.length > 5) {
            if (s1.contains(s2) || s2.contains(s1)) return true
        }
        return false
    }

    private fun isSimilarToRecentOrQueued(text: String): Boolean {
        val normNew = normalizeText(text)
        if (normNew.isEmpty()) return true

        synchronized(cloudQueueLock) {
            for (item in cloudQueue) {
                val normQueued = normalizeText(item.text)
                if (normQueued == normNew || isSimilarString(normQueued, normNew)) {
                    return true
                }
            }
        }

        return isRecentlySpoken(normNew)
    }

    private fun splitSentenceIntoChunks(rawText: String): List<String> {
        val trimmed = rawText.trim()
        if (trimmed.length < SPLIT_MIN_SENTENCE_LENGTH) {
            return listOf(trimmed)
        }

        val regex = Regex("(?<=[,;:!?.])\\s+")
        val rawChunks = trimmed.split(regex).map { it.trim() }.filter { it.isNotEmpty() }

        if (rawChunks.size <= 1) {
            return listOf(trimmed)
        }

        val mergedChunks = mutableListOf<String>()
        var currentBuffer = ""

        for (chunk in rawChunks) {
            if (currentBuffer.isEmpty()) {
                currentBuffer = chunk
            } else {
                if (currentBuffer.length < MIN_CHUNK_LENGTH) {
                    currentBuffer = "$currentBuffer $chunk"
                } else {
                    mergedChunks.add(currentBuffer)
                    currentBuffer = chunk
                }
            }
        }

        if (currentBuffer.isNotEmpty()) {
            if (mergedChunks.isNotEmpty() && currentBuffer.length < MIN_CHUNK_LENGTH) {
                val lastIdx = mergedChunks.lastIndex
                mergedChunks[lastIdx] = "${mergedChunks[lastIdx]} $currentBuffer"
            } else {
                mergedChunks.add(currentBuffer)
            }
        }

        return if (mergedChunks.isEmpty()) listOf(trimmed) else mergedChunks
    }

    fun setVoiceGender(gender: VoiceGender) {
        voiceGender = gender
        settingsManager.saveVoiceGender(gender)
        if (isInitialized) {
            currentAppliedGender = null
            selectVoiceForGender(gender, isFallbackLocal = false)
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            isGlobalStopped = false
            if (engineType == TtsEngineType.CLOUD_TTS) {
                warmupCloudConnection()
                startKeepAliveHeartbeat()
            }
        } else {
            stop()
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate
        if (isInitialized) {
            try {
                currentAppliedRate = rate
                tts?.setSpeechRate(rate)
            } catch (e: Exception) {
                Log.e(TAG, "setSpeechRate error", e)
            }
        }
    }

    fun setCloudSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.8f, 1.6f)
        cloudSpeechRate = clampedRate
        settingsManager.saveCloudSpeechRate(clampedRate)
    }

    fun setPitch(pitchValue: Float) {
        pitch = pitchValue
        if (isInitialized) {
            try {
                currentAppliedPitch = pitchValue
                tts?.setPitch(pitchValue)
            } catch (e: Exception) {
                Log.e(TAG, "setPitch error", e)
            }
        }
    }

    fun speak(text: String, isTrToEn: Boolean) {
        if (!isEnabled || isGlobalStopped) return

        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        if (engineType == TtsEngineType.CLOUD_TTS) {
            speakCloudTts(cleanText, isTrToEn)
            return
        }

        // Native / Google Translate Engine
        val normalized = normalizeText(cleanText)
        if (isRecentlySpoken(normalized)) {
            Log.d(TAG, "Skipping duplicate OCR sentence: '$cleanText'")
            return
        }
        recordSpokenText(normalized)

        if (!isInitialized) {
            Log.d(TAG, "TTS not initialized yet. Saving pending initial speech: '$cleanText'")
            pendingSpeechOnInit = cleanText to isTrToEn
            return
        }

        val targetLocale = if (isTrToEn) Locale.US else Locale.forLanguageTag("tr-TR")

        try {
            if (currentLocale != targetLocale) {
                currentLocale = targetLocale
                tts?.setLanguage(targetLocale)
            }

            if (!isTrToEn && currentAppliedGender != voiceGender) {
                selectVoiceForGender(voiceGender, isFallbackLocal = false)
            }

            if (currentAppliedRate != speechRate) {
                currentAppliedRate = speechRate
                tts?.setSpeechRate(speechRate)
            }

            if (currentAppliedPitch != pitch) {
                currentAppliedPitch = pitch
                tts?.setPitch(pitch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying TTS configuration", e)
        }

        try {
            pendingFallbackText = cleanText
            lastSpokenText = cleanText
            val utteranceId = "SpeechManager_Utterance_${System.currentTimeMillis()}"
            val result = tts?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                handlePlaybackError(utteranceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "speak error", e)
            handlePlaybackError("SpeechManager_Utterance_Catch")
        }
    }

    private fun synthesizeCloudAudioToFile(text: String, isTrToEn: Boolean, outputFile: File): Boolean {
        if (!isEnabled || isGlobalStopped) return false
        val apiKey = ttsApiKey.trim()
        if (apiKey.isBlank()) return false

        try {
            val langCode = if (isTrToEn) "en-US" else "tr-TR"
            val rawVoiceName = if (isTrToEn) {
                if (enCloudVoice.isNotBlank()) enCloudVoice.trim()
                else if (voiceGender == VoiceGender.FEMALE) "en-US-Chirp3-HD-Kore" else "en-US-Chirp3-HD-Puck"
            } else {
                if (trCloudVoice.isNotBlank()) trCloudVoice.trim()
                else if (voiceGender == VoiceGender.FEMALE) "tr-TR-Chirp3-HD-Kore" else "tr-TR-Chirp3-HD-Puck"
            }

            val voiceObj = CloudVoiceRegistry.getVoiceById(rawVoiceName)
            val voiceName = if (voiceObj != null && voiceObj.gender != voiceGender) {
                val list = if (isTrToEn) CloudVoiceRegistry.englishVoices else CloudVoiceRegistry.turkishVoices
                list.firstOrNull { it.gender == voiceGender }?.id ?: rawVoiceName
            } else {
                rawVoiceName
            }

            val rateDouble: Double = cloudSpeechRate.toDouble().coerceIn(0.8, 1.6)

            val voiceObject = JSONObject().apply {
                put("languageCode", langCode)
                put("name", voiceName)
            }

            val jsonBody = JSONObject().apply {
                put("input", JSONObject().put("text", text))
                put("voice", voiceObject)
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("speakingRate", rateDouble)
                })
            }.toString()

            val url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (isGlobalStopped) return false
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val audioContent = JSONObject(body).optString("audioContent", "")
                    if (audioContent.isNotBlank()) {
                        val audioBytes = Base64.decode(audioContent, Base64.DEFAULT)
                        outputFile.writeBytes(audioBytes)
                        return true
                    }
                } else {
                    val errBody = response.body?.string().orEmpty()
                    Log.e("SpeechManager", "Cloud TTS Error ${response.code} ($voiceName): $errBody")

                    var errorMsg = "HTTP ${response.code}"
                    try {
                        val errJson = JSONObject(errBody).optJSONObject("error")
                        val apiMsg = errJson?.optString("message", "")
                        val status = errJson?.optString("status", "")

                        if (!apiMsg.isNullOrBlank()) {
                            errorMsg = when {
                                response.code == 403 || status == "PERMISSION_DENIED" -> {
                                    "Cloud TTS Hatası (403): Google Cloud Console'da 'Text-to-Speech API' etkinleştirilmemiş veya faturalandırma kapalı.\n\nDetay: $apiMsg"
                                }
                                response.code == 400 || status == "INVALID_ARGUMENT" -> {
                                    "Cloud TTS Hatası (400) [$voiceName]: $apiMsg"
                                }
                                else -> "Cloud TTS Hatası (${response.code}): $apiMsg"
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse error JSON", e)
                    }

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SpeechManager", "Cloud TTS Synthesis Exception", e)
        }
        return false
    }

    private fun speakCloudTts(text: String, isTrToEn: Boolean) {
        if (!isEnabled || isGlobalStopped) return

        val apiKey = ttsApiKey.trim()
        if (apiKey.isBlank()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Cloud Text-to-Speech API anahtarınız girilmedi.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Deduplication & Similarity Check
        if (isSimilarToRecentOrQueued(text)) {
            Log.d(TAG, "Skipping duplicate or similar sentence in Cloud TTS: '$text'")
            return
        }

        val normalized = normalizeText(text)
        recordSpokenText(normalized)

        val chunks = splitSentenceIntoChunks(text)

        synchronized(cloudQueueLock) {
            for (chunk in chunks) {
                val newItem = CloudQueueItem(chunk, isTrToEn)
                cloudQueue.add(newItem)
                Log.d(TAG, "Added chunk to Cloud TTS FIFO queue: '$chunk'. Total queue size: ${cloudQueue.size}")
            }

            triggerBackgroundSynthesis()

            if (!isCloudQueuePlaying) {
                isCloudQueuePlaying = true
                playHeadQueueItemIfReady()
            }
        }
    }

    private fun triggerBackgroundSynthesis() {
        if (isGlobalStopped) return
        synchronized(cloudQueueLock) {
            for (item in cloudQueue) {
                if (!item.isSynthesized && !item.isSynthesizing && !item.synthesisFailed) {
                    if (activeSynthesisCount.get() < 2) {
                        item.isSynthesizing = true
                        activeSynthesisCount.incrementAndGet()

                        cloudScope.launch {
                            val tempFile = File(context.cacheDir, "cloud_tts_queue_${System.currentTimeMillis()}_${item.text.hashCode()}.mp3")
                            val success = synthesizeCloudAudioToFile(item.text, item.isTrToEn, tempFile)

                            synchronized(cloudQueueLock) {
                                item.isSynthesizing = false
                                activeSynthesisCount.decrementAndGet()

                                if (success) {
                                    item.audioFile = tempFile
                                    item.isSynthesized = true
                                } else {
                                    item.synthesisFailed = true
                                }
                            }

                            if (!isGlobalStopped) {
                                playHeadQueueItemIfReady()
                                triggerBackgroundSynthesis()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun playHeadQueueItemIfReady() {
        if (isGlobalStopped) {
            synchronized(cloudQueueLock) { isCloudQueuePlaying = false }
            return
        }

        synchronized(cloudQueueLock) {
            if (cloudQueue.isEmpty()) {
                isCloudQueuePlaying = false
                return
            }

            val headItem = cloudQueue.firstOrNull() ?: return

            if (activeMediaPlayer != null) {
                prepareNextPlayerIfReady()
                return
            }

            if (headItem.isSynthesized && headItem.audioFile != null && headItem.audioFile!!.exists()) {
                val fileToPlay = headItem.audioFile!!
                Handler(Looper.getMainLooper()).post {
                    playCloudQueueFile(fileToPlay, headItem)
                }
            } else if (headItem.synthesisFailed) {
                Handler(Looper.getMainLooper()).post {
                    speakNativeFallback(headItem.text, headItem.isTrToEn) {
                        onQueueItemCompleted(headItem)
                    }
                }
            }
        }
    }

    private fun prepareNextPlayerIfReady() {
        if (isGlobalStopped) return
        synchronized(cloudQueueLock) {
            val nextItem = cloudQueue.getOrNull(1)
            if (nextItem != null && nextItem.isSynthesized && nextItem.audioFile != null && nextItem.audioFile!!.exists()) {
                if (nextMediaPlayer == null) {
                    try {
                        val nextPlayer = MediaPlayer().apply {
                            setDataSource(nextItem.audioFile!!.absolutePath)
                            prepare()
                        }
                        nextMediaPlayer = nextPlayer
                        activeMediaPlayer?.setNextMediaPlayer(nextPlayer)
                        Log.d(TAG, "Pre-prepared next MediaPlayer with setNextMediaPlayer for '${nextItem.text}'")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pre-preparing next MediaPlayer", e)
                        nextMediaPlayer?.release()
                        nextMediaPlayer = null
                    }
                }
            }
        }
    }

    private fun playCloudQueueFile(file: File, item: CloudQueueItem) {
        if (isGlobalStopped) {
            file.delete()
            synchronized(cloudQueueLock) { isCloudQueuePlaying = false }
            return
        }

        try {
            stopMediaPlayer()
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            activeMediaPlayer = player

            prepareNextPlayerIfReady()

            player.setOnCompletionListener {
                it.release()
                if (activeMediaPlayer == it) {
                    activeMediaPlayer = null
                }
                file.delete()
                onQueueItemCompleted(item)
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing queue file", e)
            file.delete()
            speakNativeFallback(item.text, item.isTrToEn) {
                onQueueItemCompleted(item)
            }
        }
    }

    private fun onQueueItemCompleted(item: CloudQueueItem) {
        if (isGlobalStopped) {
            synchronized(cloudQueueLock) {
                cloudQueue.clear()
                isCloudQueuePlaying = false
            }
            return
        }

        synchronized(cloudQueueLock) {
            cloudQueue.remove(item)
            item.audioFile?.delete()
        }

        if (nextMediaPlayer != null && !isGlobalStopped) {
            val playerToPlay = nextMediaPlayer
            nextMediaPlayer = null
            activeMediaPlayer = playerToPlay

            val nextItem: CloudQueueItem?
            synchronized(cloudQueueLock) { nextItem = cloudQueue.firstOrNull() }

            if (nextItem != null) {
                playerToPlay?.setOnCompletionListener {
                    it.release()
                    if (activeMediaPlayer == it) {
                        activeMediaPlayer = null
                    }
                    nextItem.audioFile?.delete()
                    onQueueItemCompleted(nextItem)
                }
                playerToPlay?.start()
                prepareNextPlayerIfReady()
            } else {
                playerToPlay?.release()
                activeMediaPlayer = null
                synchronized(cloudQueueLock) { isCloudQueuePlaying = false }
            }
        } else {
            playHeadQueueItemIfReady()
        }
    }

    private fun speakNativeFallback(text: String, isTrToEn: Boolean, onDone: () -> Unit) {
        if (isGlobalStopped) {
            onDone()
            return
        }
        val targetLocale = if (isTrToEn) Locale.US else Locale.forLanguageTag("tr-TR")
        try {
            if (currentLocale != targetLocale) {
                currentLocale = targetLocale
                tts?.setLanguage(targetLocale)
            }
            val utteranceId = "Queue_Fallback_${System.currentTimeMillis()}"

            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onDone()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) onDone()
                }
            }
            tts?.setOnUtteranceProgressListener(listener)

            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res == TextToSpeech.ERROR) {
                onDone()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native fallback error", e)
            onDone()
        }
    }

    private fun stopMediaPlayer() {
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            nextMediaPlayer?.let {
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer", e)
        } finally {
            activeMediaPlayer = null
            nextMediaPlayer = null
        }
    }

    fun resetLastSpokenText() {
        lastSpokenText = null
        pendingSpeechOnInit = null
        isGlobalStopped = true
        cloudJob.cancelChildren()
        stopMediaPlayer()
        synchronized(cloudQueueLock) {
            cloudQueue.forEach { it.audioFile?.delete() }
            cloudQueue.clear()
            isCloudQueuePlaying = false
        }
        synchronized(recentSpokenTexts) {
            recentSpokenTexts.clear()
        }
    }

    fun speakCustomTest(customText: String, isTrToEn: Boolean) {
        resetLastSpokenText()
        resumeFromStop()
        val defaultText = if (isTrToEn) "Hello, this is a voice test." else "Merhaba, sesli okuma testi yapılıyor."
        val textToSpeak = customText.trim().ifEmpty { defaultText }

        if (engineType == TtsEngineType.CLOUD_TTS) {
            speakCloudTts(textToSpeak, isTrToEn)
        } else {
            val oldEnabled = isEnabled
            isEnabled = true
            speak(textToSpeak, isTrToEn)
            isEnabled = oldEnabled
        }
    }

    fun stop() {
        pendingSpeechOnInit = null
        isGlobalStopped = true
        cloudJob.cancelChildren()
        stopMediaPlayer()
        synchronized(cloudQueueLock) {
            cloudQueue.forEach { it.audioFile?.delete() }
            cloudQueue.clear()
            isCloudQueuePlaying = false
        }
        synchronized(recentSpokenTexts) {
            recentSpokenTexts.clear()
        }
        lastSpokenText = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        }
    }

    fun shutdown() {
        pendingSpeechOnInit = null
        stopKeepAliveHeartbeat()
        isGlobalStopped = true
        cloudJob.cancelChildren()
        stopMediaPlayer()
        synchronized(cloudQueueLock) {
            cloudQueue.forEach { it.audioFile?.delete() }
            cloudQueue.clear()
            isCloudQueuePlaying = false
        }
        synchronized(recentSpokenTexts) {
            recentSpokenTexts.clear()
        }
        lastSpokenText = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown error", e)
        } finally {
            tts = null
            isInitialized = false
        }
    }

    companion object {
        private const val TAG = "SpeechManager"

        // Easily adjustable chunk splitting threshold constants
        private const val SPLIT_MIN_SENTENCE_LENGTH = 60
        private const val MIN_CHUNK_LENGTH = 25
    }
}
