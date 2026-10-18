package com.example.cameratranslator

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.cameratranslator.ui.theme.CameraTranslatorTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class Screen {
    CAMERA, SETTINGS, ABOUT
}

class MainActivity : ComponentActivity() {

    private val translationManager by lazy { TranslationManager() }
    private lateinit var speechManager: SpeechManager
    private var lastTranslatedSource: String? = null
    private val lastTranslationTimestamp = AtomicLong(0)
    private var translationJob: Job? = null
    private var currentCamera: Camera? = null
    private lateinit var settingsManager: SettingsManager

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        settingsManager = SettingsManager(this)
        speechManager = SpeechManager(this)
        speechManager.setEnabled(settingsManager.getTtsEnabled())
        speechManager.setSpeechRate(settingsManager.getSpeechRate())
        speechManager.setCloudSpeechRate(settingsManager.getCloudSpeechRate())
        speechManager.setPitch(settingsManager.getSpeechPitch())
        speechManager.setVoiceGender(settingsManager.getVoiceGender())
        speechManager.engineType = settingsManager.getTtsEngineType()
        speechManager.ttsApiKey = settingsManager.getTtsApiKey()
        speechManager.trCloudVoice = settingsManager.getTrCloudVoice()
        speechManager.enCloudVoice = settingsManager.getEnCloudVoice()

        val savedApiKey = settingsManager.getApiKey()
        translationManager.updateApiKey(savedApiKey)

        lifecycleScope.launch {
            translationManager.prepare()
        }

