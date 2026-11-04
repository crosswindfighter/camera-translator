package com.example.cameratranslator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** OPUS motorunun durumu: duraklatılmış kamera ekranında gösterilir. */
sealed class OpusNotice {
    /** [error]: önceki indirme başarısız olduysa nedeni */
    data class NotInstalled(val error: String? = null) : OpusNotice()
    data class Downloading(val progress: Float) : OpusNotice()
    object Verifying : OpusNotice()
}

/** OPUS panelindeki düğmeler */
class OpusNoticeActions(
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onLater: () -> Unit = {}
)

/**
 * Paneli OCR alanına bindirmeden, çizgisine yaslı yerleştirir: alanın üstünde ya da altında, hangisinde daha çok yer
 * varsa orada durur ve OCR taşınınca onunla yer değiştirir. Üstte üst çubuğa binmesin diye pay bırakılır; sığmazsa
 * içeriği kaydırılır.
 * [ocrRect] ekran koordinatlarında; [areaTop] bu bileşenin ekranın tepesinden uzaklığı.
 */
@Composable
fun OpusNoticeClearOfOcr(notice: OpusNotice, actions: OpusNoticeActions, ocrRect: Rect, areaTop: Dp) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val ocrTop = with(density) { ocrRect.top.toDp() } - areaTop
        val ocrBottom = with(density) { ocrRect.bottom.toDp() } - areaTop
        val above = (ocrTop - OCR_GAP - TOP_BAR_CLEARANCE).coerceIn(0.dp, maxHeight)
        val below = (maxHeight - ocrBottom - OCR_GAP).coerceIn(0.dp, maxHeight)
        val useAbove = above >= below
        when {
            // OCR alanı neredeyse tüm ekransa binmemek mümkün değil: bütün alan kullanılır
            maxOf(above, below) < MIN_NOTICE_HEIGHT -> OpusModelNotice(notice, actions)
            useAbove -> Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = TOP_BAR_CLEARANCE)
                    .fillMaxWidth()
                    .height(above)
            ) {
                OpusModelNotice(notice, actions, Alignment.BottomCenter)
            }
            else -> Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(below)
            ) {
                OpusModelNotice(notice, actions, Alignment.TopCenter)
            }
        }
    }
}

/** Panel ile OCR çizgisi arasındaki boşluk */
private val OCR_GAP = 4.dp
private val TOP_BAR_CLEARANCE = 12.dp
private val MIN_NOTICE_HEIGHT = 140.dp

/** Kamera görüntüsünün üstünde okunabilsin diye koyu panelde durur; düğmeleri dışında dokunmaları engellemez. */
@Composable
fun OpusModelNotice(notice: OpusNotice, actions: OpusNoticeActions, alignment: Alignment = Alignment.Center) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .background(Color(0xE60B0E14), shape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .padding(12.dp)
        ) {
            when (notice) {
                is OpusNotice.NotInstalled -> NotInstalledContent(notice.error, actions)
                is OpusNotice.Downloading -> ProgressContent("OPUS indiriliyor", notice.progress, actions.onCancel)
                is OpusNotice.Verifying -> ProgressContent("OPUS doğrulanıyor", progress = null, onCancel = null)
            }
        }
    }
}

@Composable
private fun NotInstalledContent(error: String?, actions: OpusNoticeActions) {
    val context = LocalContext.current
    var showMeteredDialog by remember { mutableStateOf(false) }
    IntroLayout(
        modifier = Modifier.fillMaxWidth(),
        header = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IntroHero(Icons.Rounded.CloudDownload, "OPUS motoru")
                Text(
                    text = "İnternetsiz çeviri için\ntek seferlik indirin",
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.78f),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        },
        steps = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IntroCard(Icons.Rounded.CloudDownload, "Tek seferlik indirme", "355 MB. İndirene kadar standart çevrimdışı motor kullanılır.")
                IntroCard(Icons.Rounded.WifiOff, "İnternetsiz çalışır", "İndirdikten sonra çeviri için internet gerekmez.")
            }
        },
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (error != null) {
                    Text(
                        text = "İndirilemedi: $error",
                        color = Color(0xFFEF9A9A),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DownloadButton(
                        label = if (error != null) "Tekrar dene" else "Şimdi indir",
                        modifier = Modifier.weight(1f)
                    ) {
                        // Ayarlar'daki gibi: mobil veride 355 MB için önce sorulur
                        if (OfflineModelDownloader.isOnMeteredNetwork(context)) showMeteredDialog = true else actions.onDownload()
                    }
                    TextButton(onClick = actions.onLater) {
                        Text("Daha sonra", color = Color.White.copy(alpha = 0.78f), fontFamily = FontFamily.SansSerif, fontSize = 14.sp)
                    }
                }
            }
        }
    )
    if (showMeteredDialog) {
        val totalMb = OfflineModelDownloader.totalBytes / 1_000_000
        AlertDialog(
            onDismissRequest = { showMeteredDialog = false },
            title = { Text("Mobil veri") },
            text = { Text("Wi-Fi'a bağlı değilsiniz. Model $totalMb MB; mobil veriyle indirmek istiyor musunuz?") },
            confirmButton = {
                TextButton(onClick = {
                    showMeteredDialog = false
                    actions.onDownload()
                }) { Text("İndir") }
            },
            dismissButton = { TextButton(onClick = { showMeteredDialog = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun DownloadButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(INTRO_CYAN, INTRO_BLUE)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            color = Color.White,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** [progress] null ise süresi belirsiz çubuk gösterilir; [onCancel] null ise iptal düğmesi yok. */
@Composable
private fun ProgressContent(title: String, progress: Float?, onCancel: (() -> Unit)?) {
    IntroLayout(
        modifier = Modifier.fillMaxWidth(),
        header = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IntroHero(if (progress != null) Icons.Rounded.CloudDownload else Icons.Rounded.Verified, title)
            }
        },
        steps = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, color = INTRO_CYAN, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "%${(progress * 100).toInt()}",
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                } else {
                    LinearProgressIndicator(color = INTRO_CYAN, modifier = Modifier.fillMaxWidth())
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel) {
                        Text("İptal", color = Color.White.copy(alpha = 0.78f), fontFamily = FontFamily.SansSerif, fontSize = 14.sp)
                    }
                }
            }
        }
    )
}
