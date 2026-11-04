package com.example.cameratranslator

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val INTRO_CYAN = Color(0xFF00E5FF)
internal val INTRO_BLUE = Color(0xFF2979FF)
private val INTRO_GREEN = Color(0xFF69F0AE)
private val INTRO_GRADIENT = listOf(INTRO_CYAN, INTRO_BLUE)

/** Ekran Çevirisi modunun ana ekranı: mod bekliyorken nasıl başlatılacağını, çalışırken ne yapılacağını anlatır. */
@Composable
fun PhoneModeIntro(modifier: Modifier = Modifier) {
    val running by PhoneScreenService.running.collectAsStateWithLifecycle()
    IntroLayout(
        modifier = modifier.fillMaxSize(),
        header = { IntroHeader(running) },
        steps = { IntroSteps(running) }
    )
}

/** Yatayda solda başlık, sağda adımlar; dikeyde alt alta. Sığmazsa kaydırılır. */
@Composable
internal fun IntroLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    steps: @Composable () -> Unit,
    /** Adımların altında sabit duran kısım (düğmeler): içerik kaydırılsa da görünür kalır */
    footer: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val scroll = Modifier.verticalScroll(rememberScrollState())
        if (maxWidth > maxHeight && maxWidth >= 480.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(0.42f), contentAlignment = Alignment.Center) { header() }
                Column(modifier = Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f, fill = false).then(scroll)) { steps() }
                    footer?.invoke()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).then(scroll),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    header()
                    steps()
                }
                footer?.invoke()
            }
        }
    }
}

/** Degrade zeminli simge ve degrade başlık */
@Composable
internal fun IntroHero(icon: ImageVector, title: String) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .background(Brush.linearGradient(INTRO_GRADIENT), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
    }
    Text(
        text = title,
        textAlign = TextAlign.Center,
        style = TextStyle(
            brush = Brush.horizontalGradient(INTRO_GRADIENT),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            letterSpacing = (-0.5).sp
        )
    )
}

@Composable
private fun IntroHeader(running: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IntroHero(Icons.Rounded.Translate, "Ekran Çevirisi")
        if (running) {
            RunningBadge()
        } else {
            Text(
                text = "Kamera kullanmadan, oyununuzu\nTürkçe altyazıyla oynayın",
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.78f),
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun RunningBadge() {
    val pulse by rememberInfiniteTransition(label = "introPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "introPulseAlpha"
    )
    Row(
        modifier = Modifier
            .background(INTRO_GREEN.copy(alpha = 0.13f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse }
                .background(INTRO_GREEN, CircleShape)
        )
        Text("Çalışıyor", color = INTRO_GREEN, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun IntroSteps(running: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            IntroCard(Icons.Rounded.SportsEsports, "Oyununuza dönün", "Çeviriler oyunun üstünde görünecek.")
            IntroCard(
                Icons.Rounded.TouchApp,
                "Yüzen düğme",
                "OCR alanlarını düzenlemek ya da durdurmak için ekranın kenarındaki yüzen düğmeye dokunun."
            )
        } else {
            IntroCard(
                Icons.Rounded.PhoneAndroid,
                "Telefonda oynayın",
                "Telefon ekranındaki oyunları doğrudan çevirebilirsiniz. Telefondan Xbox veya PlayStation oyunlarınıza Remote Play ile bağlanıp Türkçe çeviriyle oynayabilirsiniz."
            )
            IntroCard(
                Icons.Rounded.Tv,
                "İsterseniz televizyonda oynayın",
                "Samsung telefonunuzu kabloyla televizyona bağlayıp DeX'te Xbox veya PlayStation Remote Play'i açın. Kablosuz DeX bu modla çalışmaz."
            )
            IntroCard(
                Icons.Rounded.TouchApp,
                "Başlat'a basın",
                "Ekran paylaşımında \"Tek uygulama\" seçip oyunu seçin. OCR alanlarını oyunun içinden, yüzen balonla yerleştirin."
            )
            IntroCard(
                Icons.Rounded.Subtitles,
                "Türkçe oynayın",
                "Çeviri doğrudan İngilizce altyazının üstüne gelir; telefonda da televizyonda da görünür."
            )
        }
    }
}

@Composable
internal fun IntroCard(icon: ImageVector, title: String, body: String) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(INTRO_CYAN.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = INTRO_CYAN, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                body,
                color = Color.White.copy(alpha = 0.72f),
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.5.sp,
                lineHeight = 17.sp
            )
        }
    }
}
