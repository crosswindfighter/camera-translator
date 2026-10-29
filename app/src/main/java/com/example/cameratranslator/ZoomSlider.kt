package com.example.cameratranslator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Kaydırıcının en yüksek yakınlaştırması (kamera daha azını destekliyorsa onunki). */
const val ZOOM_SLIDER_MAX = 20f

/** S23 Ultra'nın gerçek lensleri: bu değerlerde görüntü en net, kaydırıcı hafifçe yapışır. */
private val SNAP_POINTS = listOf(1f, 3f, 10f)
private const val SNAP_DISTANCE = 0.035f
private const val LABEL_VISIBLE_MS = 2000L

/**
 * Dikey yakınlaştırma kaydırıcısı (0.6x geniş açı – 20x). Ölçek logaritmik: 1x→2x ile
 * 10x→20x aynı mesafede, böylece yakın değerlerde hassas ayar yapılabilir. Üstteki değer
 * etiketi dokunurken görünür, bırakıldıktan sonra kaybolur.
 */
@Composable
fun ZoomSlider(
    zoomLevel: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val min = minZoom.coerceAtMost(1f)
    val max = maxZoom.coerceIn(1f, ZOOM_SLIDER_MAX)
    val currentZoom by rememberUpdatedState(zoomLevel)
    val onChange by rememberUpdatedState(onZoomChange)

    fun toPosition(zoom: Float): Float =
        if (max <= min) 0f else ((ln(zoom.coerceIn(min, max)) - ln(min)) / (ln(max) - ln(min))).coerceIn(0f, 1f)

    fun toZoom(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        SNAP_POINTS.firstOrNull { it in min..max && abs(toPosition(it) - p) < SNAP_DISTANCE }?.let { return it }
        // 0.1 adımlarla: kamera her küçük değişimde yeniden ayarlanmasın
        return (Math.round(exp(ln(min) + p * (ln(max) - ln(min))) * 10f) / 10f).coerceIn(min, max)
    }

    // Etiket: her dokunuşta/değişimde görünür, son değişimden LABEL_VISIBLE_MS sonra kaybolur
    var interaction by remember { mutableIntStateOf(0) }
    var showLabel by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        if (interaction == 0) return@LaunchedEffect
        showLabel = true
        delay(LABEL_VISIBLE_MS)
        showLabel = false
    }
    fun update(y: Float, trackHeight: Int) {
        val zoom = toZoom(1f - y / trackHeight.coerceAtLeast(1))
        if (zoom != currentZoom) onChange(zoom)
        interaction++
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Not: Column içindeki AnimatedVisibility ColumnScope sürümüyle çakışıyordu; saydamlık animasyonu yeterli
        val labelAlpha by animateFloatAsState(targetValue = if (showLabel) 1f else 0f, label = "zoomLabel")
        Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            Text(
                text = formatZoom(zoomLevel),
                color = Color.Yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .alpha(labelAlpha)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Box(
            modifier = Modifier
                .width(40.dp)
                .height(176.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(vertical = 14.dp)
                .pointerInput(min, max) {
                    detectTapGestures(onPress = { offset -> update(offset.y, size.height) })
                }
                .pointerInput(min, max) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        update(change.position.y, size.height)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(148.dp)) {
                val centerX = size.width / 2
                val trackWidth = 4.dp.toPx()
                val position = toPosition(zoomLevel)
                val thumbY = size.height * (1f - position)

                // Ray: dolu kısım (seçili yakınlaştırmaya kadar) sarı, kalanı soluk beyaz
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(centerX - trackWidth / 2, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2)
                )
                drawRoundRect(
                    color = Color.Yellow.copy(alpha = 0.85f),
                    topLeft = Offset(centerX - trackWidth / 2, thumbY),
                    size = Size(trackWidth, size.height - thumbY),
                    cornerRadius = CornerRadius(trackWidth / 2)
                )
                // Lens noktaları (1x, 3x, 10x)
                for (snap in SNAP_POINTS) {
                    if (snap !in min..max) continue
                    val y = size.height * (1f - toPosition(snap))
                    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = 2.5.dp.toPx(), center = Offset(centerX + 10.dp.toPx(), y))
                }
                // Tutamaç
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = Offset(centerX, thumbY))
                drawCircle(color = Color.Yellow, radius = 6.dp.toPx(), center = Offset(centerX, thumbY))
            }
        }
    }
}

fun formatZoom(zoom: Float): String =
    if (abs(zoom - Math.round(zoom)) < 0.05f) "${Math.round(zoom)}x" else String.format(Locale.US, "%.1fx", zoom)
