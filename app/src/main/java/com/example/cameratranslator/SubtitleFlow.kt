package com.example.cameratranslator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sinematik altyazıda önceki çevirilerin akışı: açık (akıllı akış: OCR aşağıdaysa yukarı, yukarıdaysa aşağı akar)
 * ya da kapalı. Kayıtlı ayarla uyum için açık hâlin adı UP kaldı.
 */
enum class SubtitleFlow { UP, OFF }

private const val FLOW_ANIMATION_MS = 400
private const val HISTORY_FONT_SIZE = 16f

/**
 * Akan altyazı: [lines] eskiden yeniye sıralı, sonuncusu güncel çeviri. [flowUp] ise liste alttan dizilir
 * (güncel çeviri en altta, OCR alanının hemen üstünde), değilse üstten (güncel çeviri OCR alanının hemen altında).
 * Yeni çeviri OCR tarafından kayarak gelir; bir önceki çeviri aynı satır olarak kalır, küçülüp soluklaşarak
 * OCR'dan uzaklaşır. Eskidikçe daha da solar.
 */
@Composable
fun FlowingSubtitles(lines: List<String>, currentMaxFontSize: Float, flowUp: Boolean, modifier: Modifier = Modifier) {
    // Satırın kimliği metni (+ aynı metin kaçıncı kez geçiyor): güncel çeviri geçmişe geçince aynı satır kalır
    val keyed = remember(lines) {
        val seen = HashMap<String, Int>()
        lines.map { line ->
            val count = (seen[line] ?: 0) + 1
            seen[line] = count
            "$line#$count" to line
        }
    }
    BoxWithConstraints(modifier = modifier, contentAlignment = if (flowUp) Alignment.BottomCenter else Alignment.TopCenter) {
        // Güncel çeviri alanın en fazla ~%65'ini kaplasın (üstte önceki satırlara yer kalsın); sığmazsa küçülür
        val measurer = rememberTextMeasurer()
        val current = lines.lastOrNull().orEmpty()
        val widthPx = constraints.maxWidth
        val maxCurrentHeight = if (constraints.hasBoundedHeight) constraints.maxHeight * 0.65f else Float.MAX_VALUE
        val currentFontSize = remember(current, currentMaxFontSize, widthPx, maxCurrentHeight) {
            var size = currentMaxFontSize
            if (widthPx > 0 && current.isNotEmpty()) {
                while (size > 14f) {
                    val height = measurer.measure(
                        text = current,
                        style = flowStyle(size, true),
                        constraints = Constraints(maxWidth = widthPx)
                    ).size.height
                    if (height <= maxCurrentHeight) break
                    size -= 1f
                }
            }
            size
        }

        // Yükseklik sınırsız: Column satırları kalan yüksekliğe sığdırmaya çalışınca güncel çeviri kesiliyordu
        // (OCR alanının içine girmiş gibi). Fazlası OCR'ın öbür tarafından taşar ve dış kutunun solan kenarında kaybolur.
        val ordered = if (flowUp) keyed else keyed.reversed()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = if (flowUp) Alignment.Bottom else Alignment.Top, unbounded = true),
            verticalArrangement = if (flowUp) Arrangement.Bottom else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ordered.forEachIndexed { index, (id, text) ->
                val age = if (flowUp) ordered.size - 1 - index else index // 0 = güncel çeviri
                key(id) {
                    val isCurrent = age == 0
                    val fontSize by animateFloatAsState(
                        if (isCurrent) currentFontSize else HISTORY_FONT_SIZE, tween(FLOW_ANIMATION_MS), label = "flowFont"
                    )
                    val emphasis by animateFloatAsState(
                        if (isCurrent) 1f else (0.75f - 0.06f * (age - 1)).coerceAtLeast(0.3f), tween(FLOW_ANIMATION_MS),
                        label = "flowEmphasis"
                    )
                    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = visible,
                        // OCR tarafından kayarak gelir: yukarı akışta aşağıdan, aşağı akışta yukarıdan
                        enter = slideInVertically(tween(FLOW_ANIMATION_MS)) { if (flowUp) it / 2 else -it / 2 } +
                            expandVertically(
                                tween(FLOW_ANIMATION_MS),
                                expandFrom = if (flowUp) Alignment.Top else Alignment.Bottom
                            ) +
                            fadeIn(tween(FLOW_ANIMATION_MS))
                    ) {
                        Text(
                            text = text,
                            // Güncel: beyaz ve kalın; öncekiler gri ve eskidikçe soluk
                            color = lerp(Color.LightGray.copy(alpha = emphasis), Color.White, if (isCurrent) emphasis else 0f),
                            style = flowStyle(fontSize, isCurrent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (isCurrent) 4.dp else 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun flowStyle(size: Float, bold: Boolean) = TextStyle(
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = TextAlign.Center,
    lineHeight = (size * 1.15f).sp
)
