package com.example.cameratranslator

import android.util.Size
import android.util.Log
import android.view.ViewGroup
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.content.Context
import android.os.Build
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
                    context.display?.rotation ?: Surface.ROTATION_0
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
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
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
            
            // İlk değerleri uygula
            camera.cameraControl.setZoomRatio(zoomLevel)
            camera.cameraControl.setExposureCompensationIndex(exposureIndex)
            
            cameraInstance = camera
            onCameraReady(camera)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Kamera Ayarlarını (Zoom ve Pozlama) Senkronize Et
    LaunchedEffect(cameraInstance, zoomLevel, exposureIndex) {
        val camera = cameraInstance ?: return@LaunchedEffect
        
        // 1. Değerler her değiştiğinde anında uygula
        camera.cameraControl.setZoomRatio(zoomLevel)
        camera.cameraControl.setExposureCompensationIndex(exposureIndex)
        
        // 2. Uygulama her foreground olduğunda (Resume) ayarları tazelemek için
        // Bazı cihazlarda donanım seviyesindeki resetlemeyi önler.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // repeatOnLifecycle her Resume olduğunda bu bloğu tetikler
            delay(200) // Donanımın hazır olması için kısa bir tolerans
            camera.cameraControl.setZoomRatio(zoomLevel)
            camera.cameraControl.setExposureCompensationIndex(exposureIndex)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
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
