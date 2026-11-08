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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
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
enum class GuideTarget { NONE, ZOOM, SUN, FOCUS, OCR_AREA, DIRECTION, ENGINE, START, EYE, MODE, MENU }

private data class GuideStep(val title: String, val text: String, val target: GuideTarget)

private val STEPS = listOf(
    GuideStep(
        "Telefonu yerleştirin",
        "Telefonu TV'ye bakacak şekilde sabit bir yere koyun (tripod ya da bir destek idealdir). Birkaç adımda uygulamayı tanıtalım.",
        GuideTarget.NONE
    ),
    GuideStep(
        "Yakınlaştırma",
        "Buradan yakınlaştırın. Altyazı ekranda büyük ve net görünene kadar yaklaşın; sınırlar telefonunuzun kamerasına göre " +
            "ayarlanır. Kaydırıcı yerine sabit düğmeler isterseniz Ayarlar'dan değiştirebilirsiniz.",
        GuideTarget.ZOOM
    ),
    GuideStep(
        "Parlaklık",
        "TV ekranı kameraya genelde fazla parlak gelir. Parlaklığı kıstıkça yazılar daha net okunur. Kaydırıcının alt " +
            "kısmındaki mor bölge ekstra karartmadır; çok parlak sahnelerde ya da yazılar hâlâ dağılıyorsa oraya inin.",
        GuideTarget.SUN
    ),
    GuideStep(
        "Odak",
        "Uygulama açılışta ve yakınlaştırdıktan sonra kendiliğinden netler ve odağı kilitler. Yazı bulanıksa buradan elle " +
            "odaklayabilir ya da OCR alanına dokunarak yeniden netleyebilirsiniz.",
        GuideTarget.FOCUS
    ),
    GuideStep(
        "OCR alanı",
        "Bu çerçeveyi altyazının üstüne sürükleyin, sağ alt köşesinden boyutunu ayarlayın. Yalnızca altyazıyı kapsarsa en doğru sonucu alırsınız.",
        GuideTarget.OCR_AREA
    ),
    GuideStep(
        "Dil yönü",
        "EN → TR İngilizce altyazıyı Türkçeye çevirir. Dokunarak TR → EN yönüne geçebilirsiniz.",
        GuideTarget.DIRECTION
    ),
    GuideStep(
        "Çeviri motoru",
        "Dokunarak çeviri motorunu değiştirin. OPUS internetsiz ve ücretsiz çalışır (Ayarlar'dan bir kez indirilir), " +
            "Google Translate internet ister, Google Cloud ise kendi API anahtarınızla çalışır.",
        GuideTarget.ENGINE
    ),
    GuideStep(
        "Başlat",
        "Başlat'a basın. Altyazılar okunup çevrilir.",
        GuideTarget.START
    ),
    GuideStep(
        "Göz modu",
        "Göz simgesine basarsanız düğmeler gizlenir, ekranda yalnızca çeviri kalır. Tekrar basınca geri gelir.",
        GuideTarget.EYE
    ),
    GuideStep(
        "Görünüm modu",
        "Bu düğmeye basınca küçük bir menü açılır: kamerayla çeviride çevirinin telefon ekranında mı (Sinematik Altyazı) yoksa " +
            "TV'deki yazının tam üstünde mi (Yerinde Çeviri) gösterileceğini seçer ya da Ekran Çevirisi'ne geçersiniz. " +
            "Modların ne işe yaradığını Ayarlar > Görünüm Modu'nda okuyabilirsiniz.",
        GuideTarget.MODE
    ),
    GuideStep(
        "Ayarlar",
        "Görünüm modları (Sinematik Altyazı, Yerinde Çeviri ve telefonun kendi ekranını çeviren Ekran Çevirisi), yazı boyutu, sesli okuma ve OPUS motoru buradaki Ayarlar'da. İpucu: oyunda altyazı " +
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
    onBack: () -> Unit,
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
        // Kartın iki yanında oklar: yanlışlıkla geçilen adıma geri dönülebilsin
        Row(
            modifier = Modifier
                .align(cardAlignment)
                .padding(horizontal = 8.dp, vertical = 40.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GuideArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Önceki adım", enabled = step > 0, onClick = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
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
            GuideArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Sonraki adım", enabled = true, onClick = onNext)
        }
    }
}

/** Rehber kartının yanındaki yuvarlak ok düğmesi; ilk adımda geri oku soluk ve pasif */
@Composable
private fun GuideArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(40.dp)
            .background(Color(0xFF1E1E1E).copy(alpha = if (enabled) 0.9f else 0.4f), CircleShape)
    ) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f))
    }
}
