package com.example.cameratranslator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Telefon ekranı modu (deneysel): kamera yerine telefonun kendi ekranı okunur (ör. telefonda ya da Remote Play ile
 * oynanan oyun). Ekran Android'in ekran yakalama izniyle alınır; 1–3 OCR alanının yazısı okunur, çevrilir ve
 * oyunun üstündeki şeffaf katmanda, İngilizce yazının hemen üstünde (yer yoksa altında) gösterilir.
 *
 * - Çeviri katmanı dokunulmazdır: dokunuşlar doğrudan oyuna gider.
 * - Katman ve yüzen düğme ekran yakalamada siyah görünür (FLAG_SECURE): OCR kendi çevirimizi okumaz. Bu yüzden
 *   çeviri İngilizce yazının üstüne değil yanına konur; üstüne konsaydı altındaki yeni altyazı okunamazdı.
 * - OCR alanları yalnızca yüzen düğmeden "Alanları düzenle" ile görünür ve taşınır.
 */
class PhoneScreenService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    /** Ekranda gösterilen bir alanın çevirisi; [textRect] İngilizce yazının ekrandaki yeri (0..1) */
    data class AreaTranslation(val text: String, val textRect: RectF, val lineHeight: Float, val shownAt: Long)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: SettingsManager
    private lateinit var windowManager: WindowManager
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translationManager by lazy { SharedTranslation.get(applicationContext) }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureJob: Job? = null
    private var overlayView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // Katmanın okuduğu durum (Compose)
    val isLandscape = mutableStateOf(false)
    val isEditing = mutableStateOf(false)
    val areaCount = mutableStateOf(1)
    val translations = mutableStateListOf<AreaTranslation?>(null, null, null)
    val areaVersion = mutableStateOf(0)
    /** Yüzen düğme ekranın sağ / alt yarısında mı: menü ekranın içine doğru açılır */
    val bubbleOnRight = mutableStateOf(false)
    val bubbleOnBottom = mutableStateOf(false)
    /**
     * Çeviri altyazının tam üstüne konabilir mi: Android'in ekran paylaşımında "tek uygulama" seçilirse katmanımız
     * yakalanan görüntüde yoktur, OCR altındaki yazıyı okumaya devam eder. "Tüm ekran" seçildiyse katman yakalamada
     * siyah görünür (yazı kapanır); bu fark edilince çeviri yazının hemen üstüne (yanına) alınır.
     */
    val coverText = mutableStateOf(true)

    private val lastSeenAt = LongArray(MAX_AREAS)
    private val lastTextRect = arrayOfNulls<RectF>(MAX_AREAS)
    private val lastLineHeight = FloatArray(MAX_AREAS)
    private val emptyReads = IntArray(MAX_AREAS)
    private val stabilizers by lazy {
        List(MAX_AREAS) { index ->
            OcrStabilizer(
                isCleanReading = { CleanReading.isClean(it, translationManager::isKnownWord) },
                isKnownWord = translationManager::isKnownWord
            ) { confirmed -> onConfirmed(index, confirmed) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        settings = SettingsManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        isLandscape.value = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        areaCount.value = settings.getPhoneAreaCount()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android, ekran yakalamadan önce bildirimli ön plan hizmeti ister
        startForegroundWithNotification()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection failed", e)
            null
        }
        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Kullanıcı bildirimden ya da sistemden paylaşımı durdurdu
                scope.launch { stopSelf() }
            }
        }, Handler(mainLooper))
        startCapture()
        showOverlay()
        showBubble()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        _running.value = true
        captureJob = scope.launch { captureLoop() }
        scope.launch { hideStaleTranslations() }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Dönünce ekranın boyutu değişir: yakalama da yeni boyuta göre yeniden kurulur
        for (i in 0 until MAX_AREAS) translations[i] = null
        stabilizers.forEach { it.reset() }
        restartCapture()
    }

    override fun onDestroy() {
        _running.value = false
        captureJob?.cancel()
        scope.cancel()
        runCatching { overlayView?.let { windowManager.removeView(it) } }
        runCatching { bubbleView?.let { windowManager.removeView(it) } }
        overlayView = null
        bubbleView = null
        releaseCapture()
        runCatching { projection?.stop() }
        projection = null
        recognizer.close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ---------- Ekran yakalama ----------

    private fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
        return bounds
    }

    @SuppressLint("WrongConstant")
    private fun startCapture() {
        val mediaProjection = projection ?: return
        val (width, height) = screenSize()
        val thread = HandlerThread("PhoneScreenCapture").also { it.start() }
        captureThread = thread
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CameraTranslatorScreen", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, Handler(thread.looper)
        )
    }

    @SuppressLint("WrongConstant")
    private fun restartCapture() {
        val display = virtualDisplay ?: return
        val (width, height) = screenSize()
        val old = imageReader
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        display.resize(width, height, resources.displayMetrics.densityDpi)
        display.surface = reader.surface
        old?.close()
    }

    private fun releaseCapture() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
    }

    /** Son kareyi alır, alanları keser ve okur (OCR iki okuma arasında boş kalmaz, telefonu yormaz). */
    private suspend fun captureLoop() {
        while (scope.isActive) {
            delay(FRAME_INTERVAL_MS)
            if (isEditing.value) continue
            val frame = withContext(Dispatchers.Default) { grabFrame() } ?: continue
            try {
                for (index in 0 until areaCount.value) {
                    val area = settings.getPhoneAreaRect(index, isLandscape.value)
                    readArea(index, frame, area)
                }
            } finally {
                frame.recycle()
            }
        }
    }

    private fun grabFrame(): Bitmap? {
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padded.width == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (e: Exception) {
            Log.w(TAG, "Frame copy failed", e)
            null
        } finally {
            image.close()
        }
    }

    private suspend fun readArea(index: Int, frame: Bitmap, area: FloatArray) {
        val left = (area[0] * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (area[1] * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (area[2] * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (area[3] * frame.height).toInt().coerceIn(top + 1, frame.height)
        if (right - left < 30 || bottom - top < 12) return
        val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        try {
            val result = recognizer.process(InputImage.fromBitmap(crop, 0)).await()
            val lines = result.textBlocks.flatMap { it.lines }
                .filter { line -> line.text.count { it.isLetter() } >= 2 }
            val text = lines.joinToString(" ") { it.text.trim() }.trim()
            if (text.isBlank()) {
                detectBlackedOutCover(index, frame)
                emptyReads[index]++
                if (emptyReads[index] >= 3) stabilizers[index].notifyEmpty()
                return
            }
            emptyReads[index] = 0
            val boxes = lines.mapNotNull { it.boundingBox }
            if (boxes.isNotEmpty()) {
                // Yazının ekrandaki yeri (0..1)
                lastTextRect[index] = RectF(
                    (left + boxes.minOf { it.left }).toFloat() / frame.width,
                    (top + boxes.minOf { it.top }).toFloat() / frame.height,
                    (left + boxes.maxOf { it.right }).toFloat() / frame.width,
                    (top + boxes.maxOf { it.bottom }).toFloat() / frame.height
                )
                lastLineHeight[index] = boxes.map { it.height() }.average().toFloat() / frame.height
            }
            lastSeenAt[index] = System.currentTimeMillis()
            val confidence = lines.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
            stabilizers[index].submit(text, confidence)
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed", e)
        } finally {
            crop.recycle()
        }
    }

    private fun onConfirmed(index: Int, text: String) {
        val isTrToEn = translationManager.isTrToEn
        if (!isTrToEn && TURKISH_LETTERS.containsMatchIn(text)) return
        // Oyun arayüzü yazıları (sayı, para, mesafe) çevrilmesin: 1. alanda 1, diğerlerinde 2 gerçek kelime gerekir
        val realWords = text.split(' ').count { word ->
            val core = word.trim { !it.isLetter() && it != '\'' }.lowercase()
            core.length >= 2 && translationManager.isKnownWord(core)
        }
        if (!isTrToEn && realWords < if (index == 0) 1 else 2) return
        scope.launch {
            val translated = try {
                withContext(Dispatchers.IO) { translationManager.translate(text) }
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed", e)
                ""
            }
            if (translated.isBlank()) return@launch
            val rect = lastTextRect[index] ?: return@launch
            translations[index] = AreaTranslation(translated, rect, lastLineHeight[index], System.currentTimeMillis())
            TranslationHistory.add(text, translated, area = index + 1)
            OcrDebugDumper.logPipeline("Telefon ekranı ${index + 1}", "$text → $translated", endOfEntry = true)
        }
    }

    /**
     * Çeviri yazının üstündeyken alan boş okunduysa ve yazının yeri yakalamada simsiyahsa (gerçek oyun görüntüsü
     * neredeyse hiç tam 0,0,0 olmaz) kutumuz yakalamada görünüyor demektir: "tüm ekran" paylaşılıyor. O zaman
     * çeviriler yazının yanına alınır ve kullanıcıya bir kez nasıl üstüne getirileceği söylenir.
     */
    private fun detectBlackedOutCover(index: Int, frame: Bitmap) {
        if (!coverText.value) return
        val shown = translations[index] ?: return
        // Yalnızca çeviri yeni gösterilmişken: altyazı bitince sinematik siyah bantlar yanlış alarm vermesin
        if (System.currentTimeMillis() - shown.shownAt > 1500) return
        val rect = shown.textRect
        val left = (rect.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val right = (rect.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val top = (rect.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val bottom = (rect.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
        var black = 0
        var total = 0
        for (gy in 0 until 6) {
            val y = top + (bottom - top) * (gy * 2 + 1) / 12
            for (gx in 0 until 20) {
                val x = left + (right - left) * (gx * 2 + 1) / 40
                if ((frame.getPixel(x, y) and 0xFFFFFF) == 0) black++
                total++
            }
        }
        if (total > 0 && black >= total * 0.95f) {
            coverText.value = false
            android.widget.Toast.makeText(
                this,
                "Çeviriler altyazının yanında gösterilecek. Tam üstünde görmek için ekran paylaşımında \"Tek uygulama\" seçin.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** İngilizce yazı birkaç saniye görünmezse çevirisi de kaybolur. */
    private suspend fun hideStaleTranslations() {
        while (scope.isActive) {
            delay(500)
            val now = System.currentTimeMillis()
            for (i in 0 until MAX_AREAS) {
                if (translations[i] != null && now - lastSeenAt[i] > HIDE_AFTER_MS) translations[i] = null
            }
        }
    }

    // ---------- Pencereler ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PhoneScreenService)
            setViewTreeSavedStateRegistryOwner(this@PhoneScreenService)
            setContent(content)
        }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            OVERLAY_PASSIVE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        overlayParams = params
        val view = composeView { PhoneScreenOverlay(this) }
        overlayView = view
        windowManager.addView(view, params)
    }

    private fun showBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = resources.displayMetrics.heightPixels / 3
            // Düğme kamera deliğinin olduğu kenara da yanaşabilsin (oyun ekranı orayı da kullanıyor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        bubbleParams = params
        val view = composeView { PhoneScreenBubble(this) }
        bubbleView = view
        windowManager.addView(view, params)
    }

    /** Yüzen düğme sürüklendi (pencere sağa/alta bağlıysa konum o kenardan ölçülür) */
    fun moveBubble(dx: Float, dy: Float) {
        val params = bubbleParams ?: return
        val view = bubbleView ?: return
        val (width, height) = screenSize()
        val fromRight = (params.gravity and Gravity.END) == Gravity.END
        val fromBottom = (params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
        params.x = (params.x + (if (fromRight) -dx else dx).toInt()).coerceIn(0, maxOf(0, width - view.width))
        params.y = (params.y + (if (fromBottom) -dy else dy).toInt()).coerceIn(0, maxOf(0, height - view.height))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Bırakılınca düğme bulunduğu yarıya bağlanır: sağ yarıdaysa pencere sağ kenardan, alt yarıdaysa alt kenardan
     * ölçülür. Menü açılınca pencere ekranın içine doğru büyür, kenardan taşmaz.
     */
    fun anchorBubble() {
        val params = bubbleParams ?: return
        val view = bubbleView ?: return
        val (width, height) = screenSize()
        val fromRight = (params.gravity and Gravity.END) == Gravity.END
        val fromBottom = (params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
        val left = if (fromRight) width - params.x - view.width else params.x
        val top = if (fromBottom) height - params.y - view.height else params.y
        val onRight = left + view.width / 2 > width / 2
        val onBottom = top + view.height / 2 > height / 2
        bubbleOnRight.value = onRight
        bubbleOnBottom.value = onBottom
        params.gravity = (if (onRight) Gravity.END else Gravity.START) or (if (onBottom) Gravity.BOTTOM else Gravity.TOP)
        params.x = if (onRight) width - left - view.width else left
        params.y = if (onBottom) height - top - view.height else top
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** Alan düzenleme: katman dokunulabilir olur, alanlar görünür; bitince yine dokunulmaz */
    fun setEditing(editing: Boolean) {
        isEditing.value = editing
        if (editing) for (i in 0 until MAX_AREAS) translations[i] = null
        val params = overlayParams ?: return
        val view = overlayView ?: return
        params.flags = if (editing) OVERLAY_EDIT_FLAGS else OVERLAY_PASSIVE_FLAGS
        runCatching { windowManager.updateViewLayout(view, params) }
        if (!editing) stabilizers.forEach { it.reset() }
    }

    fun setAreaCount(count: Int) {
        val value = count.coerceIn(1, MAX_AREAS)
        areaCount.value = value
        settings.savePhoneAreaCount(value)
        areaVersion.value++
    }

    fun areaRect(index: Int, landscape: Boolean): FloatArray = settings.getPhoneAreaRect(index, landscape)

    fun saveAreaRect(index: Int, landscape: Boolean, rect: RectF) {
        settings.savePhoneAreaRect(index, landscape, rect.left, rect.top, rect.right, rect.bottom)
    }

    fun minFontSize(): Float = settings.getSubtitleFontSizeScreen()

    fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }
    }

    // ---------- Bildirim ----------

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Telefon ekranı çevirisi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, PhoneScreenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Telefon ekranı çevriliyor")
            .setContentText("Ekrandaki altyazılar okunup çevriliyor.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Durdur", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PhoneScreenService"
        const val MAX_AREAS = 3
        private const val FRAME_INTERVAL_MS = 350L
        private const val HIDE_AFTER_MS = 5000L
        private const val CHANNEL_ID = "phone_screen_translation"
        private const val NOTIFICATION_ID = 7301
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val ACTION_STOP = "com.example.cameratranslator.STOP_PHONE_SCREEN"
        private val TURKISH_LETTERS = Regex("[şŞğĞıİ]")

        // Çeviri katmanı: dokunulmaz (oyun oynanır), ekran yakalamada görünmez (OCR kendi çevirisini okumaz)
        private const val OVERLAY_PASSIVE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE
        // Alan düzenlerken: dokunulabilir
        private const val OVERLAY_EDIT_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE

        private val _running = MutableStateFlow(false)
        /** Telefon ekranı modu çalışıyor mu (ana ekrandaki Başlat/Durdur için) */
        val running: StateFlow<Boolean> = _running

        /** Bu telefonda kullanılabilir mi (Android 10+) */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, PhoneScreenService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PhoneScreenService::class.java).setAction(ACTION_STOP))
        }
    }
}