        setContent {
            var currentScreen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
            var currentAppTheme by remember { mutableStateOf(settingsManager.getTheme()) }
            var displayMode by remember { mutableStateOf(settingsManager.getDisplayMode()) }
            var showDisplayModeQuickSwitch by remember { mutableStateOf(settingsManager.getShowDisplayModeQuickSwitch()) }
            var subtitleFontSizeDefault by remember { mutableFloatStateOf(settingsManager.getSubtitleFontSizeDefault()) }
            var subtitleFontSizeScreen by remember { mutableFloatStateOf(settingsManager.getSubtitleFontSizeScreen()) }

            val activeSubtitleFontSize = if (displayMode == DisplayMode.DEFAULT) subtitleFontSizeDefault else subtitleFontSizeScreen

            var isTtsEnabled by remember { mutableStateOf(settingsManager.getTtsEnabled()) }
            var ttsEngineType by remember { mutableStateOf(settingsManager.getTtsEngineType()) }
            var ttsApiKey by remember { mutableStateOf(settingsManager.getTtsApiKey()) }
            var speechRate by remember { mutableFloatStateOf(settingsManager.getSpeechRate()) }
            var cloudSpeechRate by remember { mutableFloatStateOf(settingsManager.getCloudSpeechRate()) }
            var speechPitch by remember { mutableFloatStateOf(settingsManager.getSpeechPitch()) }
            var voiceGender by remember { mutableStateOf(settingsManager.getVoiceGender()) }
            var trCloudVoice by remember { mutableStateOf(settingsManager.getTrCloudVoice()) }
            var enCloudVoice by remember { mutableStateOf(settingsManager.getEnCloudVoice()) }
            var isTrToEn by rememberSaveable { mutableStateOf(settingsManager.getTranslationDirection()) }

            // Retain Zoom & Exposure state across screen navigation and orientation changes
            var zoomLevel by rememberSaveable { mutableFloatStateOf(1f) }
            var exposureIndex by rememberSaveable { mutableIntStateOf(0) }

            val isDark = when (currentAppTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            CameraTranslatorTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        Screen.CAMERA -> {
                            CameraContent(
                                zoomLevel = zoomLevel,
                                exposureIndex = exposureIndex,
                                onZoomChange = { zoomLevel = it },
                                onExposureChange = { exposureIndex = it },
                                isTrToEn = isTrToEn,
                                isTtsEnabled = isTtsEnabled,
                                subtitleFontSize = activeSubtitleFontSize,
                                displayMode = displayMode,
                                showDisplayModeQuickSwitch = showDisplayModeQuickSwitch,
                                onToggleDisplayMode = {
                                    displayMode = if (displayMode == DisplayMode.DEFAULT) DisplayMode.SCREEN_BETA else DisplayMode.DEFAULT
                                    settingsManager.saveDisplayMode(displayMode)
                                },
                                onToggleDirection = {
                                    isTrToEn = !isTrToEn
                                    settingsManager.saveTranslationDirection(isTrToEn)
                                },
                                onNavigate = { currentScreen = it }
                            )
                        }
                        Screen.SETTINGS -> {
                            SettingsScreen(
                                currentTheme = currentAppTheme,
                                currentApiKey = settingsManager.getApiKey(),
                                currentTtsApiKey = ttsApiKey,
                                subtitleFontSize = activeSubtitleFontSize,
                                currentDisplayMode = displayMode,
                                showDisplayModeQuickSwitch = showDisplayModeQuickSwitch,
                                isTtsEnabled = isTtsEnabled,
                                currentTtsEngineType = ttsEngineType,
                                speechRate = speechRate,
                                cloudSpeechRate = cloudSpeechRate,
                                speechPitch = speechPitch,
                                currentVoiceGender = voiceGender,
                                isTrToEn = isTrToEn,
                                currentTrCloudVoice = trCloudVoice,
                                currentEnCloudVoice = enCloudVoice,
                                onThemeChange = { theme ->
                                    currentAppTheme = theme
                                    settingsManager.saveTheme(theme)
                                },
                                onSubtitleFontSizeChange = { size ->
                                    if (displayMode == DisplayMode.DEFAULT) {
                                        subtitleFontSizeDefault = size
                                        settingsManager.saveSubtitleFontSizeDefault(size)
                                    } else {
                                        subtitleFontSizeScreen = size
                                        settingsManager.saveSubtitleFontSizeScreen(size)
                                    }
                                },
                                onDisplayModeChange = { mode ->
                                    displayMode = mode
                                    settingsManager.saveDisplayMode(mode)
                                },
                                onShowDisplayModeQuickSwitchChange = { show ->
                                    showDisplayModeQuickSwitch = show
                                    settingsManager.saveShowDisplayModeQuickSwitch(show)
                                },
                                onApiKeySave = { key ->
                                    settingsManager.saveApiKey(key)
                                    translationManager.updateApiKey(key)
                                    lifecycleScope.launch {
                                        translationManager.prepare()
                                    }
                                },
                                onTtsApiKeySave = { key ->
                                    ttsApiKey = key
                                    settingsManager.saveTtsApiKey(key)
                                    speechManager.ttsApiKey = key
                                },
                                onTtsEnabledChange = { enabled ->
                                    isTtsEnabled = enabled
                                    settingsManager.saveTtsEnabled(enabled)
                                    speechManager.setEnabled(enabled)
                                },
                                onTtsEngineTypeChange = { type ->
                                    ttsEngineType = type
                                    settingsManager.saveTtsEngineType(type)
                                    speechManager.engineType = type
                                },
                                onSpeechRateChange = { rate ->
                                    speechRate = rate
                                    settingsManager.saveSpeechRate(rate)
                                    speechManager.setSpeechRate(rate)
                                },
                                onCloudSpeechRateChange = { rate ->
                                    cloudSpeechRate = rate
                                    speechManager.setCloudSpeechRate(rate)
                                },
                                onSpeechPitchChange = { pitch ->
                                    speechPitch = pitch
                                    settingsManager.saveSpeechPitch(pitch)
                                    speechManager.setPitch(pitch)
                                },
                                onVoiceGenderChange = { gender ->
                                    voiceGender = gender
                                    settingsManager.saveVoiceGender(gender)
                                    speechManager.setVoiceGender(gender)

                                    val currentTrObj = CloudVoiceRegistry.getVoiceById(trCloudVoice)
                                    if (currentTrObj != null && currentTrObj.gender != gender) {
                                        val newTrVoice = CloudVoiceRegistry.turkishVoices.firstOrNull { it.gender == gender }?.id ?: "tr-TR-Chirp3-HD-Kore"
                                        trCloudVoice = newTrVoice
                                        settingsManager.saveTrCloudVoice(newTrVoice)
                                        speechManager.trCloudVoice = newTrVoice
                                    }

                                    val currentEnObj = CloudVoiceRegistry.getVoiceById(enCloudVoice)
                                    if (currentEnObj != null && currentEnObj.gender != gender) {
                                        val newEnVoice = CloudVoiceRegistry.englishVoices.firstOrNull { it.gender == gender }?.id ?: "en-US-Chirp3-HD-Kore"
                                        enCloudVoice = newEnVoice
                                        settingsManager.saveEnCloudVoice(newEnVoice)
                                        speechManager.enCloudVoice = newEnVoice
                                    }
                                },
                                onTrCloudVoiceSave = { voiceId ->
                                    trCloudVoice = voiceId
                                    settingsManager.saveTrCloudVoice(voiceId)
                                    speechManager.trCloudVoice = voiceId
                                },
                                onEnCloudVoiceSave = { voiceId ->
                                    enCloudVoice = voiceId
                                    settingsManager.saveEnCloudVoice(voiceId)
                                    speechManager.enCloudVoice = voiceId
                                },
                                onTestCustomSpeech = { customText ->
                                    speechManager.speakCustomTest(customText, isTrToEn)
                                },
                                onBack = { currentScreen = Screen.CAMERA }
                            )
                        }
                        Screen.ABOUT -> {
                            AboutScreen(
                                onBack = { currentScreen = Screen.CAMERA }
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    private fun CameraContent(
        zoomLevel: Float,
        exposureIndex: Int,
        onZoomChange: (Float) -> Unit,
        onExposureChange: (Int) -> Unit,
        isTrToEn: Boolean,
        isTtsEnabled: Boolean,
        subtitleFontSize: Float,
        displayMode: DisplayMode,
        showDisplayModeQuickSwitch: Boolean,
        onToggleDisplayMode: () -> Unit,
        onToggleDirection: () -> Unit,
        onNavigate: (Screen) -> Unit
    ) {
        val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

        var currentTranslation by rememberSaveable { mutableStateOf("") }
        var historyList by rememberSaveable { mutableStateOf(listOf<String>()) }
        var isPaused by rememberSaveable { mutableStateOf(true) }
        var isMuted by remember { mutableStateOf(!speechManager.isEnabled) }
        var isFirstFrameAfterStart by remember { mutableStateOf(true) }
        val isTrToEnRef = rememberUpdatedState(isTrToEn)
        var translationMode by rememberSaveable { mutableStateOf(settingsManager.getTranslationMode()) }

        val translationStatus by translationManager.status.collectAsStateWithLifecycle()
        val currentTranslationRef = rememberUpdatedState(currentTranslation)
        val historyListRef = rememberUpdatedState(historyList)

        val context = LocalContext.current
        DisposableEffect(isPaused) {
            val activity = context as? ComponentActivity
            if (!isPaused) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val stabilizer = remember {
            OcrStabilizer { confirmedText ->
                if (isSimilarSourceText(confirmedText, lastTranslatedSource)) {
                    Log.d(PIPELINE_TAG, "Skipping duplicate/similar OCR source before API call: '$confirmedText'")
                    return@OcrStabilizer
                }
                lastTranslatedSource = confirmedText
                val timestamp = System.currentTimeMillis()
                lastTranslationTimestamp.set(timestamp)

                translationJob?.cancel()
                translationJob = lifecycleScope.launch {
                    try {
                        if (timestamp < lastTranslationTimestamp.get()) return@launch

                        val translated = withContext(Dispatchers.IO) {
                            translationManager.translate(confirmedText)
                        }

                        if (timestamp < lastTranslationTimestamp.get()) return@launch

                        if (translated.isNotBlank() && !translated.equals(confirmedText, ignoreCase = true)) {
                            val currentNorm = normalizeForDeduplication(translated)
                            val prevNorm = normalizeForDeduplication(currentTranslationRef.value)
                            val lastHistoryNorm = historyListRef.value.lastOrNull()?.let { normalizeForDeduplication(it) }

                            if (currentNorm.isNotEmpty() && (currentNorm == prevNorm || currentNorm == lastHistoryNorm || isSimilarSourceText(translated, currentTranslationRef.value))) {
                                Log.d(PIPELINE_TAG, "Skipping duplicate screen translation: '$translated'")
                                return@launch
                            }

                            val prev = currentTranslationRef.value
                            if (prev.isNotBlank() && !prev.startsWith("Altyazı") && !prev.startsWith("Hazır")) {
                                val newHistory = historyListRef.value.toMutableList()
                                newHistory.add(prev)
                                if (newHistory.size > 100) newHistory.removeAt(0)
                                historyList = newHistory
                            }
                            currentTranslation = translated
                            speechManager.speak(translated, isTrToEnRef.value)
                        }
                    } catch (e: Exception) {
                        Log.e(PIPELINE_TAG, "Translation exception", e)
                    }
                }
            }
        }

        val analyzer = remember {
            TextRecognitionAnalyzer(
                onTextDetected = { ocrText ->
                    stabilizer.submit(ocrText)
                },
                onEmptyDetected = {
                    stabilizer.notifyEmpty()
                    lastTranslatedSource = null
                }
            )
        }

        LaunchedEffect(isPaused) {
            analyzer.setPaused(isPaused)
            isFirstFrameAfterStart = true
            if (isPaused) {
                stabilizer.reset()
                lastTranslatedSource = null
                speechManager.stop()
                speechManager.resetLastSpokenText()
            } else {
                speechManager.resumeFromStop()
            }
        }

        val onPreviewViewReady = remember(analyzer) {
            { view: PreviewView -> analyzer.attachPreviewView(view) }
        }

        var isExposureControlsVisible by rememberSaveable { mutableStateOf(false) }
        var exposureRange by remember { mutableStateOf(-4..4) }
        var isStealthMode by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(translationMode, isTrToEn) {
            translationManager.mode = translationMode
            translationManager.isTrToEn = isTrToEn
            settingsManager.saveTranslationMode(translationMode)
            settingsManager.saveTranslationDirection(isTrToEn)
        }

        if (cameraPermissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            isExposureControlsVisible = false
                        })
                    }
            ) {
                val isStealthModeVal = isStealthMode
                val cameraAlpha = if (isStealthModeVal && displayMode == DisplayMode.DEFAULT) 0f else 1f
                Box(modifier = Modifier.fillMaxSize().alpha(cameraAlpha)) {
                    CameraPreview(
                        analyzer = analyzer,
                        zoomLevel = zoomLevel,
                        exposureIndex = exposureIndex,
                        onCameraReady = { camera ->
                            currentCamera = camera
                            val state = camera.cameraInfo.exposureState
                            exposureRange = IntRange(
                                state.exposureCompensationRange.lower,
                                state.exposureCompensationRange.upper
                            )
                        },
                        onPreviewViewReady = onPreviewViewReady
                    )
                }

                val statusText = when (val s = translationStatus) {
                    is TranslationStatus.DownloadingModel -> ""
                    is TranslationStatus.Ready -> if (isPaused) "Çeviriye Hazır" else ""
                    is TranslationStatus.Error -> if (isPaused) s.message else ""
                    else -> ""
                }

                val isBusy by translationManager.isBusy.collectAsStateWithLifecycle()

                val displayResult = when {
                    statusText.isNotEmpty() -> statusText
                    isPaused -> ""
                    currentTranslation.isNotEmpty() -> currentTranslation
                    isBusy && translationMode != TranslationMode.OFFLINE -> "Çevriliyor..."
                    else -> "Altyazı bekleniyor..."
                }

                MainOverlay(
                    currentTranslation = displayResult,
                    history = historyList,
                    isPaused = isPaused,
                    zoomLevel = zoomLevel,
                    translationMode = translationMode,
                    isStealthMode = isStealthMode,
                    onToggleStealthMode = { isStealthMode = !isStealthMode },
                    onCycleTranslationMode = {
                        translationMode = when (translationMode) {
                            TranslationMode.CLOUD -> TranslationMode.FREE
                            TranslationMode.FREE -> TranslationMode.OFFLINE
                            TranslationMode.OFFLINE -> TranslationMode.CLOUD
                        }
                        translationManager.mode = translationMode
                    },
                    onTogglePause = {
                        isPaused = !isPaused
                        if (isPaused) {
                            translationJob?.cancel()
                            translationJob = null
                            currentTranslation = ""
                            historyList = emptyList()
                            lastTranslatedSource = null
                            stabilizer.reset()
                            speechManager.stop()
                            speechManager.resetLastSpokenText()
                        } else {
                            speechManager.resumeFromStop()
                        }
                    },
                    onZoomChange = onZoomChange,
                    onOcrAreaChange = { area, _, _ ->
                        analyzer.setOcrArea(area, 0f, 0f)
                    },
                    isExposureControlsVisible = isExposureControlsVisible,
                    onToggleExposureControls = {
                        isExposureControlsVisible = !isExposureControlsVisible
                    },
                    onExposureIncrement = {
                        if (exposureIndex < exposureRange.last) {
                            onExposureChange(exposureIndex + 1)
                        }
                    },
                    onExposureDecrement = {
                        if (exposureIndex > exposureRange.first) {
                            onExposureChange(exposureIndex - 1)
                        }
                    },
                    onFocusRequest = { x: Float, y: Float ->
                        val camera = currentCamera
                        if (camera != null) {
                            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                            val point = factory.createPoint(x, y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                .disableAutoCancel()
                                .build()
                            camera.cameraControl.startFocusAndMetering(action)
                        }
                    },
                    onNavigate = onNavigate,
                    isTrToEn = isTrToEn,
                    onToggleDirection = onToggleDirection,
                    isTtsEnabled = isTtsEnabled,
                    isMuted = isMuted,
                    onToggleMute = {
                        isMuted = !isMuted
                        speechManager.setEnabled(!isMuted)
                    },
                    subtitleFontSize = subtitleFontSize,
                    displayMode = displayMode,
                    showDisplayModeQuickSwitch = showDisplayModeQuickSwitch,
                    onToggleDisplayMode = onToggleDisplayMode,
                    initialOcrRectDefaultPortrait = remember { settingsManager.getOcrRectDefaultPortrait() },
                    initialOcrRectDefaultLandscape = remember { settingsManager.getOcrRectDefaultLandscape() },
                    initialOcrRectScreenPortrait = remember { settingsManager.getOcrRectScreenPortrait() },
                    initialOcrRectScreenLandscape = remember { settingsManager.getOcrRectScreenLandscape() },
                    onOcrRectSaveDefaultPortrait = { left, top, right, bottom ->
                        settingsManager.saveOcrRectDefaultPortrait(left, top, right, bottom)
                    },
                    onOcrRectSaveDefaultLandscape = { left, top, right, bottom ->
                        settingsManager.saveOcrRectDefaultLandscape(left, top, right, bottom)
                    },
                    onOcrRectSaveScreenPortrait = { left, top, right, bottom ->
                        settingsManager.saveOcrRectScreenPortrait(left, top, right, bottom)
                    },
                    onOcrRectSaveScreenLandscape = { left, top, right, bottom ->
                        settingsManager.saveOcrRectScreenLandscape(left, top, right, bottom)
                    }
                )
            }
        } else {
            LaunchedEffect(Unit) {
                cameraPermissionState.launchPermissionRequest()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun normalizeForDeduplication(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isSimilarSourceText(textA: String, textB: String?): Boolean {
        if (textB.isNullOrBlank()) return false
        val normA = normalizeForDeduplication(textA)
        val normB = normalizeForDeduplication(textB)
        if (normA == normB) return true
        if (normA.isEmpty() || normB.isEmpty()) return false

        val shorter = if (normA.length <= normB.length) normA else normB
        val longer = if (normA.length <= normB.length) normB else normA

        if (shorter.length >= 4 && longer.contains(shorter) && (shorter.length.toFloat() / longer.length.toFloat()) >= 0.70f) {
            return true
        }

        val distance = levenshteinDistance(normA, normB)
        val ratio = 1f - distance.toFloat() / kotlin.math.max(normA.length, normB.length)
        return ratio >= 0.72f
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[s2.length]
    }

    private fun hideSystemBars() {
        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.stop()
        speechManager.shutdown()
        translationManager.close()
    }

    companion object {
        private const val PIPELINE_TAG = "PIPELINE"
    }
}
