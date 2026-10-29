package com.example.cameratranslator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Kaydırıcının en yüksek yakınlaştırması (kamera daha azını destekliyorsa onunki). */
const val ZOOM_SLIDER_MAX = 20f

/** S23 Ultra'nın gerçek lensleri: bu değerlerde görüntü en net, kaydırıcı görünmeden hafifçe yapışır. */
private val SNAP_POINTS = listOf(1f, 3f, 10f)
private const val SNAP_DISTANCE = 0.035f
private const val LABEL_VISIBLE_MS = 2000L
/** Sürüklerken kameraya en fazla ~25 kez/sn zoom gönder (her dokunma olayında değil). */
private const val SEND_INTERVAL_MS = 40L

/**
 * Dikey yakınlaştırma kaydırıcısı (geniş açı – kameranın desteklediği en yüksek değer, en fazla 20x). Ölçek logaritmik: 1x→2x ile
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
        return (Math.round(exp(ln(min) + p * (ln(max) - ln(min))) * 100f) / 100f).coerceIn(min, max)
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
    // Dokunurken tutamaç ve etiket parmağı anında takip eder (ana ekranın yeniden çizilmesini beklemez);
    // kameraya giden değer ise SEND_INTERVAL_MS'de bir gönderilir, bırakınca son değer kesin gönderilir
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var dragZoom by remember { mutableStateOf<Float?>(null) }
    val lastSentAt = remember { longArrayOf(0L) }

    fun update(y: Float, trackHeight: Int, final: Boolean) {
        val raw = (1f - y / trackHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        val zoom = toZoom(raw)
        // Lens noktasına yapışınca tutamaç da oraya otursun
        dragPosition = if (zoom in SNAP_POINTS) toPosition(zoom) else raw
        dragZoom = zoom
        val now = SystemClock.uptimeMillis()
        if ((final || now - lastSentAt[0] >= SEND_INTERVAL_MS) && zoom != currentZoom) {
            lastSentAt[0] = now
            onChange(zoom)
        }
        interaction++
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Not: Column içindeki AnimatedVisibility ColumnScope sürümüyle çakışıyordu; saydamlık animasyonu yeterli
        val labelAlpha by animateFloatAsState(targetValue = if (showLabel) 1f else 0f, label = "zoomLabel")
        Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            Text(
                text = formatZoom(dragZoom ?: zoomLevel),
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
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .padding(vertical = 14.dp)
                .pointerInput(min, max) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var lastY = down.position.y
                        update(lastY, size.height, final = true)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            lastY = change.position.y
                            update(lastY, size.height, final = false)
                        }
                        update(lastY, size.height, final = true)
                        dragPosition = null
                        dragZoom = null
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(148.dp)) {
                val centerX = size.width / 2
                val trackWidth = 3.dp.toPx()
                val position = dragPosition ?: toPosition(zoomLevel)
                val thumbY = size.height * (1f - position)
                val thumbCenter = Offset(centerX, thumbY)

                // Ray: kalan kısım soluk beyaz, seçili yakınlaştırmaya kadar sarıdan turuncuya
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(centerX - trackWidth / 2, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2)
                )
                if (size.height - thumbY > 1f) drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFD54F), Color(0xFFFF9800)),
                        startY = thumbY,
                        endY = size.height
                    ),
                    topLeft = Offset(centerX - trackWidth / 2, thumbY),
                    size = Size(trackWidth, size.height - thumbY),
                    cornerRadius = CornerRadius(trackWidth / 2)
                )
                // Tutamaç: yumuşak gölge + beyaz daire + sarı merkez
                drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = thumbCenter + Offset(0f, 1.dp.toPx()))
                drawCircle(color = Color.White, radius = 10.dp.toPx(), center = thumbCenter)
                drawCircle(color = Color(0xFFFFC107), radius = 3.5.dp.toPx(), center = thumbCenter)
            }
        }
    }
}

fun formatZoom(zoom: Float): String =
    if (abs(zoom - Math.round(zoom)) < 0.05f) "${Math.round(zoom)}x" else String.format(Locale.US, "%.1fx", zoom)
