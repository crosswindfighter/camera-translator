package com.example.cameratranslator

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Ekran çevirisinde ek OCR alanı (2. ve 3. alan) için ekranda gösterilecek bilgiler. */
data class ExtraAreaUi(
    val initialPortrait: FloatArray,
    val initialLandscape: FloatArray,
    val translation: String,
    /** Çevrilen İngilizce yazının alan içindeki yeri (0..1); null = kutu gizli */
    val bounds: RectF?,
    val lineHeight: Float
)

private val AREA_COLOR = Color(0xFF00E5FF)

/** coerceIn gibi; kutu alanla aynı boydayken küsurat farkıyla aralık ters dönerse (çöküyordu) alt sınır. */
private fun clampSafe(value: Float, min: Float, max: Float): Float = if (max <= min) min else value.coerceIn(min, max)

/**
 * Ekran çevirisi kutusu: siyah zemin, beyaz yazı; İngilizce yazının tam üstüne oturup onu örter.
 * Yazı boyutu ayardan değil ekrandaki İngilizce satırın yüksekliğinden gelir, sığmazsa küçülür.
 */
@Composable
fun ScreenTranslationBox(ocrRect: Rect, bounds: RectF, lineHeight: Float, text: String) {
    val density = LocalDensity.current
    // Satır kutusunun ~%75'i harf boyutudur
    val lineHeightPx = lineHeight * ocrRect.height
    val autoFontSize = if (lineHeightPx > 0f) {
        (lineHeightPx * 0.75f / (density.density * density.fontScale)).coerceIn(12f, 60f)
    } else 24f
    val pad = with(density) { 8.dp.toPx() }
    val padX = with(density) { 8.dp.toPx() } * 2
    val padY = with(density) { 4.dp.toPx() } * 2
    val centerX = ocrRect.left + (bounds.left + bounds.right) / 2f * ocrRect.width
    val centerY = ocrRect.top + (bounds.top + bounds.bottom) / 2f * ocrRect.height
    // Türkçe genelde daha uzun: kutu en az OCR alanının yarısı genişliğinde, en az 40 dp yüksekliğinde
    var boxWidth = (bounds.width() * ocrRect.width + pad * 2)
        .coerceAtLeast(ocrRect.width * 0.5f).coerceAtMost(ocrRect.width)
    var boxHeight = (bounds.height() * ocrRect.height + pad * 2).coerceAtLeast(with(density) { 40.dp.toPx() })
    // Çeviri sığmıyorsa kutu büyür: önce alanın tüm genişliğine, sonra aşağı-yukarı alanın yüksekliğine.
    // Alan çok darsa kutu alanın dışına taşar ama cümle yarım kalmaz (en son yazı küçülür).
    // Kutunun boyu, içindeki yazının ölçüsünden hesaplanır (yazı aynı stil ve genişlikle çizilir):
    // çeviri sığmıyorsa kutu önce alanın tüm genişliğine yayılır, sonra aşağı doğru uzar; ancak alanın
    // (ya da İngilizce yazının 3 katının) boyunu aşacaksa yazı küçülür. Cümle hiçbir zaman kesilmez.
    val measurer = rememberTextMeasurer()
    fun style(size: Float) = TextStyle(
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = (size * 1.15f).sp,
        color = Color.White
    )
    fun textHeight(width: Float, size: Float): Float = measurer.measure(
        text = text,
        style = style(size),
        constraints = Constraints(maxWidth = (width - padX).roundToInt().coerceAtLeast(1))
    ).size.height.toFloat()
    var fontSize = autoFontSize
    if (textHeight(boxWidth, fontSize) + padY > boxHeight) boxWidth = ocrRect.width
    val maxHeight = maxOf(ocrRect.height, boxHeight * 3f)
    var measured = textHeight(boxWidth, fontSize)
    while (measured + padY > maxHeight && fontSize > 12f) {
        fontSize -= 1f
        measured = textHeight(boxWidth, fontSize)
    }
    boxHeight = maxOf(boxHeight, measured + padY)
    val boxLeft = clampSafe(centerX - boxWidth / 2f, ocrRect.left, ocrRect.right - boxWidth)
    val boxTop = if (boxHeight <= ocrRect.height) {
        clampSafe(centerY - boxHeight / 2f, ocrRect.top, ocrRect.bottom - boxHeight)
    } else {
        (centerY - boxHeight / 2f).coerceAtLeast(0f)
    }
    Box(
        modifier = Modifier
            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
            .size(with(density) { boxWidth.toDp() }, with(density) { boxHeight.toDp() })
            .background(Color.Black, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = style(fontSize), modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Ek OCR alanı: köşesinde numarası olan ince çerçeve (göz modunda gizli), sürüklenerek taşınır,
 * sağ alt köşeden boyutlanır. Yeri dikey ve yatay için ayrı kaydedilir.
 */
@Composable
fun ExtraOcrArea(
    number: Int,
    area: ExtraAreaUi,
    isLandscape: Boolean,
    containerSize: Size,
    isStealthMode: Boolean,
    /** Alanın yeri değişti (OCR'a bildirilir) */
    onRectChange: (rect: RectF) -> Unit,
    /** Kullanıcı sürükleyip bıraktı: o yönün kaydına yazılır */
    onRectSave: (landscape: Boolean, rect: RectF) -> Unit
) {
    val density = LocalDensity.current
    val initial = if (isLandscape) area.initialLandscape else area.initialPortrait
    // rememberSaveable değil: telefon dönünce eski yönün konumu yeni yönün kaydına yazılıyordu
    var left by remember(number, isLandscape) { mutableFloatStateOf(initial[0]) }
    var top by remember(number, isLandscape) { mutableFloatStateOf(initial[1]) }
    var right by remember(number, isLandscape) { mutableFloatStateOf(initial[2]) }
    var bottom by remember(number, isLandscape) { mutableFloatStateOf(initial[3]) }

    LaunchedEffect(left, top, right, bottom) {
        onRectChange(RectF(left, top, right, bottom))
    }
    val save = { onRectSave(isLandscape, RectF(left, top, right, bottom)) }
    if (containerSize.width <= 0f || containerSize.height <= 0f) return

    val rect = Rect(
        left * containerSize.width, top * containerSize.height,
        right * containerSize.width, bottom * containerSize.height
    )

    if (area.bounds != null && area.translation.isNotEmpty()) {
        ScreenTranslationBox(rect, area.bounds, area.lineHeight, area.translation)
    }
    if (isStealthMode) return

    Box(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .border(1.dp, AREA_COLOR.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .pointerInput(containerSize, isLandscape) {
                detectDragGestures(onDragEnd = { save() }) { change, drag ->
                    change.consume()
                    val width = right - left
                    val height = bottom - top
                    val newLeft = clampSafe(left + drag.x / containerSize.width, 0f, 1f - width)
                    val newTop = clampSafe(top + drag.y / containerSize.height, 0f, 1f - height)
                    left = newLeft
                    top = newTop
                    right = newLeft + width
                    bottom = newTop + height
                }
            }
    ) {
        Text(
            text = number.toString(),
            color = AREA_COLOR,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.BottomEnd)
                .pointerInput(containerSize, isLandscape) {
                    detectDragGestures(onDragEnd = { save() }) { change, drag ->
                        change.consume()
                        right = clampSafe(right + drag.x / containerSize.width, left + 0.05f, 1f)
                        bottom = clampSafe(bottom + drag.y / containerSize.height, top + 0.02f, 1f)
                    }
                },
            contentAlignment = Alignment.BottomEnd
        ) {
            Text(
                text = "⇲",
                color = AREA_COLOR,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 2.dp, end = 4.dp)
            )
        }
    }
}
