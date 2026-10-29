package com.example.cameratranslator

import android.util.Size
import android.util.Log
import android.view.ViewGroup
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.content.Context
import android.os.Build
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer,
    zoomLevel: Float = 1f,
    exposureIndex: Int = 0,
    manualFocusValue: Float = 0f,
    onCameraReady: (Camera) -> Unit = {},
    onPreviewViewReady: (PreviewView) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // OCR analyzer'ına dokunmadan: her kamera karesini iletmek PreviewView.getBitmap()
    // çağrılarını main thread'de kuyruklar ve TextureView preview'sini kilitler.
    val gatedAnalyzer = remember(analyzer) {
        MainThreadSafeAnalyzerGate(analyzer)
    }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }

    // Granular rotation takibi (özellikle 180 derece flip için)
    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display.rotation
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                }
                if (rotation != displayRotation) {
                    displayRotation = rotation
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(previewView) {
        onPreviewViewReady(previewView)
    }

    // Kamera kurulumu ve UseCase bağlama (Başlangıçta ve her 90/180 derece dönüşte çalışır)
    LaunchedEffect(displayRotation) {
        val cameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val rotation = displayRotation

        while (previewView.width == 0 || previewView.height == 0 || previewView.viewPort == null) {
            delay(16)
        }

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // 1080p: zoom yapılmış TV altyazısında harf kenarları 720p'ye göre belirgin şekilde daha net.
                        // 4K denendi: S23 Ultra önizlemeyle birlikte en fazla 1920x1440 veriyor (genişlik aynı), fayda yok.
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            // RGBA çıktısı: YUV -> JPEG -> Bitmap dönüşümündeki sıkıştırma bozulmaları ortadan kalkar.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, gatedAnalyzer) }

        try {
            cameraProvider.unbindAll()
            val viewPort = previewView.viewPort!!
            val useCaseGroup = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .build()
            
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            Log.d(TAG, "Analysis resolution: ${imageAnalysis.resolutionInfo?.resolution}")

            // İlk değerleri uygula
            camera.cameraControl.setZoomRatio(zoomLevel)
            camera.cameraControl.setExposureCompensationIndex(exposureIndex)
            
            cameraInstance = camera
            onCameraReady(camera)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Kamera Ayarlarını (Zoom, Pozlama ve Manuel Odak) Senkronize Et
    LaunchedEffect(cameraInstance, zoomLevel, exposureIndex, manualFocusValue) {
        val camera = cameraInstance ?: return@LaunchedEffect
        
        // 1. Değerler her değiştiğinde anında uygula
        camera.cameraControl.setZoomRatio(zoomLevel)
        camera.cameraControl.setExposureCompensationIndex(exposureIndex)
        applyManualFocus(camera, manualFocusValue)

        // 2. Uygulama her foreground olduğunda (Resume) ayarları tazelemek için
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(200)
            camera.cameraControl.setZoomRatio(zoomLevel)
            camera.cameraControl.setExposureCompensationIndex(exposureIndex)
            applyManualFocus(camera, manualFocusValue, force = true)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

/** Odak kaydırıcısının alt kısmı: bu değerin altı otomatik odak (AF). */
const val AUTO_FOCUS_ZONE = 0.08f

/** Telefon kameralarının yakın odak sınırı ~10 cm = 10 diyoptri. */
private const val MAX_FOCUS_DIOPTERS = 10f

private var lastAppliedFocus = -1f
private var lastFocusCamera: Camera? = null

private fun applyManualFocus(camera: Camera, focusValue: Float, force: Boolean = false) {
    // Kaydırma sırasında her küçük değişimde istek yağdırmamak için; ama kamera
    // yeniden bağlandıysa (Ayarlar'dan dönüş, döndürme) değer aynı olsa da uygula.
    if (!force && camera === lastFocusCamera && kotlin.math.abs(focusValue - lastAppliedFocus) < 0.004f) return
    lastAppliedFocus = focusValue
    lastFocusCamera = camera

    try {
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)
        if (focusValue < AUTO_FOCUS_ZONE) {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                .build()
            camera2Control.setCaptureRequestOptions(options)
        } else {
            // Karesel eğri: kaydırıcının büyük kısmı uzak mesafelere (TV: 2-4 m = 0.25-0.5 diyoptri)
            // ayrılır. Eskiden doğrusaldı ve TV mesafesi kaydırıcının en alttaki %3'üne sıkışıyordu.
            val t = ((focusValue - AUTO_FOCUS_ZONE) / (1f - AUTO_FOCUS_ZONE)).coerceIn(0f, 1f)
            val targetDiopters = MAX_FOCUS_DIOPTERS * t * t
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, targetDiopters)
                .build()
            camera2Control.setCaptureRequestOptions(options)
        }
    } catch (e: Exception) {
        Log.e("CameraPreview", "Manual focus error", e)
    }
}

/**
 * OCR sınıfını değiştirmeden ImageAnalysis karelerini seyreltir.
 * Çeviri açıkken PreviewView.getBitmap() her frame'de main thread'e basılmaz.
 */
private class MainThreadSafeAnalyzerGate(
    private val delegate: ImageAnalysis.Analyzer
) : ImageAnalysis.Analyzer {
    private val lastForwardedAt = AtomicLong(0)

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastForwardedAt.get()
        if (now - previous < MIN_FORWARD_INTERVAL_MS) {
            image.close()
            return
        }
        if (!lastForwardedAt.compareAndSet(previous, now)) {
            image.close()
            return
        }
        delegate.analyze(image)
    }

    companion object {
        private const val MIN_FORWARD_INTERVAL_MS = 150L
    }
}

private const val TAG = "CameraPreview"
