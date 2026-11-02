package com.example.cameratranslator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** İlk açılış rehberinin hedefleri: her adım ana ekranda neyi parlak gösterecek. */
enum class GuideTarget { NONE, OCR_AREA, START, EYE, MENU }

private data class GuideStep(val title: String, val text: String, val target: GuideTarget)

private val STEPS = listOf(
    GuideStep(
        "Telefonu yerleştirin",
        "Telefonu TV'ye bakacak şekilde sabit bir yere koyun. Soldaki kaydırıcıyla altyazı net görünene kadar yakınlaştırın.",
        GuideTarget.NONE
    ),
    GuideStep(
        "OCR alanı",
        "Bu çerçeveyi altyazının üstüne sürükleyin, sağ alt köşesinden boyutunu ayarlayın. Yalnızca altyazıyı kapsarsa en doğru sonucu alırsınız.",
        GuideTarget.OCR_AREA
    ),
    GuideStep(
        "Başlat",
        "Başlat'a basın. Altyazılar okunup Türkçeye çevrilir.",
        GuideTarget.START
    ),
    GuideStep(
        "Göz modu",
        "Göz simgesine basarsanız düğmeler gizlenir, ekranda yalnızca çeviri kalır. Tekrar basınca geri gelir.",
        GuideTarget.EYE
    ),
    GuideStep(
        "Ayarlar",
        "Ekran Çevirisi modu, yazı boyutu, sesli okuma ve internetsiz OPUS motoru buradaki Ayarlar'da. İpucu: oyunda altyazı " +
            "arka planını (siyah kutu) açarsanız okuma çok daha doğru olur.",
        GuideTarget.MENU
    )
)

/** Rehberdeki adım sayısı */
val GUIDE_STEP_COUNT = STEPS.size

/** [step] numaralı adımın parlak gösterilecek hedefi */
fun guideTargetOf(step: Int): GuideTarget = STEPS.getOrNull(step)?.target ?: GuideTarget.NONE

/**
 * İlk açılış rehberi: ekran kararır, anlatılan düğme/alan ([target], ekran koordinatı) parlak kalır,
 * ekranın öbür yarısında kısa açıklama kartı çıkar. Rehber açıkken alttaki düğmelere dokunulmaz.
 */
@Composable
fun BoxScope.OnboardingGuide(
    step: Int,
    target: Rect?,
    containerHeight: Float,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val current = STEPS.getOrNull(step) ?: return
    val accent = Color(0xFF76FF03)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dokunmalar alttaki kamera ekranına geçmesin
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            with(drawContext.canvas.nativeCanvas) {
                val checkPoint = saveLayer(null, null)
                drawRect(Color.Black.copy(alpha = 0.72f))
                if (target != null) {
                    val pad = 8.dp.toPx()
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(target.left - pad, target.top - pad),
                        size = Size(target.width + pad * 2, target.height + pad * 2),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        blendMode = BlendMode.Clear
                    )
                }
                restoreToCount(checkPoint)
            }
            if (target != null) {
                val pad = 8.dp.toPx()
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(target.left - pad, target.top - pad),
                    size = Size(target.width + pad * 2, target.height + pad * 2),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = DrawStroke(width = 2.dp.toPx())
                )
            }
        }

        // Kart, hedefin olmadığı yarıda durur (hedefi örtmesin)
        val cardAlignment = when {
            target == null -> Alignment.Center
            target.center.y > containerHeight / 2f -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }
        Column(
            modifier = Modifier
                .align(cardAlignment)
                .padding(horizontal = 24.dp, vertical = 40.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("${step + 1} / ${STEPS.size}", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(current.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(current.text, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (step < STEPS.lastIndex) {
                    TextButton(onClick = onSkip) { Text("Atla", color = Color.White.copy(alpha = 0.7f)) }
                }
                Box(modifier = Modifier.weight(1f))
                Button(onClick = onNext) { Text(if (step < STEPS.lastIndex) "İleri" else "Başlayalım") }
            }
        }
    }
}
