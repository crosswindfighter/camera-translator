package com.example.cameratranslator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * OCR kaynağı: ImageAnalysis frame'i değil, PreviewView bitmap'i.
 * Kullanıcının ekranda gördüğü görüntü = OCR'a giden görüntü (WYSIWYG).
 */
class TextRecognitionAnalyzer(
    private val onTextDetected: (String) -> Unit,
    private val onEmptyDetected: () -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastEmittedText = ""
    private var lastReading = ""
    private var stabilizationJob: Job? = null
    private var emptyFrameCount = 0

    private val ocrArea = AtomicReference<RectF?>(null)
    private val previewViewRef = AtomicReference<PreviewView?>(null)
    private val isPaused = AtomicBoolean(true)
    private val activeOcrTasks = AtomicInteger(0)
    private val frameCounter = AtomicLong(0)

    fun attachPreviewView(view: PreviewView) {
        previewViewRef.set(view)
        Log.d(TAG, "PreviewView attached ${view.width}x${view.height}")
    }

    fun setOcrArea(rect: RectF?, @Suppress("UNUSED_PARAMETER") viewWidth: Float, @Suppress("UNUSED_PARAMETER") viewHeight: Float) {
        ocrArea.set(rect)
    }

    fun setPaused(paused: Boolean) {
        val wasPaused = isPaused.getAndSet(paused)
        if (paused && !wasPaused) resetStabilization("pause")
        if (!paused && wasPaused) resetStabilization("resume")
    }

    private fun resetStabilization(reason: String) {
        stabilizationJob?.cancel()
        stabilizationJob = null
        lastEmittedText = ""
        lastReading = ""
        emptyFrameCount = 0
        Log.d(TAG, "Stabilization reset ($reason)")
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val frameId = frameCounter.incrementAndGet()
        // En fazla 2 eşzamanlı OCR işlemine izin ver
        if (isPaused.get() || activeOcrTasks.get() >= 2) {
            imageProxy.close()
            return
        }

        val area = ocrArea.get()
        if (area == null) {
            imageProxy.close()
            return
        }

        // ImageProxy'den ViewPort uyumlu (crop + rotation) bitmap al
        val previewBitmap = imageProxy.toBitmapSafe()
        imageProxy.close()

        if (previewBitmap == null) return

        activeOcrTasks.incrementAndGet()
        scope.launch {
            runOcr(previewBitmap, area, frameId)
        }
    }

    private suspend fun runOcr(previewBitmap: Bitmap, area: RectF, frameId: Long) {
        try {
            val cropRect = PreviewCoordinateMapper.mapNormalizedToBitmapRect(
                area,
                previewBitmap.width,
                previewBitmap.height
            )

            if (cropRect.width() < 30 || cropRect.height() < 12) {
                activeOcrTasks.decrementAndGet()
                previewBitmap.recycle()
                return
            }

            val cropped = Bitmap.createBitmap(
                previewBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
            previewBitmap.recycle()

            val ocrBitmap = upscaleForOcr(cropped)
            if (ocrBitmap !== cropped) cropped.recycle()

            val image = InputImage.fromBitmap(ocrBitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    if (frameId % 10 == 0L) Log.d(TAG, "Frame #$frameId OCR Result: \"$text\"")
                    if (text.isNotBlank()) handleOcrResult(text, frameId)
                    else handleEmptyResult(frameId)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Frame #$frameId ML Kit error", e)
                }
                .addOnCompleteListener {
                    activeOcrTasks.decrementAndGet()
                    ocrBitmap.recycle()
                }
        } catch (e: Exception) {
            activeOcrTasks.decrementAndGet()
            try {
                if (!previewBitmap.isRecycled) previewBitmap.recycle()
            } catch (_: Exception) {}
            Log.e(TAG, "Frame #$frameId error", e)
        }
    }

    private fun upscaleForOcr(source: Bitmap): Bitmap {
        val targetWidth = max(800, source.width * 2)
        val scale = targetWidth.toFloat() / source.width.toFloat()
        if (scale <= 1.1f) return source

        val width = (source.width * scale).roundToInt()
        val height = (source.height * scale).roundToInt()
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
        canvas.drawBitmap(source, Matrix().apply { postScale(scale, scale) }, paint)
        return scaled
    }

    private fun handleEmptyResult(frameId: Long) {
        emptyFrameCount++
        if (emptyFrameCount >= EMPTY_RESET_THRESHOLD) {
            lastEmittedText = ""
            lastReading = ""
            stabilizationJob?.cancel()
            stabilizationJob = null
            onEmptyDetected()
        }
    }

    private fun isGibberishWord(word: String): Boolean {
        val cleanWord = word.lowercase().filter { it.isLetter() }
        if (cleanWord.length < 3) return false

        // 1. Filter 3+ consecutive identical letters (e.g. "iii", "zzz", "lll")
        if (GIBBERISH_REPEAT_REGEX.containsMatchIn(cleanWord)) return true

        // 2. Filter 3+ consecutive unnatural vowels (e.g. "aiii", "eiii")
        if (GIBBERISH_VOWELS_REGEX.containsMatchIn(cleanWord)) return true

        // 3. Filter 4+ consecutive consonants (e.g. "xptz", "rtbn", "qwerty")
        if (GIBBERISH_CONSONANTS_REGEX.containsMatchIn(cleanWord)) return true

        return false
    }

    private fun filterOcrNoise(text: String): String {
        val words = text.replace(SPACES_REGEX, " ").trim().split(SPACES_REGEX)
        val filteredWords = words.filter { word ->
            // 1. Filter out words containing 2 OR MORE CONSECUTIVE UPPERCASE letters (e.g. PA, LA, OK, MENU, SETTINGS, ANKARA)
            val has2PlusCaps = TWO_PLUS_CAPS_REGEX.containsMatchIn(word)

            // 2. Filter out alphanumeric noise words where letters and numbers are directly attached (e.g. A10, A1, A2, A5000, 100X, B12)
            val hasAlphanumericNoise = ALPHANUMERIC_NOISE_REGEX.containsMatchIn(word)

            // 3. Filter out unpronounceable OCR artifacts and gibberish noise words
            val isGibberish = isGibberishWord(word)

            !has2PlusCaps && !hasAlphanumericNoise && !isGibberish
        }
        return filteredWords.joinToString(" ").trim()
    }

    private fun compareKey(text: String): String {
        return text.lowercase()
            .replace(SPACES_REGEX, " ")
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), "")
            .trim()
    }

    private fun isSimilarText(a: String, b: String): Boolean {
        val keyA = compareKey(a)
        val keyB = compareKey(b)
        if (keyA == keyB) return true
        if (keyA.isEmpty() || keyB.isEmpty()) return false

        val shorter = if (keyA.length <= keyB.length) keyA else keyB
        val longer = if (keyA.length <= keyB.length) keyB else keyA
        if (longer.contains(shorter) && shorter.length.toFloat() / longer.length >= 0.75f) {
            return true
        }
        return false
    }

    private fun handleOcrResult(text: String, frameId: Long) {
        if (isPaused.get()) {
            Log.d(TAG, "Frame #$frameId ignored because analyzer is paused.")
            return
        }
        emptyFrameCount = 0

        // Filter out ALL-CAPS words (2+ caps), Alphanumeric noise tokens (e.g. A10, A1), and Gibberish OCR artifacts
        val cleaned = filterOcrNoise(text)

        if (!isPlausibleSubtitle(cleaned)) {
            Log.d(TAG, "Frame #$frameId rejected: \"$cleaned\" (original: \"$text\")")
            return
        }

        // DEDUPLICATION FIX: Ignore minor punctuation/space/case variations across consecutive frames!
        if (isSimilarText(cleaned, lastReading) || isSimilarText(cleaned, lastEmittedText)) {
            return
        }

        lastReading = cleaned
        lastEmittedText = cleaned
        Log.d(TAG, "Frame #$frameId emitting: \"$cleaned\"")
        onTextDetected(cleaned)
    }

    private fun isPlausibleSubtitle(text: String): Boolean {
        if (text.length < 4) return false

        val letters = text.count { it.isLetter() }
        if (letters < text.length * 0.45f) return false
        if (text.count { it.isDigit() } > text.length * 0.15f) return false
        if (REJECT_PATTERN.containsMatchIn(text)) return false

        val words = text.split(SPACES_REGEX).filter { it.isNotBlank() }
        if (text.length > 15 && words.size < 2) return false

        var validWords = 0
        for (word in words) {
            val wordLetters = word.filter { it.isLetter() }
            if (wordLetters.length < 2) continue

            val vowels = wordLetters.count { it.lowercaseChar() in "aeiouıöü" }
            val vowelRatio = vowels.toFloat() / wordLetters.length
            if (vowelRatio !in 0.15f..0.75f) continue

            val upperRatio = wordLetters.count { it.isUpperCase() }.toFloat() / wordLetters.length
            if (wordLetters.length > 4 && upperRatio > 0.7f) continue
            if (CAMEL_CASE_PATTERN.containsMatchIn(word)) continue

            validWords++
        }

        return validWords >= 1
    }

    companion object {
        private const val TAG = "OCR_ANALYZER"
        private const val EMPTY_RESET_THRESHOLD = 3
        private const val MAX_CONCURRENT_OCR = 2

        private val SPACES_REGEX = Regex("\\s+")
        private val REJECT_PATTERN = Regex("[<>]{1,}|_{2,}|={2,}")
        private val CAMEL_CASE_PATTERN = Regex("[a-z][A-Z]{2,}[a-z]")

        // Regex matching words with 2 or more consecutive uppercase letters (e.g. LA, OK, PA, MENU, SETTINGS, PAUSE, ANKARA)
        private val TWO_PLUS_CAPS_REGEX = Regex("[A-ZÇĞİÖŞÜ]{2,}")

        // Regex matching alphanumeric noise tokens where letters and digits are directly concatenated (e.g. A10, A1, A2, A5000, 100X, B12)
        private val ALPHANUMERIC_NOISE_REGEX = Regex("(?=[A-Za-zÇĞİÖŞÜçğıöşü]*\\d)(?=\\d*[A-Za-zÇĞİÖŞÜçğıöşü])[A-Za-zÇĞİÖŞÜçğıöşü0-9-]+")

        // Gibberish artifact regexes
        private val GIBBERISH_REPEAT_REGEX = Regex("(.)\\1{2,}")
        private val GIBBERISH_VOWELS_REGEX = Regex("[aeiouıöü]{3,}")
        private val GIBBERISH_CONSONANTS_REGEX = Regex("[bcdfghjklmnprstvwxyzçğş]{4,}")
    }
}
