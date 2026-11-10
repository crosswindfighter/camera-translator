package com.example.cameratranslator

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
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
    CAMERA, SETTINGS, ABOUT, HISTORY
}

/** Metin okuma art arda hata verince ekranda gösterilir; kullanıcı ne yapacağını bilsin */
private const val OCR_FAILING_MESSAGE =
    "⚠️ Metin okuma şu an çalışmıyor. Durdurup yeniden başlatın; düzelmezse uygulamayı kapatıp açın veya telefonu yeniden başlatın."


class MainActivity : ComponentActivity() {

    // Telefon ekranı modu: Android'in ekran paylaşımı onayı; onaylanınca hizmet başlar ve uygulama arka plana geçer
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // Çeviriler uygulamanın açık olduğu ekranda gösterilir (DeX'te TV)
            val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            } else android.view.Display.DEFAULT_DISPLAY
            PhoneScreenService.start(this, result.resultCode, data, displayId)
            Toast.makeText(this, "Oyununuzu açın. Menü için ekranın kenarındaki yüzen düğmeye dokunun.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Ekran paylaşımına izin verilmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Telefon ekranı modunu başlatır: önce "diğer uygulamaların üstünde göster" izni, sonra ekran paylaşımı onayı */
    private fun startPhoneScreen() {
        if (!PhoneScreenService.isSupported) {
            Toast.makeText(this, "Ekran Çevirisi Android 10 ve üstünde çalışır.", Toast.LENGTH_LONG).show()
            return
        }
        if (!PhoneScreenService.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Çevirilerin oyunun üstünde görünmesi için açılan ekranda \"Diğer uygulamaların üzerinde göster\" iznini açın, sonra geri dönüp Başlat'a basın.",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private data class OcrApiHistoryItem(
        val rawText: String,
        val normalizedText: String,
        val timestamp: Long
    )

    private val translationManager by lazy {
        SharedTranslation.get(applicationContext)
    }
    private lateinit var speechManager: SpeechManager
    private var lastTranslatedSource: String? = null
    private val lastTranslationTimestamp = AtomicLong(0)
    private var translationJob: Job? = null
    private var currentCamera: Camera? = null
    private lateinit var settingsManager: SettingsManager

    private val recentOcrApiHistory = mutableListOf<OcrApiHistoryItem>()
    private var lastApiCallTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        GoogleApiAuth.init(this)
        settingsManager = SettingsManager(this)
        // Eski ayrı "telefon ekranı kaynağı" ayarı artık üçüncü görünüm modu (Ekran Çevirisi)
        if (settingsManager.isPhoneScreenSource()) {
            settingsManager.saveDisplayMode(DisplayMode.PHONE_SCREEN)
            settingsManager.savePhoneScreenSource(false)
        }
        OcrDebugDumper.init(this)
        OfflineModelDownloader.init(this)
        OcrTextFixer.init(this)
        // Yeni yükleyen: rehberi görür, yenilikleri görmez (istisna: büyük yenilenme sürümü 1.0.7).
        // Güncelleyen: yalnızca "Yenilikler" penceresini görür, rehber çıkmaz (istisna: rehberin ilk geldiği 1.0.7).
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val isUpdate = info != null && info.firstInstallTime != info.lastUpdateTime
        if (isUpdate && !settingsManager.isGuideSeen() && BuildConfig.VERSION_NAME !in GUIDE_ON_UPDATE) {
            settingsManager.saveGuideSeen(true)
        }
        if (settingsManager.getLastSeenVersion() == null && !isUpdate &&
            BuildConfig.VERSION_NAME !in WHATS_NEW_ON_FRESH_INSTALL
        ) {
            settingsManager.saveLastSeenVersion(BuildConfig.VERSION_NAME)
        }
        OcrTuning.textMaskEnabled = settingsManager.getOcrTextMaskEnabled()
        OcrTuning.centerOnlyEnabled = settingsManager.getOcrCenterOnlyEnabled()
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

        lifecycleScope.launch(Dispatchers.IO) {
            UpdateInstaller.cleanUpOnStart(applicationContext)
        }

        lifecycleScope.launch {
            translationManager.prepare()
        }

        // OPUS modeli indirme bitince (OPUS modu seçiliyse) hemen kullanılabilir olsun
        lifecycleScope.launch {
            var wasInstalled = OfflineModelDownloader.state.value is OfflineModelDownloader.State.Installed
            OfflineModelDownloader.state.collect { state ->
                val installed = state is OfflineModelDownloader.State.Installed
                if (installed && !wasInstalled) translationManager.reloadOfflineModel()
                wasInstalled = installed
            }
        }

        setContent {
            var currentScreen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
            val offlineModelState by OfflineModelDownloader.state.collectAsStateWithLifecycle()
            var currentAppTheme by remember { mutableStateOf(settingsManager.getTheme()) }
            var displayMode by remember { mutableStateOf(settingsManager.getDisplayMode()) }
            var showDisplayModeQuickSwitch by remember { mutableStateOf(settingsManager.getShowDisplayModeQuickSwitch()) }
            var isCloudModeEnabled by remember { mutableStateOf(settingsManager.getCloudModeEnabled()) }
            var isOcrTextMaskEnabled by remember { mutableStateOf(OcrTuning.textMaskEnabled) }
            var isOcrCenterOnlyEnabled by remember { mutableStateOf(OcrTuning.centerOnlyEnabled) }
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
            // Odak: ekranlar arası (Ayarlar/Hakkında) korunur; uygulama tamamen kapanınca
            // diske yazılmadığı için tekrar 0 = otomatik odakla başlar
            var manualFocusValue by rememberSaveable { mutableFloatStateOf(0f) }

            var availableUpdate by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
            // Güncellemeden sonraki ilk açılışta bir kez: bu sürümün yenilikleri
            var whatsNewNotes by remember {
                mutableStateOf(
                    if (settingsManager.getLastSeenVersion() == BuildConfig.VERSION_NAME) null
                    else ReleaseNotes.notesFor(BuildConfig.VERSION_NAME)
                )
            }
            LaunchedEffect(Unit) {
                availableUpdate = UpdateChecker.checkOnLaunch(settingsManager)
            }

            var isSplashVisible by remember { mutableStateOf(!hasShownAppLaunchSplash) }
            val splashAlpha = remember { Animatable(if (!hasShownAppLaunchSplash) 1f else 0f) }

            LaunchedEffect(Unit) {
                if (isSplashVisible) {
                    hasShownAppLaunchSplash = true
                    delay(1200L) // Show opening logo full screen for 1.2s
                    splashAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 500) // Smooth 500ms fade-out transition
                    )
                    isSplashVisible = false
                }
            }

            val isDark = when (currentAppTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            CameraTranslatorTheme(darkTheme = isDark) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                                manualFocusValue = manualFocusValue,
                                onManualFocusChange = { manualFocusValue = it },
                                isTrToEn = isTrToEn,
                                isCloudModeEnabled = isCloudModeEnabled,
                                isTtsEnabled = isTtsEnabled,
                                subtitleFontSize = activeSubtitleFontSize,
                                displayMode = displayMode,
                                showDisplayModeQuickSwitch = showDisplayModeQuickSwitch,
                                onSelectDisplayMode = { mode ->
                                    // Sağdaki mod düğmesi küçük bir menü açar; seçilen moda doğrudan geçilir
                                    if (mode != displayMode) {
                                        displayMode = mode
                                        settingsManager.saveDisplayMode(displayMode)
                                        // Ekran Çevirisi'nden çıkılınca ekran paylaşımı da durur
                                        if (displayMode != DisplayMode.PHONE_SCREEN && PhoneScreenService.running.value) {
                                            PhoneScreenService.stop(this@MainActivity)
                                        }
                                    }
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
                                isOcrTextMaskEnabled = isOcrTextMaskEnabled,
                                offlineModelState = offlineModelState,
                                onDownloadOfflineModel = { OfflineModelDownloader.start() },
                                onCancelOfflineModelDownload = { OfflineModelDownloader.cancel() },
                                onDeleteOfflineModel = {
                                    lifecycleScope.launch {
                                        translationManager.releaseOfflineModel()
                                        OfflineModelDownloader.delete()
                                    }
                                },
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
                                    if (mode != DisplayMode.PHONE_SCREEN && PhoneScreenService.running.value) {
                                        PhoneScreenService.stop(this@MainActivity)
                                    }
                                },
                                onShowDisplayModeQuickSwitchChange = { show ->
                                    showDisplayModeQuickSwitch = show
                                    settingsManager.saveShowDisplayModeQuickSwitch(show)
                                },
                                onOcrTextMaskEnabledChange = { enabled ->
                                    isOcrTextMaskEnabled = enabled
                                    OcrTuning.textMaskEnabled = enabled
                                    settingsManager.saveOcrTextMaskEnabled(enabled)
                                },
                                isOcrCenterOnlyEnabled = isOcrCenterOnlyEnabled,
                                onOcrCenterOnlyEnabledChange = { enabled ->
                                    isOcrCenterOnlyEnabled = enabled
                                    OcrTuning.centerOnlyEnabled = enabled
                                    settingsManager.saveOcrCenterOnlyEnabled(enabled)
                                },
                                onApiKeySave = { key ->
                                    settingsManager.saveApiKey(key)
                                    translationManager.updateApiKey(key)
                                    lifecycleScope.launch {
                                        translationManager.prepare()
                                    }
                                },
                                isCloudModeEnabled = isCloudModeEnabled,
                                onCloudModeEnabledChange = { enabled ->
                                    isCloudModeEnabled = enabled
                                    settingsManager.saveCloudModeEnabled(enabled)
                                    // Cloud kapatıldıysa ve seçili mod Cloud ise OPUS'a geç
                                    if (!enabled && settingsManager.getTranslationMode() == TranslationMode.OFFLINE) {
                                        settingsManager.saveTranslationMode(TranslationMode.OFFLINE)
                                        translationManager.mode = TranslationMode.OFFLINE
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
                        Screen.HISTORY -> {
                            HistoryScreen(onBack = { currentScreen = Screen.CAMERA })
                        }
                    }

                    // Full-Screen Opening Cover Overlay (Adaptive: opening_logo.png in Portrait, opening_logo2.png in Landscape)
                    if (isSplashVisible) {
                        val configuration = LocalConfiguration.current
                        val isLandscapeMode = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val openingCoverDrawable = if (isLandscapeMode) R.drawable.opening_logo2 else R.drawable.opening_logo

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = splashAlpha.value }
                                .background(Color.Black)
                        ) {
                            Image(
                                painter = painterResource(id = openingCoverDrawable),
                                contentDescription = "Opening Cover Logo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    // Yenilikler penceresi: açılış logosundan sonra, sürüm başına bir kez
                    val notes = whatsNewNotes
                    if (notes != null && !isSplashVisible) {
                        WhatsNewDialog(version = BuildConfig.VERSION_NAME, notes = notes) {
                            settingsManager.saveLastSeenVersion(BuildConfig.VERSION_NAME)
                            whatsNewNotes = null
                        }
                    } else if (notes == null && settingsManager.getLastSeenVersion() != BuildConfig.VERSION_NAME) {
                        // Bu sürümün notu yoksa da bir dahaki sürüme kadar sorulmasın
                        settingsManager.saveLastSeenVersion(BuildConfig.VERSION_NAME)
                    }

                    // Yeni sürüm penceresi: her sürüm için yalnızca bir kez, açılış logosundan ve yeniliklerden sonra
                    val update = availableUpdate
                    if (update != null && !isSplashVisible && whatsNewNotes == null) {
                        val dismiss: () -> Unit = {
                            settingsManager.savePromptedUpdateVersion(update.version)
                            availableUpdate = null
                        }
                        // Uygulama içinde indirip kurulum penceresini açar (APK yoksa GitHub sayfası)
                        UpdateDialog(release = update, onDismiss = dismiss)
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
        manualFocusValue: Float,
        onManualFocusChange: (Float) -> Unit,
        isTrToEn: Boolean,
        isTtsEnabled: Boolean,
        subtitleFontSize: Float,
        displayMode: DisplayMode,
        showDisplayModeQuickSwitch: Boolean,
        onSelectDisplayMode: (DisplayMode) -> Unit,
        onToggleDirection: () -> Unit,
        onNavigate: (Screen) -> Unit,
        isCloudModeEnabled: Boolean
    ) {
        val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

        var currentTranslation by rememberSaveable { mutableStateOf("") }
        // Sinematik altyazıda önceki çevirilerin akışı (Ayarlar'dan; ekrana dönünce yeniden okunur)
        val subtitleFlow = remember { settingsManager.getSubtitleFlow() }
        val flowLines = remember { settingsManager.getFlowLines() }
        val eyeBigText = remember { settingsManager.getEyeBigText() }
        // İlk açılış rehberinin adımı (-1 = kapalı); Hakkında'dan tekrar açılabilir
        var guideStep by remember { mutableIntStateOf(if (settingsManager.isGuideSeen()) -1 else 0) }
        // Ekran çevirisi: çeviri kutusu İngilizce yazının tam üstüne konur; yazı 5 sn görünmezse kutu kaybolur
        var screenTextBounds by remember { mutableStateOf<android.graphics.RectF?>(null) }
        val lastTextBounds = remember { arrayOfNulls<android.graphics.RectF>(1) }
        // İngilizce satır yüksekliği (OCR alanına oranla): Türkçe yazı aynı boyutta gösterilir
        var screenTextLineHeight by remember { mutableFloatStateOf(0f) }
        val lastLineHeight = remember { floatArrayOf(0f) }
        val lastTextSeenAt = remember { longArrayOf(0L) }
        var historyList by rememberSaveable { mutableStateOf(listOf<String>()) }
        var isPaused by rememberSaveable { mutableStateOf(true) }
        // Okunacak kaynak: kamera (TV) ya da telefonun kendi ekranı
        // Ekran Çevirisi modu: kamera yerine telefonun kendi ekranı okunur
        val isPhoneSource = displayMode == DisplayMode.PHONE_SCREEN
        LaunchedEffect(isPhoneSource) {
            // Bu moda geçilince kamera çevirisi durur (kamera zaten kapanır)
            if (isPhoneSource) isPaused = true
        }
        val phoneRunning by PhoneScreenService.running.collectAsStateWithLifecycle()
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
            OcrStabilizer(
                // OPUS sözlüğüyle: iki temiz ve neredeyse aynı okuma 3. okumayı beklemeden onaylanır
                isCleanReading = { CleanReading.isClean(it, translationManager::isKnownWord) },
                isKnownWord = translationManager::isKnownWord,
                // Onay beklenirken en olası metni OPUS'ta önceden çevir (sonuç önbellekte hazır olur).
                // Ekrana erken bir şey gitmez; motor meşgulse gerçek çeviriyi bekletmemek için atlanır.
                onLikelyCandidate = { candidate ->
                    if (!isTrToEnRef.value && translationManager.canPretranslate && !translationManager.isBusy.value) {
                        lifecycleScope.launch { translationManager.translate(candidate) }
                    }
                }
            ) { confirmedText ->
                val now = System.currentTimeMillis()
                // EN -> TR'de Türkçe yazı oyunun değil konsolun bildirimi ("Bu konsol 5 dakika içinde…"): çevrilmez
                if (!isTrToEnRef.value && TextRecognitionAnalyzer.looksTurkish(confirmedText)) return@OcrStabilizer

                // QUOTA SHIELD: Is this OCR text duplicate/similar to recent API calls in last 10s or < 1.5s interval?
                if (isApiCallDuplicateOrSimilar(confirmedText, now)) {
                    return@OcrStabilizer
                }

                // Record API call in quota shield history
                lastApiCallTime = now
                val normNew = normalizeForDeduplication(confirmedText)
                if (recentOcrApiHistory.size >= 5) {
                    recentOcrApiHistory.removeAt(0)
                }
                recentOcrApiHistory.add(OcrApiHistoryItem(confirmedText, normNew, now))

                lastTranslatedSource = confirmedText
                OcrDebugDumper.logPipeline("Onaylanan", confirmedText)
                OcrBlurWatch.onConfirmed(confirmedText)
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
                            TranslationHistory.add(confirmedText, translated)
                            screenTextBounds = lastTextBounds[0]
                            screenTextLineHeight = lastLineHeight[0]
                            OcrDebugDumper.logPipeline("Çeviri", translated, endOfEntry = true)
                            Log.d(PIPELINE_TAG, "On screen ${System.currentTimeMillis() - timestamp} ms after confirm")
                            speechManager.speak(translated, isTrToEnRef.value)
                        }
                    } catch (e: Exception) {
                        Log.e(PIPELINE_TAG, "Translation exception", e)
                    }
                }
            }
        }

        // Ekran çevirisinin ek alanları (2. ve 3.): her biri kendi onayı ve çevirisiyle, geçmiş ve sesli okuma yok
        val extraAreaCount = remember { settingsManager.getScreenExtraAreaCount() }
        val extraTranslations = remember { mutableStateListOf("", "") }
        val extraBounds = remember { mutableStateListOf<android.graphics.RectF?>(null, null) }
        val extraLineHeights = remember { mutableStateListOf(0f, 0f) }
        val extraLastBounds = remember { arrayOfNulls<android.graphics.RectF>(2) }
        val extraLastLine = remember { FloatArray(2) }
        val extraSeenAt = remember { LongArray(2) }
        val extraEmptyFrames = remember { IntArray(2) }
        val extraStabilizers = remember {
            List(2) { index ->
                OcrStabilizer(
                    isCleanReading = { CleanReading.isClean(it, translationManager::isKnownWord) },
                    isKnownWord = translationManager::isKnownWord
                ) { confirmedText ->
                    if (!isTrToEnRef.value && TextRecognitionAnalyzer.looksTurkish(confirmedText)) return@OcrStabilizer
                    // Ek alanlarda orta filtresi yok: harita, para, mesafe gibi yazılar ("$1,250", "0.4 mi")
                    // çevrilmesin; en az 2 gerçek kelime gerekir
                    val realWords = confirmedText.split(' ').count { word ->
                        val core = word.trim { !it.isLetter() && it != '\'' }.lowercase()
                        core.length >= 2 && translationManager.isKnownWord(core)
                    }
                    if (realWords < 2) return@OcrStabilizer
                    lifecycleScope.launch {
                        try {
                            val translated = withContext(Dispatchers.IO) { translationManager.translate(confirmedText) }
                            if (translated.isBlank()) return@launch
                            extraTranslations[index] = translated
                            TranslationHistory.add(confirmedText, translated, area = index + 2)
                            extraBounds[index] = extraLastBounds[index]
                            extraLineHeights[index] = extraLastLine[index]
                            OcrDebugDumper.logPipeline("Alan ${index + 2}", "$confirmedText → $translated", endOfEntry = true)
                        } catch (e: Exception) {
                            Log.e(PIPELINE_TAG, "Extra area translation exception", e)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(500)
                val now = System.currentTimeMillis()
                for (i in 0 until 2) {
                    if (extraBounds[i] != null && now - extraSeenAt[i] > SCREEN_BOX_HIDE_MS) extraBounds[i] = null
                }
            }
        }

        // Metin okuma art arda hata veriyorsa "Altyazı bekleniyor" yerine nedeni gösterilir
        var isOcrFailing by remember { mutableStateOf(false) }
        val analyzer = remember {
            TextRecognitionAnalyzer(
                onTextDetected = { ocrText, confidence ->
                    stabilizer.submit(ocrText, confidence)
                },
                onTextBounds = { bounds, lineHeight ->
                    lastTextBounds[0] = bounds
                    lastLineHeight[0] = lineHeight
                    lastTextSeenAt[0] = System.currentTimeMillis()
                },
                onExtraText = { index, text, confidence, bounds, lineHeight ->
                    extraEmptyFrames[index] = 0
                    if (bounds != null) {
                        extraLastBounds[index] = bounds
                        extraLastLine[index] = lineHeight
                    }
                    extraSeenAt[index] = System.currentTimeMillis()
                    extraStabilizers[index].submit(text, confidence)
                },
                onExtraEmpty = { index ->
                    extraEmptyFrames[index]++
                    if (extraEmptyFrames[index] >= 3) extraStabilizers[index].notifyEmpty()
                },
                onEmptyDetected = {
                    stabilizer.notifyEmpty()
                    lastTranslatedSource = null
                    recentOcrApiHistory.clear()
                },
                // Analiz iş parçacığından gelir: durum ana iş parçacığında değişsin
                onRecognizerFailing = { failing -> runOnUiThread { isOcrFailing = failing } }
            )
        }

        LaunchedEffect(displayMode) {
            for (i in 0 until 2) {
                if (displayMode == DisplayMode.DEFAULT || i >= extraAreaCount) analyzer.setExtraArea(i, null)
            }
        }

        LaunchedEffect(screenTextBounds) {
            if (screenTextBounds == null) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(500)
                if (System.currentTimeMillis() - lastTextSeenAt[0] > SCREEN_BOX_HIDE_MS) {
                    screenTextBounds = null
                    break
                }
            }
        }

        LaunchedEffect(isPaused) {
            analyzer.setPaused(isPaused)
            if (isPaused) {
                screenTextBounds = null
                for (i in 0 until 2) {
                    extraStabilizers[i].reset()
                    extraTranslations[i] = ""
                    extraBounds[i] = null
                }
            }
            isFirstFrameAfterStart = true
            if (isPaused) {
                stabilizer.reset()
                lastTranslatedSource = null
                // Tekrar koruması (10 sn içinde aynı cümle çevrilmez) duraklatmayı aşmasın: Durdur'dan sonra
                // Başlat'a basılınca ekrandaki altyazı, az önce çevrilmiş olsa da yeniden çevrilir (ekran temizlendi)
                recentOcrApiHistory.clear()
                lastApiCallTime = 0L
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
        var isFocusControlsVisible by rememberSaveable { mutableStateOf(false) }
        var exposureRange by remember { mutableStateOf(-4..4) }
        // Kameranın desteklediği yakınlaştırma aralığı (S23 Ultra: 0.6x geniş açı – 100x)
        // Son bilinen aralıkla başla: döndürünce ekran yeniden kurulurken kaydırıcı varsayılan
        // aralıkla (1x en altta) çizilip sonra 1x'e zıplıyordu. Aralık bilinmiyorsa kaydırıcı gizli.
        var zoomRange by remember { mutableStateOf(settingsManager.getZoomRange()) }
        // Ayarlar'dan dönünce bu ekran yeniden kurulduğu için kayıtlı seçim okunur
        val useZoomSlider = remember { settingsManager.getZoomSliderEnabled() }
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
                            isFocusControlsVisible = false
                        })
                    }
            ) {
                val isStealthModeVal = isStealthMode
                val cameraAlpha = if (isStealthModeVal && displayMode == DisplayMode.DEFAULT) 0f else 1f
                // Telefon ekranı modunda kamera hiç açılmaz (pil)
                if (!isPhoneSource) Box(modifier = Modifier.fillMaxSize().alpha(cameraAlpha)) {
                    CameraPreview(
                        analyzer = analyzer,
                        zoomLevel = zoomLevel,
                        exposureIndex = exposureIndex,
                        manualFocusValue = manualFocusValue,
                        onCameraReady = { camera ->
                            currentCamera = camera
                            // Aralık kamera bağlanınca hemen gelmeyebilir: değişince güncelle
                            camera.cameraInfo.zoomState.removeObservers(this@MainActivity)
                            camera.cameraInfo.zoomState.observe(this@MainActivity) { zoom ->
                                val range = zoom.minZoomRatio..zoom.maxZoomRatio.coerceAtMost(ZOOM_SLIDER_MAX)
                                if (range != zoomRange) {
                                    zoomRange = range
                                    settingsManager.saveZoomRange(range.start, range.endInclusive)
                                }
                            }
                            val state = camera.cameraInfo.exposureState
                            exposureRange = IntRange(
                                state.exposureCompensationRange.lower,
                                state.exposureCompensationRange.upper
                            )
                        },
                        onPreviewViewReady = onPreviewViewReady
                    )
                }

                // OPUS modundayken model yoksa (duraklatılmış ekranda) kullanıcıyı yönlendir
                val offlineModelState by OfflineModelDownloader.state.collectAsStateWithLifecycle()
                // "Daha sonra" denince panel bir daha çıkmaz; Ayarlar'dan indirilirse ilerleme yine görünür
                var opusNoticeDismissed by remember { mutableStateOf(settingsManager.getOpusNoticeDismissed()) }
                val opusNotice: OpusNotice? = if (isPaused && !isPhoneSource && translationMode == TranslationMode.OFFLINE && !isTrToEn) {
                    when (val m = offlineModelState) {
                        is OfflineModelDownloader.State.Installed -> null
                        is OfflineModelDownloader.State.Downloading -> OpusNotice.Downloading(m.progress)
                        is OfflineModelDownloader.State.Verifying -> OpusNotice.Verifying
                        is OfflineModelDownloader.State.Failed -> if (opusNoticeDismissed) null else OpusNotice.NotInstalled(m.message)
                        else -> if (opusNoticeDismissed) null else OpusNotice.NotInstalled()
                    }
                } else null

                val statusText = when (val s = translationStatus) {
                    is TranslationStatus.DownloadingModel -> ""
                    is TranslationStatus.Ready -> if (isPaused) "Çeviriye Hazır" else ""
                    is TranslationStatus.Error -> if (isPaused) s.message else ""
                    else -> ""
                }

                val isBusy by translationManager.isBusy.collectAsStateWithLifecycle()

                val displayResult = when {
                    // Ekran Çevirisi'nin ana ekranı: PhoneModeIntro kendi içeriğini gösterir
                    isPhoneSource -> ""
                    opusNotice != null -> ""
                    statusText.isNotEmpty() -> statusText
                    isPaused -> ""
                    isOcrFailing -> OCR_FAILING_MESSAGE
                    currentTranslation.isNotEmpty() -> currentTranslation
                    isBusy && translationMode != TranslationMode.OFFLINE -> "Çevriliyor..."
                    else -> "Altyazı bekleniyor..."
                }

                // Etiket: OPUS modunda model yüklü ve yön EN -> TR ise OPUS motoru aktif
                val isOfflinePlusActive = translationMode == TranslationMode.OFFLINE && !isTrToEn &&
                    offlineModelState is OfflineModelDownloader.State.Installed

                MainOverlay(
                    currentTranslation = displayResult,
                    opusNotice = opusNotice,
                    opusNoticeActions = OpusNoticeActions(
                        onDownload = { OfflineModelDownloader.start() },
                        onCancel = { OfflineModelDownloader.cancel() },
                        onLater = {
                            opusNoticeDismissed = true
                            settingsManager.saveOpusNoticeDismissed()
                            Toast.makeText(
                                context,
                                "Şimdilik standart çevrimdışı motor kullanılacak. OPUS'u istediğiniz zaman Ayarlar'dan indirebilirsiniz.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    ),
                    // Metin okuma hatası da durum yazısı gibi gösterilir (Yazının üstünde modunda da görünsün)
                    isStatusMessage = isPhoneSource || (isOcrFailing && !isPaused),
                    isPhoneMode = isPhoneSource,
                    screenTextBounds = if (isPaused || currentTranslation.isEmpty()) null else screenTextBounds,
                    screenTextLineHeight = screenTextLineHeight,
                    extraAreas = if (displayMode == DisplayMode.DEFAULT) emptyList() else List(extraAreaCount) { i ->
                        ExtraAreaUi(
                            initialPortrait = { settingsManager.getExtraOcrRect(i, landscape = false) },
                            initialLandscape = { settingsManager.getExtraOcrRect(i, landscape = true) },
                            translation = if (isPaused) "" else extraTranslations[i],
                            bounds = if (isPaused) null else extraBounds[i],
                            lineHeight = extraLineHeights[i]
                        )
                    },
                    guideStep = guideStep,
                    onGuideNext = {
                        if (guideStep >= GUIDE_STEP_COUNT - 1) {
                            guideStep = -1
                            settingsManager.saveGuideSeen(true)
                        } else {
                            guideStep++
                        }
                    },
                    onGuideBack = { if (guideStep > 0) guideStep-- },
                    onGuideSkip = {
                        guideStep = -1
                        settingsManager.saveGuideSeen(true)
                    },
                    onExtraAreaChange = { index, rect -> analyzer.setExtraArea(index, rect) },
                    onExtraAreaSave = { index, landscape, rect ->
                        settingsManager.saveExtraOcrRect(index, landscape, rect.left, rect.top, rect.right, rect.bottom)
                    },
                    history = historyList,
                    subtitleFlow = subtitleFlow,
                    flowLines = flowLines,
                    eyeBigText = eyeBigText,
                    // Telefon ekranı modunda Başlat/Durdur hizmetin durumunu gösterir
                    isPaused = if (isPhoneSource) !phoneRunning else isPaused,
                    zoomLevel = zoomLevel,
                    translationMode = translationMode,
                    isOfflinePlusActive = isOfflinePlusActive,
                    // Ekran Çevirisi'nde göz simgesi yok: göz modu açıkken geçilirse düğmeler gizli kalmasın
                    isStealthMode = isStealthMode && !isPhoneSource,
                    onToggleStealthMode = { isStealthMode = !isStealthMode },
                    onCycleTranslationMode = {
                        // OPUS → Google Translate → (Ayarlar'dan açıldıysa) Google Cloud → OPUS
                        translationMode = when (translationMode) {
                            TranslationMode.OFFLINE -> TranslationMode.FREE
                            TranslationMode.FREE -> if (isCloudModeEnabled) TranslationMode.CLOUD else TranslationMode.OFFLINE
                            TranslationMode.CLOUD -> TranslationMode.OFFLINE
                        }
                        translationManager.mode = translationMode
                    },
                    onTogglePause = {
                        if (isPhoneSource) {
                            if (phoneRunning) PhoneScreenService.stop(context) else startPhoneScreen()
                            return@MainOverlay
                        }
                        if (isPaused) {
                            if (translationMode == TranslationMode.CLOUD && settingsManager.getApiKey().isBlank()) {
                                Toast.makeText(context, "Lütfen Ayarlar'dan Cloud Translation API anahtarınızı girin.", Toast.LENGTH_LONG).show()
                                return@MainOverlay
                            }
                        }

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
                        isFocusControlsVisible = false
                        isExposureControlsVisible = !isExposureControlsVisible
                    },
                    exposureRange = exposureRange,
                    exposureIndex = exposureIndex,
                    onExposureIndexChange = onExposureChange,
                    isFocusControlsVisible = isFocusControlsVisible,
                    onToggleFocusControls = {
                        isExposureControlsVisible = false
                        isFocusControlsVisible = !isFocusControlsVisible
                    },
                    manualFocusValue = manualFocusValue,
                    onManualFocusChange = onManualFocusChange,
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
                    onSelectDisplayMode = onSelectDisplayMode,
                    useZoomSlider = useZoomSlider,
                    isZoomRangeKnown = zoomRange != null,
                    minZoom = zoomRange?.start ?: 1f,
                    maxZoom = zoomRange?.endInclusive ?: ZOOM_SLIDER_MAX,
                    // Her seferinde ayarlardan taze okunur: eskiden ekran açılışında bir kez okunup saklandığı için
                    // görünüm modu düğmesiyle geçince OCR alanı son ayarlanan yerine değil eski yerine dönüyordu
                    initialOcrRectDefaultPortrait = { settingsManager.getOcrRectDefaultPortrait() },
                    initialOcrRectDefaultLandscape = { settingsManager.getOcrRectDefaultLandscape() },
                    initialOcrRectScreenPortrait = { settingsManager.getOcrRectScreenPortrait() },
                    initialOcrRectScreenLandscape = { settingsManager.getOcrRectScreenLandscape() },
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

    private fun isApiCallDuplicateOrSimilar(newRawText: String, now: Long): Boolean {
        val normNew = normalizeForDeduplication(newRawText)
        if (normNew.length < 2) return true

        // 1. Remove items older than 12 seconds from history
        recentOcrApiHistory.removeAll { (now - it.timestamp) > 12000L }

        // 2. ANCHOR LOCK: Check if new text is duplicate/similar (>= 60% similarity) to current screen anchor
        for (item in recentOcrApiHistory) {
            val sim = computeSimilarity(normNew, item.normalizedText)
            if (sim >= 0.60f) {
                Log.d(PIPELINE_TAG, "ANCHOR LOCK: Skipping duplicate/similar text on screen: '$newRawText'")
                return true
            }
        }

        // NEW DISTINCT SENTENCE: Allow INSTANT translation on frame 1!
        return false
    }

    private fun computeSimilarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f

        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a

        if (shorter.length >= 4 && longer.contains(shorter) && (shorter.length.toFloat() / longer.length.toFloat()) >= 0.65f) {
            return 1.0f
        }

        val distance = levenshteinDistance(a, b)
        val maxLength = kotlin.math.max(a.length, b.length)
        return 1.0f - (distance.toFloat() / maxLength.toFloat())
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
        // Telefon ekranı modu sürüyorsa çeviri motoru hizmette kullanılmaya devam eder
        if (!PhoneScreenService.running.value) SharedTranslation.close()
    }

    companion object {
        private const val PIPELINE_TAG = "PIPELINE"
        /** Ekran çevirisinde İngilizce yazı bu kadar süre görünmezse çeviri kutusu kaybolur */
        private const val SCREEN_BOX_HIDE_MS = 5000L
        /** Yeni yükleyene de "Yenilikler" penceresinin gösterildiği sürümler (büyük yenilenmeler) */
        private val WHATS_NEW_ON_FRESH_INSTALL = setOf("1.0.7")
        /** Güncelleyene de rehberin gösterildiği sürümler (rehberin ilk geldiği sürüm) */
        private val GUIDE_ON_UPDATE = setOf("1.0.7")
        private var hasShownAppLaunchSplash = false
    }
}
