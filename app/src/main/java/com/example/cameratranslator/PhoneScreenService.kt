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
import kotlinx.coroutines.CancellationException
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
 * oyunun üstündeki şeffaf katmanda gösterilir. Katmanlar Başlat'a basılan ekranda açılır (DeX'te TV'deki masaüstü).
 *
 * - Çeviri katmanı dokunulmazdır: dokunuşlar doğrudan oyuna gider.
 * - Paylaşımda "tek uygulama" seçildiyse katman yakalanan görüntüde yoktur: çeviri İngilizce yazının tam üstüne
 *   konur, katman ekran yansıtmada (Smart View) da görünür. "Tüm ekran" paylaşıldığı anlaşılırsa (OCR kendi
 *   çevirimizi okursa) katman yakalamadan gizlenir (FLAG_SECURE; yakalamada siyah) ve çeviri yazının yanına alınır,
 *   yoksa altındaki yeni altyazı okunamazdı.
 * - OCR alanları yalnızca yüzen düğmeden "Alanları düzenle" ile görünür ve taşınır.
 */
class PhoneScreenService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    /** Ekranda gösterilen bir alanın çevirisi; [textRect] İngilizce yazının ekrandaki yeri (0..1) */
    data class AreaTranslation(val text: String, val textRect: RectF, val lineHeight: Float, val shownAt: Long, val source: String = "")

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: SettingsManager
    private lateinit var windowManager: WindowManager
    /** Katmanların açıldığı ekranın bağlamı: Başlat'a basılan ekran (DeX'te TV) */
    private var overlayContext: Context = this
    /**
     * Katmanlar ekran yakalamada gizli mi (FLAG_SECURE). Başta kapalı: "tek uygulama" paylaşımında katman zaten
     * yakalanmaz, gizli olmayınca ekran yansıtmada (Smart View) da görünür. "Tüm ekran" paylaşıldığı anlaşılınca
     * açılır ki OCR kendi çevirisini okumasın.
     */
    private var hideFromCapture = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translationManager by lazy { SharedTranslation.get(applicationContext) }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureJob: Job? = null
    private var overlayView: ComposeView? = null
    /** Android'in bildirdiği yakalanan içerik boyutu (yalnızca log için) */
    private var contentSize: Pair<Int, Int>? = null
    private var dumpFrameId = 0L
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    /** Telefonun kendi ekranını açık tutan görünmez 1x1 pencere (DeX'te katman TV'deyken) */
    private var keepAwakeView: android.view.View? = null

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
    /** Son okumaların metni ve yeri: kutu, onaylanan metne en uygun okumanın yerine konur */
    private val recentReads = List(MAX_AREAS) { ArrayDeque<PlacedRead>() }
    /** Sürmekte olan çeviri: yeni altyazı gelince iptal edilir, eski çeviri geç gelip yeni yazının üstüne binmez */
    private val translateJobs = arrayOfNulls<Job>(MAX_AREAS)
    private val emptyReads = IntArray(MAX_AREAS)
    private val lastLoggedRead = arrayOfNulls<String>(MAX_AREAS)
    private val stabilizers by lazy {
        List(MAX_AREAS) { index ->
            OcrStabilizer(
                isCleanReading = { CleanReading.isClean(it, translationManager::isKnownWord) },
                isKnownWord = translationManager::isKnownWord,
                // Kamera modundaki gibi: onay beklenirken en olası metin önceden çevrilir, onayda sonuç hazırdır
                onLikelyCandidate = { candidate ->
                    if (!translationManager.isTrToEn && translationManager.canPretranslate && !translationManager.isBusy.value) {
                        scope.launch { translationManager.translate(candidate) }
                    }
                }
            ) { confirmed -> onConfirmed(index, confirmed) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        settings = SettingsManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
        useDisplay(intent.getIntExtra(EXTRA_DISPLAY_ID, android.view.Display.DEFAULT_DISPLAY))
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Kullanıcı bildirimden ya da sistemden paylaşımı durdurdu
                scope.launch { stopSelf() }
            }

            // Android 14+: yakalanan içeriğin boyutu (geliştirici logu; çevirinin yeri kayarsa sebebi buradan görülür)
            override fun onCapturedContentResize(width: Int, height: Int) {
                scope.launch { onContentResized(width, height) }
            }
        }, Handler(mainLooper))
        startCapture()
        showOverlay()
        showBubble()
        keepPhoneScreenOn()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        _running.value = true
        captureJob = scope.launch { captureLoop() }
        scope.launch { hideStaleTranslations() }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientation()
        // Dönünce ekranın boyutu değişir: yakalama da yeni boyuta göre yeniden kurulur
        for (i in 0 until MAX_AREAS) translations[i] = null
        stabilizers.forEach { it.reset() }
        restartCapture()
    }

    /**
     * Yalnızca değiştiğinde loga yazılır. Yakalama burada yeniden boyutlandırılmaz: boyutlandırma aynı bildirimi
     * tekrar tetikliyor, dönen döngü ekran paylaşımını düşürüyordu.
     */
    private fun onContentResized(width: Int, height: Int) {
        if (contentSize == width to height) return
        contentSize = width to height
        val (screenWidth, screenHeight) = screenSize()
        OcrDebugDumper.logPipeline("Telefon ekranı", "Yakalanan içerik ${width}x$height, ekran ${screenWidth}x$screenHeight", endOfEntry = true)
    }

    override fun onDestroy() {
        _running.value = false
        captureJob?.cancel()
        scope.cancel()
        runCatching { overlayView?.let { windowManager.removeView(it) } }
        runCatching { bubbleView?.let { windowManager.removeView(it) } }
        runCatching { keepAwakeView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        keepAwakeView = null
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

    /**
     * Katmanları [displayId] ekranında açar (DeX'te uygulama TV'deki masaüstündeyse orada). Yakalama da o ekranın
     * boyutunda kurulur: "tek uygulama" paylaşımında seçilen uygulamanın görüntüsü bu boyuta yerleşir.
     */
    private fun useDisplay(displayId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != android.view.Display.DEFAULT_DISPLAY) {
            val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId)
            if (display != null) {
                overlayContext = createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                windowManager = overlayContext.getSystemService(WindowManager::class.java)
            }
        }
        updateOrientation()
    }

    private fun updateOrientation() {
        val (width, height) = screenSize()
        isLandscape.value = width > height
    }

    private fun densityDpi(): Int = overlayContext.resources.displayMetrics.densityDpi

    private fun screenSize(): Pair<Int, Int> {
        val metrics = overlayContext.resources.displayMetrics
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
            "CameraTranslatorScreen", width, height, densityDpi(),
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
        display.resize(width, height, densityDpi())
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
        var lastFrameAt = System.currentTimeMillis()
        var stallLogged = false
        while (scope.isActive) {
            delay(FRAME_INTERVAL_MS)
            if (isEditing.value) continue
            val frame = withContext(Dispatchers.Default) { grabFrame() }
            if (frame == null) {
                // Görüntü değişmeyince yeni kare gelmez; uzun sürerse yakalama durmuş olabilir (geliştirici logu)
                if (!stallLogged && System.currentTimeMillis() - lastFrameAt > STALL_LOG_MS) {
                    stallLogged = true
                    OcrDebugDumper.logPipeline("Telefon ekranı", "${STALL_LOG_MS / 1000} sn'dir ekrandan yeni görüntü gelmiyor", endOfEntry = true)
                }
                continue
            }
            if (stallLogged) {
                stallLogged = false
                OcrDebugDumper.logPipeline("Telefon ekranı", "Ekran görüntüsü yeniden geliyor", endOfEntry = true)
            }
            lastFrameAt = System.currentTimeMillis()
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

    /**
     * 1. alan (altyazı) "Sadece ortadaki altyazıyı oku" açıksa kamera modundaki gibi yalnızca ortadaki kelime
     * gruplarını alır: alanın kenarındaki harita, görev ve arayüz yazıları atılır. Diğer alanlar tümünü okur.
     */
    private suspend fun recognizeLines(input: Bitmap, index: Int): List<ReadLine> {
        val result = recognizer.process(InputImage.fromBitmap(input, 0)).await()
        val centerOnly = index == 0 && OcrTuning.centerOnlyEnabled
        val readLines = result.textBlocks.flatMap { it.lines }
            .filter { line -> line.text.count { it.isLetter() } >= 2 }
            .mapNotNull { line -> if (centerOnly) centeredLine(line, input.width) else ReadLine(line.text.trim(), line.boundingBox, line.confidence) }
            // Araç/tabela yazısı ("PARK RANGER"): tamamı büyük harf ve noktalamasız
            .filter { !TextRecognitionAnalyzer.isCapsDecal(it.text) }
        // Altyazıya ait olmayan satırlar ("oBEY" arması, haritadaki "Adanas") ne metne ne kutunun boyutuna girsin
        return if (index == 0) dropStrayLines(readLines) else readLines
    }

    /** Metnin kelimeleri (en az 2 harfli) ve sözlükte bulunanların sayısı */
    private fun dictionaryScore(text: String): Pair<Int, Int> {
        val cores = text.split(' ').map { word -> word.trim { !it.isLetter() && it != '\'' }.lowercase() }.filter { it.length >= 2 }
        val known = cores.count { OcrTextFixer.isDictionaryWord(it) || OcrTextFixer.isDictionaryWord(it.substringBefore('\'')) }
        return known to cores.size
    }

    private fun dictionaryRatio(text: String): Float {
        val (known, total) = dictionaryScore(text)
        return if (total == 0) 0f else known.toFloat() / total
    }

    /** Okuma bozuk görünüyor mu, ya da açık renkli alanda hiç yazı bulunamadı mı? */
    private fun shouldReadAgain(text: String, crop: Bitmap): Boolean {
        if (translationManager.isTrToEn || !OcrTextFixer.isDictionaryLoaded) return false
        if (text.isBlank()) return isBrightArea(crop)
        val (known, total) = dictionaryScore(text)
        return total >= 4 && known < total * READ_AGAIN_RATIO
    }

    /** Görüntünün ortalama parlaklığı yüksek mi (seyrek örnekleme) */
    private fun isBrightArea(bitmap: Bitmap): Boolean {
        var sum = 0L
        var count = 0
        for (gy in 0 until 8) {
            val y = bitmap.height * (gy * 2 + 1) / 16
            for (gx in 0 until 24) {
                val pixel = bitmap.getPixel(bitmap.width * (gx * 2 + 1) / 48, y)
                sum += ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                count++
            }
        }
        return sum / count >= BRIGHT_AREA_LEVEL
    }

    /**
     * Beyaz yazıyı açık zeminden ayırır: her pikselin en düşük rengi alınır (krem beton düşük, gerçek beyaz yüksek kalır),
     * en parlak kısım dışındaki her şey siyaha çekilir. Eşik görüntüye göre seçilir; sonuç siyah üstüne beyaz yazıdır.
     */
    private fun boostBrightText(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val lowest = minOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
            pixels[i] = lowest
            histogram[lowest]++
        }
        val brightestAllowed = pixels.size * BOOST_KEEP_RATIO
        var seen = 0
        var threshold = 255
        while (threshold > 0 && seen + histogram[threshold] < brightestAllowed) {
            seen += histogram[threshold]
            threshold--
        }
        threshold = threshold.coerceIn(170, 235)
        for (i in pixels.indices) {
            val level = ((pixels[i] - threshold) * 255 / (255 - threshold)).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private suspend fun readArea(index: Int, frame: Bitmap, area: FloatArray) {
        val left = (area[0] * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (area[1] * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (area[2] * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (area[3] * frame.height).toInt().coerceIn(top + 1, frame.height)
        if (right - left < 30 || bottom - top < 12) return
        val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        // Kamera modundaki gibi OCR'dan önce büyütülür: ekrandaki küçük oyun yazısı büyütülmeden çok bozuk okunuyordu
        // ("clorit kriow what tie fuck"). Kutuların yeri büyütme oranıyla geri ölçeklenir.
        val ocrInput = TextRecognitionAnalyzer.upscaleForOcr(crop)
        val scale = ocrInput.width.toFloat() / crop.width
        // Geliştirici modu: OCR'ın gördüğü görüntü (ör. satır sonu kesiliyor mu diye bakmak için)
        if (index == 0) OcrDebugDumper.maybeSaveFrames(++dumpFrameId, ocrInput, null)
        try {
            var lines = recognizeLines(ocrInput, index)
            // Kamera modundaki gürültü filtresi: harf-rakam karışığı ve anlamsız kelimeler atılır
            var text = TextRecognitionAnalyzer.filterOcrNoise(lines.joinToString(" ") { it.text })
            // Açık zeminde beyaz yazı: normal okuma bozuk ya da boşsa aynı kare işlenip bir daha okunur; daha çok gerçek
            // kelime içeren okuma seçilir. Normal okuma temizse bu adımlar hiç çalışmaz.
            suspend fun readEnhanced(label: String, image: Bitmap) {
                try {
                    OcrDebugDumper.maybeSaveFrames(++dumpFrameId, ocrInput, image)
                    val retryLines = recognizeLines(image, index)
                    val retryText = TextRecognitionAnalyzer.filterOcrNoise(retryLines.joinToString(" ") { it.text })
                    if (retryText.isNotBlank()) {
                        // Oran değil gerçek kelime sayısı: oran, yarım kalan okumayı ("Fly to") tam okumaya tercih ediyordu.
                        // Belirgin kısalan okuma alınmaz: işlem renkli kelimeleri siliyor ("Tail the architect and..." -> "Tail the and...")
                        val keepsText = retryText.count { it.isLetter() } >= text.count { it.isLetter() } * MIN_RETRY_LETTER_RATIO
                        val better = text.isBlank() || (keepsText && dictionaryScore(retryText).first > dictionaryScore(text).first)
                        OcrDebugDumper.logPipeline(
                            "Telefon $label", "${if (better) "SEÇİLDİ" else "seçilmedi"}: \"$text\" / \"$retryText\""
                        )
                        if (better) {
                            lines = retryLines
                            text = retryText
                        }
                    }
                } finally {
                    image.recycle()
                }
            }
            if (index == 0 && shouldReadAgain(text, crop)) {
                // Önce beyaz altyazı filtresi (koyu kenar çizgisine de bakar); görüntü uygun değilse null döner
                if (OcrTuning.textMaskEnabled) {
                    withContext(Dispatchers.Default) { SubtitleTextMask.apply(ocrInput) }?.let { readEnhanced("filtre okuma", it) }
                }
                // Hâlâ zayıfsa en parlak kısmı öne çıkaran ikinci okuma
                if (shouldReadAgain(text, crop)) {
                    readEnhanced("ikinci okuma", withContext(Dispatchers.Default) { boostBrightText(ocrInput) })
                }
            }
            if (text.isBlank()) {
                emptyReads[index]++
                if (emptyReads[index] >= 3) stabilizers[index].notifyEmpty()
                return
            }
            // Altyazıya benzemeyen okuma (tuş ipuçları "Zoom LT RT ...", rakam yığını) "yazı yok" sayılmaz, yalnızca atlanır
            if (!TextRecognitionAnalyzer.isPlausibleSubtitle(text)) return
            emptyReads[index] = 0
            if (text != lastLoggedRead[index]) {
                lastLoggedRead[index] = text
                OcrDebugDumper.logPipeline("Telefon OCR ${index + 1}", text)
            }
            val boxes = lines.mapNotNull { line ->
                line.box?.let {
                    android.graphics.Rect(
                        (it.left / scale).toInt(), (it.top / scale).toInt(),
                        (it.right / scale).toInt(), (it.bottom / scale).toInt()
                    )
                }
            }
            if (boxes.isNotEmpty()) {
                // Yazının ekrandaki yeri (0..1)
                lastTextRect[index] = RectF(
                    (left + boxes.minOf { it.left }).toFloat() / frame.width,
                    (top + boxes.minOf { it.top }).toFloat() / frame.height,
                    (left + boxes.maxOf { it.right }).toFloat() / frame.width,
                    (top + boxes.maxOf { it.bottom }).toFloat() / frame.height
                )
                lastLineHeight[index] = boxes.map { it.height() }.average().toFloat() / frame.height
                rememberRead(index, text)
            }
            lastSeenAt[index] = System.currentTimeMillis()
            val confidence = lines.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)
            // Okunan yazı ekrandaki kendi çevirimizse "tüm ekran" paylaşılıyor: katmanı yakalamadan gizle
            val shown = translations[index]
            if (!hideFromCapture && shown != null && looksLikeOurs(text, shown.text, shown.source)) {
                OcrDebugDumper.logPipeline("Telefon ekranı", "Kendi çevirisi okundu, yanına alınıyor: \"$text\" / \"${shown.text}\"", endOfEntry = true)
                switchToHiddenOverlay()
                return
            }
            stabilizers[index].submit(text, confidence)
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed", e)
        } finally {
            if (ocrInput !== crop) ocrInput.recycle()
            crop.recycle()
        }
    }

    private fun onConfirmed(index: Int, text: String) {
        val isTrToEn = translationManager.isTrToEn
        if (!isTrToEn && TextRecognitionAnalyzer.looksTurkish(text)) {
            OcrDebugDumper.logPipeline("Telefon ekranı ${index + 1}", "Türkçe yazı, çevrilmedi: $text", endOfEntry = true)
            return
        }
        // Oyun arayüzü yazıları (sayı, para, mesafe) çevrilmesin: 1. alanda 1, diğerlerinde 2 gerçek kelime gerekir
        // 1. alan (altyazı) zaten altyazı filtresinden geçti: "Woof!", "It's unconstitutional!" gibi kısa altyazılar
        // atılıyordu. Ek alanlarda oyun arayüzü yazıları (para, mesafe) çevrilmesin: en az 2 gerçek kelime gerekir.
        val realWords = text.split(' ').count { word ->
            val core = word.trim { !it.isLetter() && it != '\'' }.lowercase()
            core.length >= 2 && (translationManager.isKnownWord(core) || translationManager.isKnownWord(core.substringBefore('\'')))
        }
        if (!isTrToEn && index > 0 && realWords < 2) {
            OcrDebugDumper.logPipeline("Telefon ekranı ${index + 1}", "Yeterli gerçek kelime yok, çevrilmedi: $text", endOfEntry = true)
            return
        }
        // Çoğu anlamsız kelimeyse (parlak zeminde bozuk okuma) "İVAR güvenlik ligi" gibi çeviri göstermek yerine
        // daha temiz okuma beklenir. Tam sözlükle bakılır: isKnownWord "clockwork", "Mikey" gibi kelimeleri bilmiyor,
        // temiz cümleleri de engelliyordu. Sözlük yüklenmediyse uygulanmaz.
        val (dictionaryCount, wordCount) = dictionaryScore(text)
        if (!isTrToEn && OcrTextFixer.isDictionaryLoaded && wordCount >= 4 && dictionaryCount < wordCount * MIN_REAL_WORD_RATIO) {
            OcrDebugDumper.logPipeline("Telefon ekranı ${index + 1}", "Bozuk okuma, çevrilmedi ($dictionaryCount/$wordCount): $text", endOfEntry = true)
            // Kilitli kalmasın: aynı altyazının daha temiz okuması onaylanabilsin
            stabilizers[index].reset()
            return
        }
        // Kutu, çeviri hazır olduğu andaki okumaya değil bu metnin okumasına göre konur
        val placed = findRead(index, text)
        translateJobs[index]?.cancel()
        translateJobs[index] = scope.launch {
            val translated = try {
                withContext(Dispatchers.IO) { translationManager.translate(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed", e)
                ""
            }
            if (translated.isBlank()) {
                OcrDebugDumper.logPipeline("Telefon ekranı ${index + 1}", "Çeviri boş geldi: $text", endOfEntry = true)
                return@launch
            }
            val rect = placed?.rect ?: lastTextRect[index] ?: return@launch
            translations[index] = AreaTranslation(translated, rect, placed?.lineHeight ?: lastLineHeight[index], System.currentTimeMillis(), source = text)
            OcrDebugDumper.logPipeline("Telefon kutu ${index + 1}", "$rect")
            TranslationHistory.add(text, translated, area = index + 1)
            OcrDebugDumper.logPipeline("Telefon ekranı ${index + 1}", "$text → $translated", endOfEntry = true)
        }
    }

    /** OCR'ın okuduğu satır (ortadaki kelimeler ayıklandıysa yalnızca onların metni ve yeri) */
    private class ReadLine(val text: String, val box: android.graphics.Rect?, val confidence: Float)

    /** Bir okumanın metni ve ekrandaki yeri */
    private class PlacedRead(val text: String, val rect: RectF, val lineHeight: Float)

    private fun rememberRead(index: Int, text: String) {
        val rect = lastTextRect[index] ?: return
        val reads = recentReads[index]
        synchronized(reads) {
            reads.addLast(PlacedRead(text, RectF(rect), lastLineHeight[index]))
            while (reads.size > RECENT_READS) reads.removeFirst()
        }
    }

    /**
     * Onaylanan metinle en çok ortak kelimesi olan okuma; eşitlikte en yenisi. Böylece tek satırı okunmuş bir kare ya da
     * sonraki altyazı kutunun yerini bozmaz.
     */
    private fun findRead(index: Int, text: String): PlacedRead? {
        val wanted = text.lowercase().split(' ').toSet()
        val reads = recentReads[index]
        return synchronized(reads) {
            reads.asReversed()
                .maxByOrNull { read -> read.text.lowercase().split(' ').count { it in wanted } }
                ?.takeIf { read -> read.text.lowercase().split(' ').any { it in wanted } }
        }
    }

    /**
     * Altyazıya ait olmayan satırları atar. En uzun satır altyazı sayılır; diğerleri ondan belirgin küçükse (araç arması,
     * tabela) ya da hem kısa hem de hizasız ise (haritadaki yer adı) atılır. İkinci satırı kısa altyazılar ("clap.")
     * ortalı ya da sola hizalı olduğu için kalır. Gürültü filtresinin tamamen sileceği satırlar ve altyazıdan dikey olarak
     * kopuk satırlar (karanlık sahnede ceketteki logo) da atılır; yoksa metinde görünmeseler bile kutuyu yukarı uzatıyorlardı.
     */
    private fun dropStrayLines(lines: List<ReadLine>): List<ReadLine> {
        if (lines.size < 2) return lines
        val readable = lines.associateWith { TextRecognitionAnalyzer.filterOcrNoise(it.text) }.filterValues { it.isNotBlank() }
        val main = readable.keys.filter { it.box != null }.maxByOrNull { readable.getValue(it).length } ?: return readable.keys.toList()
        val mainBox = main.box ?: return readable.keys.toList()
        val aligned = readable.keys.filter { line ->
            val box = line.box
            if (line === main || box == null) return@filter true
            if (box.height() < mainBox.height() * 0.7f) return@filter false
            val isLong = line.text.length >= main.text.length * 0.4f
            val isCentered = kotlin.math.abs(box.centerX() - mainBox.centerX()) <= mainBox.width() * 0.15f
            val isLeftAligned = kotlin.math.abs(box.left - mainBox.left) <= mainBox.width() * 0.05f
            isLong || isCentered || isLeftAligned
        }
        return keepConnectedLines(aligned, main)
    }

    /**
     * Ana satırdan yukarı ve aşağı doğru yürür, araya en fazla bir satırlık boşluk girene kadarki satırları tutar (ortadaki
     * satırı okunamamış altyazı da böylece bölünmez). Konumu bilinmeyen satırlar kalır. Satır sırası korunur.
     */
    private fun keepConnectedLines(lines: List<ReadLine>, main: ReadLine): List<ReadLine> {
        val mainBox = main.box ?: return lines
        val maxGap = mainBox.height() * MAX_LINE_GAP_RATIO
        val located = lines.filter { it.box != null }.sortedBy { it.box!!.top }
        val mainIndex = located.indexOf(main)
        val connected = hashSetOf(main)
        var edge = mainBox.top
        for (i in mainIndex - 1 downTo 0) {
            if (edge - located[i].box!!.bottom > maxGap) break
            connected += located[i]
            edge = located[i].box!!.top
        }
        edge = mainBox.bottom
        for (i in mainIndex + 1 until located.size) {
            if (located[i].box!!.top - edge > maxGap) break
            connected += located[i]
            edge = located[i].box!!.bottom
        }
        return lines.filter { it.box == null || it in connected }
    }

    /** Satırın yalnızca ortadaki kelime gruplarını tutar; ortada bir şey yoksa null. Konum bilgisi yoksa satır aynen kalır. */
    private fun centeredLine(line: com.google.mlkit.vision.text.Text.Line, imageWidth: Int): ReadLine? {
        val elements = line.elements
        if (elements.isEmpty() || elements.any { it.boundingBox == null }) {
            return ReadLine(line.text.trim(), line.boundingBox, line.confidence)
        }
        val words = elements.map { element ->
            val box = element.boundingBox!!
            SubtitleRegionFilter.Word(element.text, box.left, box.right, box.height())
        }
        val kept = SubtitleRegionFilter.keepCentered(words, imageWidth)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Kalan kelimelerin kutuları: çeviri tam onların üstüne konsun
        val remaining = kept.split(' ').toMutableList()
        val boxes = elements.filter { remaining.remove(it.text) }.mapNotNull { it.boundingBox }
        val box = if (boxes.isEmpty()) line.boundingBox else android.graphics.Rect(
            boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom }
        )
        return ReadLine(kept, box, line.confidence)
    }

    /**
     * Okunan yazı ekrandaki kendi Türkçe çevirimiz mi? İsimler (büyük harfle başlayan kelimeler) iki dilde aynı
     * olduğu için sayılmaz: "John, Dutch wants you" ile "John, Dutch seni istiyor" yanlışlıkla eşleşiyordu.
     * Okunan küçük harfli kelimelerin büyük çoğunluğu çeviride geçiyorsa (ve en az 2 tane) bizimkidir. İngilizce metinde de
     * olan kelimeler sayılmaz: motor bir cümleyi çevirmeden aynen döndürünce ("Eenie meenie miney mo.") altyazının kendisi
     * "kendi çevirimiz" sanılıyor, kutu oturum boyunca altyazının üstüne taşınıyordu.
     */
    private fun looksLikeOurs(read: String, translation: String, source: String): Boolean {
        fun words(text: String) = text.split(' ', '\n')
            .map { word -> word.filter { it.isLetter() } }
            .filter { it.length >= 3 && it[0].isLowerCase() }
            .toSet()
        val readWords = words(read)
        val ours = words(translation) - words(source)
        if (readWords.size < 2 || ours.isEmpty()) return false
        return readWords.count { it in ours } >= readWords.size * 0.8f
    }

    /**
     * "Tüm ekran" paylaşılıyor: katmanlar yakalamada gizlenir (OCR kendi çevirisini okumasın). Gizli katman
     * yakalamada siyah göründüğü için çeviriler altyazının üstüne değil hemen yanına alınır. Bu durumda ekran
     * yansıtmada (Smart View) çeviriler görünmez; kullanıcıya "tek uygulama" seçmesi söylenir.
     */
    private fun switchToHiddenOverlay() {
        hideFromCapture = true
        coverText.value = false
        for (i in 0 until MAX_AREAS) translations[i] = null
        stabilizers.forEach { it.reset() }
        applyWindowFlags()
        android.widget.Toast.makeText(
            overlayContext,
            "Çeviriler altyazının yanında gösterilecek. Tam üstünde ve ekran yansıtmada görmek için ekran paylaşımında \"Tek uygulama\" seçin.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun overlayFlags(editing: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Oyun sırasında dokunulmaz: dokunuşlar oyuna gider
        if (!editing) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (hideFromCapture) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        // Katman telefonun ekranındaysa telefonun ekranını açık tutar (bkz. keepPhoneScreenOn)
        if (overlayContext === this) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return flags
    }

    private fun bubbleFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (hideFromCapture) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        return flags
    }

    private fun applyWindowFlags() {
        overlayParams?.let { params ->
            params.flags = overlayFlags(isEditing.value)
            overlayView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }
        bubbleParams?.let { params ->
            params.flags = bubbleFlags()
            bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
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
        ComposeView(overlayContext).apply {
            setViewTreeLifecycleOwner(this@PhoneScreenService)
            setViewTreeSavedStateRegistryOwner(this@PhoneScreenService)
            setContent(content)
        }

    /**
     * Telefonun ekranı kapanınca Android ekran paylaşımını duraklatıyor (logda: görüntü gelmesi duruyor, çeviri
     * kesiliyor; DeX'te telefonun ışığı sönünce de). Mod çalıştığı sürece telefonun ekranı açık tutulur. Katman telefonun
     * ekranındaysa onun bayrağı yeter; DeX'te katman TV'deyken telefonun ekranına görünmez 1x1 bir pencere konur.
     * Güç düğmesiyle elle kapatılan ekran engellenemez.
     */
    private fun keepPhoneScreenOn() {
        if (overlayContext === this) {
            overlayParams?.let { params ->
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                overlayView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            }
            return
        }
        val phoneWindows = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = android.view.View(this)
        val params = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            phoneWindows.addView(view, params)
            keepAwakeView = view
        }
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            overlayFlags(editing = false),
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
            bubbleFlags(),
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
        applyWindowFlags()
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

    fun minFontSize(): Float = settings.getPhoneMinFontSize()

    /** Çevirinin geçiş süresi; yumuşak geçiş kapalıysa 0 (hemen çıkar). */
    fun transitionMs(): Int = if (settings.getPhoneSoftTransition()) settings.getPhoneTransitionMs() else 0

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
                NotificationChannel(CHANNEL_ID, "Ekran Çevirisi", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Ekran Çevirisi çalışıyor")
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
        /** Okumalar arası bekleme. 350 ms iken okuma başına ~0,5 sn, yazıdan çeviriye ~1,1 sn gidiyordu (logdan) */
        private const val FRAME_INTERVAL_MS = 120L
        private const val HIDE_AFTER_MS = 5000L
        private const val RECENT_READS = 12
        /** Kelimelerin bu orandan azı gerçek kelimeyse okuma bozuk sayılır (en az 4 kelimede) */
        private const val MIN_REAL_WORD_RATIO = 0.6f
        /** Altyazının ana satırıyla arasında bundan fazla boşluk olan satır altyazıya ait sayılmaz (satır yüksekliğine oran) */
        private const val MAX_LINE_GAP_RATIO = 1.5f
        /** İkinci okuma normal okumanın harflerinin en az bu kadarını içermeli */
        private const val MIN_RETRY_LETTER_RATIO = 0.8f
        /** Bu orandan az sözlük kelimesi varsa aynı kare, yazı öne çıkarılıp ikinci kez okunur */
        private const val READ_AGAIN_RATIO = 0.75f
        /** Yazı bulunamayan alanın ikinci okuma için en düşük ortalama parlaklığı (0-255) */
        private const val BRIGHT_AREA_LEVEL = 150L
        /** İkinci okumada görüntünün en parlak bu kadarı korunur: iki satır altyazı yaklaşık %3-6 yer kaplar */
        private const val BOOST_KEEP_RATIO = 0.08f
        private const val STALL_LOG_MS = 10_000L
        private const val CHANNEL_ID = "phone_screen_translation"
        private const val NOTIFICATION_ID = 7301
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_DISPLAY_ID = "display_id"
        private const val ACTION_STOP = "com.example.cameratranslator.STOP_PHONE_SCREEN"


        private val _running = MutableStateFlow(false)
        /** Telefon ekranı modu çalışıyor mu (ana ekrandaki Başlat/Durdur için) */
        val running: StateFlow<Boolean> = _running

        /** Bu telefonda kullanılabilir mi (Android 10+) */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** [displayId]: Başlat'a basılan ekran; çeviriler orada gösterilir (DeX'te TV) */
        fun start(context: Context, resultCode: Int, data: Intent, displayId: Int) {
            val intent = Intent(context, PhoneScreenService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PhoneScreenService::class.java).setAction(ACTION_STOP))
        }
    }
}
