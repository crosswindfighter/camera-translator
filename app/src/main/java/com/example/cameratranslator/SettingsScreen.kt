package com.example.cameratranslator

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import com.example.cameratranslator.ui.theme.SectionCardStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
private fun SettingsBlockCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SectionCardStyle.cardColor(darkAlpha = 0.45f),
        border = SectionCardStyle.cardBorder(),
        shadowElevation = SectionCardStyle.cardShadow()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Bölüm içindeki bir ayar grubu: ince çizgiyle ayırmak yerine kendi çerçevesi içinde (daha derli toplu). */
@Composable
private fun SettingsSubCard(content: @Composable ColumnScope.() -> Unit) {
    val isLight = SectionCardStyle.isLight()
    // Hafif kabartma (deneme): açık temada ince gölge, koyu temada biraz daha açık zemin.
    // Beğenilmezse SUBCARD_RAISED = false eski düz çerçeveli görünüme döner.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = when {
            !SUBCARD_RAISED -> Color.Transparent
            isLight -> MaterialTheme.colorScheme.surfaceContainerLowest
            else -> Color.White.copy(alpha = 0.05f)
        },
        shadowElevation = if (SUBCARD_RAISED && isLight) 2.dp else 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isLight) 0.8f else 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/**
 * Ayarlardaki tüm kaydırıcıların ortak görünümü: solda başlık, sağda değer rozeti, altında durak noktası
 * olmayan kaydırıcı. [step] verilirse değer bu adımlara yuvarlanır (ör. yazı boyutu 2 sp, satır sayısı 1).
 */
@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            // Değer rozeti: temanın koyu tonunda, belirgin (renk geçişli hali açık görünüyordu). Açık temada ana renk,
            // koyu temada ana rengin koyu kabı; yazı her ikisinde de zemine göre zıt renkte
            val isLight = SectionCardStyle.isLight()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isLight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    valueText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        ModernSlider(
            value = value,
            onValueChange = { raw ->
                val snapped = if (step == null) raw else {
                    valueRange.start + kotlin.math.round((raw - valueRange.start) / step) * step
                }
                onValueChange(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
            },
            valueRange = valueRange
        )
    }
}

/**
 * Görünüm modu kartı (yan yana iki kart): renkli daire içinde simge, başlık ve kısa açıklama. Seçili kart
 * renk geçişli çerçeve ve hafif renkli zeminle öne çıkar; seçiliyken tekrar dokunmak bir şey değiştirmez
 * (kamera modunda Yerinde Çeviri seçiliyken Sinematik'e dönmesin).
 */
@Composable
private fun ModeChoiceCard(
    icon: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.10f) else SectionCardStyle.segmentBackground())
            .border(
                width = if (selected) 2.dp else 1.dp,
                brush = if (selected) Brush.linearGradient(listOf(primary, tertiary))
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant)),
                shape = shape
            )
            .clickable { if (!selected) onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (selected) Brush.linearGradient(listOf(primary, tertiary))
                    else Brush.linearGradient(listOf(primary.copy(alpha = 0.12f), primary.copy(alpha = 0.12f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            subtitle,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Uygulamanın kendi kaydırıcısı: kalın, yuvarlak uçlu çubuk; dolu kısım ana renkten ikincil renge geçer,
 * tutamaç gölgeli beyaz bir daire. Dokunarak ya da sürükleyerek değiştirilir; erişilebilirlik için
 * ekran okuyucular da değeri okuyup değiştirebilir.
 */
@Composable
private fun ModernSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val activeStart = MaterialTheme.colorScheme.primary
    val activeEnd = MaterialTheme.colorScheme.tertiary
    val inactive = MaterialTheme.colorScheme.primary.copy(alpha = if (SectionCardStyle.isLight()) 0.14f else 0.22f)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val thumbRadiusPx = with(LocalDensity.current) { 11.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { target ->
                    latestOnChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                    true
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        // Tutamacın merkezi çubuğun iki ucundan taşmasın: kullanılabilir genişlik iki yandan yarıçap kadar dar
        fun valueAt(x: Float): Float {
            val usable = (widthPx - 2 * thumbRadiusPx).coerceAtLeast(1f)
            return valueRange.start + ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f) * span
        }
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(valueRange, widthPx) {
                    detectTapGestures { latestOnChange(valueAt(it.x)) }
                }
                .pointerInput(valueRange, widthPx) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        latestOnChange(valueAt(change.position.x))
                    }
                }
        ) {
            val centerY = size.height / 2f
            val trackHeight = 8.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f)
            val left = thumbRadiusPx
            val right = size.width - thumbRadiusPx
            val thumbX = left + (right - left) * fraction
            drawRoundRect(
                color = inactive,
                topLeft = Offset(left - trackHeight / 2f, centerY - trackHeight / 2f),
                size = androidx.compose.ui.geometry.Size(right - left + trackHeight, trackHeight),
                cornerRadius = radius
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(activeStart, activeEnd), startX = left, endX = right),
                topLeft = Offset(left - trackHeight / 2f, centerY - trackHeight / 2f),
                size = androidx.compose.ui.geometry.Size(thumbX - left + trackHeight, trackHeight),
                cornerRadius = radius
            )
            // Tutamaç: yumuşak gölge, beyaz daire, ortasında küçük renkli nokta
            drawCircle(Color.Black.copy(alpha = 0.16f), radius = thumbRadiusPx + 2.dp.toPx(), center = Offset(thumbX, centerY + 1.5.dp.toPx()))
            drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(thumbX, centerY))
            drawCircle(activeStart, radius = thumbRadiusPx * 0.42f, center = Offset(thumbX, centerY))
        }
    }
}

/** Ayar alt kutularında hafif kabartma efekti (false = düz çerçeve) */
private const val SUBCARD_RAISED = true

/**
 * Açılır kapanır ayar bölümü başlığı: başlık, sağda ok ve (isteğe bağlı) altında kısa açıklama.
 * Ayarlar ekranı kalabalık görünmesin diye bazı bölümler kapalı başlar.
 */
@Composable
private fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    badge: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (badge != null) {
            Text(
                badge,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Gizle" else "Göster",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Telefon ekranı modu (alt çubuktaki 📱 Ekran): görünüm modundan bağımsızdır, çeviri her zaman yazının üstünde
 * kutu içinde gösterilir. Yalnızca en küçük yazı boyutu buradan ayarlanır; OCR alanları oyunun içinden düzenlenir.
 */
@Composable
private fun PhoneScreenSection() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var size by remember { mutableFloatStateOf(settings.getPhoneMinFontSize()) }
    var listView by remember { mutableStateOf(settings.getPhoneListView()) }

    Text("📱 Ekran Çevirisi", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    Text("Çeviri nerede görünsün?", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(false to "Yazının üstünde", true to "Liste halinde").forEach { (option, label) ->
            val isSelected = option == listView
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable {
                        listView = option
                        settings.savePhoneListView(option)
                        PhoneScreenService.applyListView(option)
                    },
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
    Text(
        "Liste halinde: çeviri oyunun üstüne yazılmaz, telefonda akan bir liste olarak gösterilir. Oyunu " +
            "televizyonda oynayıp kaçırdığınız replikleri telefondan okumak için (bkz. Nasıl kullanılır).",
        fontSize = 12.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingSlider(
        label = "Çeviri yazı boyutu",
        valueText = "${size.toInt()} sp",
        value = size,
        onValueChange = {
            size = it
            settings.savePhoneMinFontSize(it)
        },
        valueRange = 12f..48f,
        step = 2f
    )
    Text(
        "Not: Oyunun yazısı bu boyuttan küçükse çeviri bu boyutta, büyükse onun boyutunda gösterilir.",
        fontSize = 12.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Sinematik altyazıda önceki çevirilerin akış yönü ve kaç satırının görüneceği. */
@Composable
private fun SubtitleFlowSection() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var flow by remember { mutableStateOf(settings.getSubtitleFlow()) }
    var lines by remember { mutableIntStateOf(settings.getFlowLines()) }
    var eyeBigText by remember { mutableStateOf(settings.getEyeBigText()) }

    Text("Geçmiş çeviriler", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(SubtitleFlow.UP to "Akıllı akış", SubtitleFlow.CENTER to "Ortadan akış", SubtitleFlow.OFF to "Kapalı").forEach { (option, label) ->
            val isSelected = option == flow
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable {
                        flow = option
                        settings.saveSubtitleFlow(option)
                    },
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
    if (flow != SubtitleFlow.OFF) {
        SettingSlider(
            label = "Görünen satır sayısı",
            valueText = "$lines",
            value = lines.toFloat(),
            onValueChange = {
                lines = kotlin.math.round(it).toInt()
                settings.saveFlowLines(lines)
            },
            valueRange = 1f..20f,
            step = 1f
        )
    }
    Text(
        when (flow) {
            SubtitleFlow.UP -> "OCR alanı aşağıdaysa yeni çeviri onun hemen üstünde belirir, öncekiler yukarı doğru akar; " +
                "OCR alanı yukarıdaysa yeni çeviri hemen altında belirir, öncekiler aşağı doğru akar. Öncekiler küçülerek " +
                "ekranın kenarında kaybolur ve hiçbir zaman altyazının üstüne binmez."
            SubtitleFlow.CENTER -> "OCR alanının yeri ne olursa olsun yeni çeviri ekranın ortasında belirir, öncekiler yukarı doğru akar. " +
                "Öncekiler küçülerek ekranın kenarında kaybolur."
            SubtitleFlow.OFF -> "Yalnızca son çeviri ekranın ortasında gösterilir. Kaçırdığınız altyazılara menüdeki \"Çeviri geçmişi\"nden bakabilirsiniz."
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Göz modunda büyük yazı: televizyon ile telefon arasında gidip gelirken çeviri bir bakışta okunsun
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Göz modunda büyük yazı", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Göz simgesine basınca son çeviri, yazı boyutu ayarından bağımsız olarak ekrana sığan en büyük boyuta çıkar. " +
                    "Kapalıyken yazı boyutu ayarı kullanılır.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = eyeBigText, onCheckedChange = {
            eyeBigText = it
            settings.saveEyeBigText(it)
        })
    }
}

/**
 * Ekran çevirisinin OCR alanları: 1. alan (altyazı) hep var; 2. ve 3. alan buradan eklenir/silinir.
 * Eklenen alan ana ekranın üst kısmında çıkar, sürüklenip boyutlanarak yerine konur.
 */
@Composable
private fun ScreenAreasSection() {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var extraCount by remember { mutableIntStateOf(settings.getScreenExtraAreaCount()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("OCR alanları", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "${extraCount + 1} / 3",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Text(
        "1. alan altyazıları okur. Ekranın başka bir yerinde de çevirmek istediğiniz bir yazı varsa (görev ve bilgi " +
            "mesajları, menüler, telefon mesajları gibi) en fazla 2 alan daha ekleyebilirsiniz. Eklenen alanı ana ekranda " +
            "istediğiniz yere sürükleyin, sağ alt köşesinden boyutunu ayarlayın; her alan kendi yazısını ayrı çevirir. " +
            "Alan sayısı arttıkça çeviri biraz yavaşlayabilir.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    for (number in 2..extraCount + 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Alan $number", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            // Yalnızca sondaki alan silinir: numaralar kaymasın
            if (number == extraCount + 1) {
                TextButton(onClick = {
                    extraCount--
                    settings.saveScreenExtraAreaCount(extraCount)
                }) { Text("Sil") }
            }
        }
    }
    if (extraCount < 2) {
        TextButton(onClick = {
            extraCount++
            settings.saveScreenExtraAreaCount(extraCount)
        }) { Text("+ OCR alanı ekle") }
    }
}

@Composable
private fun OfflineModelSection(
    state: OfflineModelDownloader.State,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val totalMb = OfflineModelDownloader.totalBytes / 1_000_000
    var showMeteredDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val updateAvailable by OfflineModelDownloader.updateAvailable.collectAsState()
    val updateError by OfflineModelDownloader.updateError.collectAsState()

    val startDownload: () -> Unit = {
        if (OfflineModelDownloader.isOnMeteredNetwork(context)) {
            showMeteredDialog = true
        } else {
            onDownload()
        }
    }

    // Model yüklüyse açıklama gizli başlar (ayarlar ekranında yer kaplamasın), oka basınca açılır
    val isInstalled = state is OfflineModelDownloader.State.Installed
    var isInfoExpanded by remember { mutableStateOf(!isInstalled) }
    CollapsibleHeader(
        title = "⚡ OPUS Çeviri Motoru",
        expanded = isInfoExpanded,
        onToggle = { isInfoExpanded = !isInfoExpanded },
        badge = if (isInstalled) "İndirildi" else null
    )
    if (isInfoExpanded) {
        Text(
            "Bir kez indirin ($totalMb MB), sonra internet olmadan ücretsiz İngilizce → Türkçe çeviri yapın. API anahtarı, kota veya ücret yok. Argo ve deyimlerde Google Translate kadar doğru olmayabilir. Türkçe → İngilizce yönünde standart çevrimdışı motor kullanılır.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    when (state) {
        is OfflineModelDownloader.State.NotInstalled -> {
            Button(onClick = startDownload, modifier = Modifier.fillMaxWidth()) {
                Text("İndir ($totalMb MB)")
            }
        }
        is OfflineModelDownloader.State.Downloading -> {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "%${(state.progress * 100).toInt()} • ${state.downloadedBytes / 1_000_000} / $totalMb MB",
                    fontSize = 13.sp
                )
                OutlinedButton(onClick = onCancel) { Text("İptal") }
            }
        }
        is OfflineModelDownloader.State.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Dosyalar doğrulanıyor...", fontSize = 13.sp)
        }
        // Durum başlıkta ("İndirildi"); burada yalnızca varsa model güncellemesi gösterilir
        is OfflineModelDownloader.State.Installed -> if (updateAvailable) {
            SettingsSubCard {
                Text("Yeni OPUS modeli hazır", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Günlük konuşmaları ve argoyu daha doğal çeviriyor. İndirme bitene kadar mevcut model çalışmaya devam eder.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                updateError?.let {
                    Text("⚠️ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = startDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Güncelle (${OfflineModelDownloader.updateBytes / 1_000_000} MB)")
                }
            }
        }
        is OfflineModelDownloader.State.Failed -> {
            Text("⚠️ ${state.message}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Button(onClick = startDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Tekrar dene (kaldığı yerden devam eder)")
            }
        }
    }

    if (isInfoExpanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Model: Helsinki-NLP OPUS-MT (opus-mt-tc-big-en-tr) temel alınarak altyazı verisiyle yeniden eğitildi, CC-BY 4.0",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (isInstalled) {
                TextButton(onClick = { showDeleteDialog = true }) { Text("Modeli sil") }
            }
        }
    }

    if (showMeteredDialog) {
        AlertDialog(
            onDismissRequest = { showMeteredDialog = false },
            title = { Text("Mobil veri") },
            text = { Text("Wi-Fi'a bağlı değilsiniz. Model $totalMb MB; mobil veriyle indirmek istiyor musunuz?") },
            confirmButton = {
                TextButton(onClick = {
                    showMeteredDialog = false
                    onDownload()
                }) { Text("İndir") }
            },
            dismissButton = { TextButton(onClick = { showMeteredDialog = false }) { Text("Vazgeç") } }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Modeli sil") },
            text = { Text("OPUS modeli silinsin mi? Tekrar kullanmak için yeniden indirmeniz gerekir ($totalMb MB).") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Vazgeç") } }
        )
    }
}

private sealed class UpdateCheckState {
    object Idle : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(val release: UpdateChecker.ReleaseInfo) : UpdateCheckState()
    object Failed : UpdateCheckState()
}

@Composable
private fun UpdatesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var dialogRelease by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    dialogRelease?.let { UpdateDialog(release = it, onDismiss = { dialogRelease = null }) }

    fun openPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Error opening GitHub releases link", e)
        }
    }

    Text("Güncellemeler", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(
        text = "Yüklü sürüm: v${UpdateChecker.currentVersion}. Yeni bir sürüm yayınlandığında uygulama açılışta sizi bir kez bilgilendirir; dilediğiniz zaman buradan da kontrol edebilirsiniz.",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    when (val s = state) {
        is UpdateCheckState.Available -> {
            Text(
                "🎉 Yeni sürüm mevcut: v${s.release.version}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { dialogRelease = s.release },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Yeni sürümü indir", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        is UpdateCheckState.UpToDate ->
            Text("✅ En güncel sürümü kullanıyorsunuz.", fontSize = 14.sp)
        is UpdateCheckState.Failed ->
            Text(
                "⚠️ Kontrol edilemedi. İnternet bağlantınızı kontrol edip tekrar deneyin.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        else -> Unit
    }

    if (state !is UpdateCheckState.Available) {
        OutlinedButton(
            onClick = {
                state = UpdateCheckState.Checking
                scope.launch {
                    state = try {
                        val latest = UpdateChecker.fetchLatest()
                        if (UpdateChecker.isNewer(latest.version, UpdateChecker.currentVersion)) {
                            UpdateCheckState.Available(latest)
                        } else {
                            UpdateCheckState.UpToDate
                        }
                    } catch (e: Exception) {
                        Log.w("SettingsScreen", "Update check failed: ${e.message}")
                        UpdateCheckState.Failed
                    }
                }
            },
            enabled = state !is UpdateCheckState.Checking,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state is UpdateCheckState.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Kontrol ediliyor...", fontSize = 14.sp)
            } else {
                Text("Güncellemeleri kontrol et 🔄", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    TextButton(onClick = { openPage(UpdateChecker.RELEASES_PAGE) }, modifier = Modifier.fillMaxWidth()) {
        Text("Tüm sürümleri GitHub'da gör", fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: AppTheme,
    currentApiKey: String,
    currentTtsApiKey: String = "",
    subtitleFontSize: Float = 38f,
    currentDisplayMode: DisplayMode = DisplayMode.DEFAULT,
    showDisplayModeQuickSwitch: Boolean = false,
    isOcrTextMaskEnabled: Boolean = false,
    isOcrCenterOnlyEnabled: Boolean = true,
    onOcrCenterOnlyEnabledChange: (Boolean) -> Unit = {},
    offlineModelState: OfflineModelDownloader.State = OfflineModelDownloader.State.NotInstalled,
    onDownloadOfflineModel: () -> Unit = {},
    onCancelOfflineModelDownload: () -> Unit = {},
    onDeleteOfflineModel: () -> Unit = {},
    isTtsEnabled: Boolean = false,
    currentTtsEngineType: TtsEngineType = TtsEngineType.NATIVE,
    speechRate: Float = 1.15f,
    cloudSpeechRate: Float = 1.0f,
    speechPitch: Float = 1.0f,
    currentVoiceGender: VoiceGender = VoiceGender.FEMALE,
    isTrToEn: Boolean = false,
    currentTrCloudVoice: String = "tr-TR-Chirp3-HD-Kore",
    currentEnCloudVoice: String = "en-US-Chirp3-HD-Kore",
    onThemeChange: (AppTheme) -> Unit,
    onSubtitleFontSizeChange: (Float) -> Unit = {},
    onDisplayModeChange: (DisplayMode) -> Unit = {},
    onShowDisplayModeQuickSwitchChange: (Boolean) -> Unit = {},
    onOcrTextMaskEnabledChange: (Boolean) -> Unit = {},
    onApiKeySave: (String) -> Unit,
    isCloudModeEnabled: Boolean = false,
    onCloudModeEnabledChange: (Boolean) -> Unit = {},
    onTtsApiKeySave: (String) -> Unit = {},
    onTtsEnabledChange: (Boolean) -> Unit = {},
    onTtsEngineTypeChange: (TtsEngineType) -> Unit = {},
    onSpeechRateChange: (Float) -> Unit = {},
    onCloudSpeechRateChange: (Float) -> Unit = {},
    onSpeechPitchChange: (Float) -> Unit = {},
    onVoiceGenderChange: (VoiceGender) -> Unit = {},
    onTrCloudVoiceSave: (String) -> Unit = {},
    onEnCloudVoiceSave: (String) -> Unit = {},
    onTestCustomSpeech: (String) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(currentApiKey) }
    var savedApiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }

    var ttsApiKey by remember { mutableStateOf(currentTtsApiKey) }
    var savedTtsApiKey by remember(currentTtsApiKey) { mutableStateOf(currentTtsApiKey) }

    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isTtsApiKeyVisible by remember { mutableStateOf(false) }

    var customTestText by remember { mutableStateOf("") }
    val zoomSettings = remember { SettingsManager(context) }
    // Ayarlar'a her girişte kapalı başlar (ana ekrana dönüp gelince açık kalmasın)
    var isDisplaySectionExpanded by remember { mutableStateOf(false) }
    var isOcrSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var isTtsSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var useZoomSlider by remember { mutableStateOf(zoomSettings.getZoomSliderEnabled()) }

    val isTtsKeySaved = ttsApiKey.isNotBlank() && ttsApiKey == savedTtsApiKey
    val isApiKeySaved = apiKey.isNotBlank() && apiKey == savedApiKey

    fun formatVoiceDisplayLabel(voiceId: String): String {
        val cleanName = voiceId
            .removePrefix("tr-TR-")
            .removePrefix("en-US-")
            .removePrefix("Chirp3-HD-")
            .removePrefix("Chirp-HD-")
        val voiceObj = CloudVoiceRegistry.getVoiceById(voiceId)
        val genderStr = if (voiceObj?.gender == VoiceGender.FEMALE) "Kadın" else "Erkek"
        return "Chirp 3: HD • $cleanName • $genderStr"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SectionCardStyle.screenBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = SectionCardStyle.screenBackground(),
        modifier = Modifier.imePadding()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // BLOCK: OPUS Çeviri Modeli
            SettingsBlockCard {
                OfflineModelSection(
                    state = offlineModelState,
                    onDownload = onDownloadOfflineModel,
                    onCancel = onCancelOfflineModelDownload,
                    onDelete = onDeleteOfflineModel
                )
            }

            // BLOCK 3: Görünüm Modu (Display Mode)
            SettingsBlockCard {
                CollapsibleHeader(
                    title = "Görünüm Modu",
                    subtitle = "Çevirinin ekranda nasıl gösterileceği ve yazı boyutu",
                    expanded = isDisplaySectionExpanded,
                    onToggle = { isDisplaySectionExpanded = !isDisplaySectionExpanded }
                )
                if (isDisplaySectionExpanded) {
                // Çeviri yazı boyutu: Sinematik Altyazı'da doğrudan boyut; Yerinde Çeviri'de en küçük boyut
                // (çeviri ekrandaki İngilizce yazının boyutunu alır, ondan küçükse bu boyuta büyür).
                // Ekran Çevirisi (telefonun kendi ekranı) kameraya dayanmaz: yalnızca kendi ayarları görünür.
                val isScreenMode = currentDisplayMode == DisplayMode.SCREEN_BETA
                val isPhoneMode = currentDisplayMode == DisplayMode.PHONE_SCREEN
                SettingsSubCard {
                    // İki ana seçenek yan yana: kamera ile çeviri (Sinematik ya da Yerinde) ve Ekran Çevirisi.
                    // Kamerada çevirinin yeri ayrıca seçilir; her seçeneğin anlatımı doğrudan altında.
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf(DisplayMode.DEFAULT, DisplayMode.PHONE_SCREEN).forEach { mode ->
                            val isPhone = mode == DisplayMode.PHONE_SCREEN
                            ModeChoiceCard(
                                icon = if (isPhone) "📱" else "📷",
                                title = if (isPhone) DisplayMode.PHONE_SCREEN.label else "Kamera",
                                subtitle = if (isPhone) "Telefonun kendi ekranını çevirir" else "TV'deki altyazıyı kamerayla okur",
                                selected = if (isPhone) isPhoneMode else !isPhoneMode,
                                onClick = { onDisplayModeChange(mode) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }

                    // Kamerayla çeviride çevirinin yeri: Sinematik Altyazı ya da Yerinde Çeviri
                    if (!isPhoneMode) {
                        Text("Çeviri nasıl gösterilsin?", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(DisplayMode.DEFAULT to "🎬 Sinematik", DisplayMode.SCREEN_BETA to "📺 Yazının üstünde").forEach { (option, label) ->
                                val isSelected = option == currentDisplayMode
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clickable { onDisplayModeChange(option) },
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            if (isScreenMode) {
                                "Çeviri, televizyondaki İngilizce yazının tam üstüne siyah bir kutuyla " +
                                    "oturur ve onu örter. Yazı boyutu ekrandaki yazıya göre kendiliğinden ayarlanır; yazı ekrandan " +
                                    "kaybolunca çevirisi de kaybolur. " +
                                    "Başlat'a basınca alanların çerçevesi gizlenir."
                            } else {
                                "Kamera televizyondaki altyazıyı okur, çevirisi telefonun ekranında iri ve net bir yazıyla görünür. " +
                                    "Göz simgesine basınca düğmeler gizlenir, kamera görüntüsü kararır ve ekranda yalnızca çeviri kalır; altyazıyı tek bakışta okursunuz."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Show Quick Switcher on Main Screen Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Anahtara değmesin: kalan genişliği alır, arada boşluk bırakır
                        Text(
                            "Görünüm modu butonunu ana ekranda göster",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                        Switch(
                            checked = showDisplayModeQuickSwitch,
                            onCheckedChange = { onShowDisplayModeQuickSwitchChange(it) }
                        )
                    }
                    // Ekran Çevirisi'nin anlatımı doğrudan kartların altında (kamera seçeneklerinin anlatımı seçimin altında)
                    if (isPhoneMode) {
                        Text(
                            PHONE_SCREEN_DESCRIPTION,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Yerinde Çeviri'de yazı boyutu ayarı yok: çeviri ekrandaki İngilizce yazının boyutunu alır
                if (!isPhoneMode && !isScreenMode) SettingsSubCard {
                    // 2 sp adımlarla, 16–80 (24 sp bazılarına büyük geliyordu)
                    SettingSlider(
                        label = "Çeviri yazı boyutu",
                        valueText = "${subtitleFontSize.toInt()} sp",
                        value = subtitleFontSize,
                        onValueChange = { onSubtitleFontSizeChange(it) },
                        valueRange = 16f..80f,
                        step = 2f
                    )
                }
                when (currentDisplayMode) {
                    DisplayMode.DEFAULT -> SettingsSubCard { SubtitleFlowSection() }
                    DisplayMode.SCREEN_BETA -> SettingsSubCard { ScreenAreasSection() }
                    DisplayMode.PHONE_SCREEN -> SettingsSubCard { PhoneScreenSection() }
                }

                // Yakınlaştırma yalnızca kamerayla çalışan modlarda
                if (currentDisplayMode != DisplayMode.PHONE_SCREEN) SettingsSubCard {
                    // Yakınlaştırma kontrolü: kaydırıcı ya da sabit düğmeler
                    Text("Yakınlaştırma kontrolü", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(true to "Kaydırıcı", false to "Düğmeler (1x–5x)").forEach { (slider, label) ->
                            val isSelected = slider == useZoomSlider
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clickable {
                                        useZoomSlider = slider
                                        zoomSettings.saveZoomSliderEnabled(slider)
                                    },
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        if (useZoomSlider) {
                            "Parmağınızla yukarı/aşağı kaydırın. Kaydırıcının sınırları telefonunuzun kamerasına göre kendiliğinden ayarlanır: en yüksek yakınlaştırma telefona göre değişir, geniş açı da yalnızca telefon bu uygulamaya sunuyorsa görünür."
                        } else {
                            "Sol tarafta 1x–5x arası sabit yakınlaştırma düğmeleri."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                }
            }

            // BLOCK: Metin Okuma (OCR)
            SettingsBlockCard {
                CollapsibleHeader(
                    title = "Metin Okuma (OCR)",
                    subtitle = "Kameranın ekrandaki altyazıyı nasıl okuyacağı: arka plan filtresi ve oyun arayüzü yazılarını eleme",
                    expanded = isOcrSectionExpanded,
                    onToggle = { isOcrSectionExpanded = !isOcrSectionExpanded }
                )
                if (isOcrSectionExpanded) {
                SettingsSubCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Beyaz altyazı filtresi", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Arka planı (siyah kutusu) olmayan beyaz altyazılarda, okuma zayıf kaldığında yazıyı öne çıkarıp yeniden okur. Tüm modlarda çalışır; yalnızca beyaz altyazılarda etkilidir. Açıkken çeviri biraz gecikebilir.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOcrTextMaskEnabled,
                            onCheckedChange = { onOcrTextMaskEnabledChange(it) }
                        )
                    }
                }
                SettingsSubCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Sadece ortadaki altyazıyı oku", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "OCR alanının kenarlarındaki harita, telefon ve görev yazıları gibi oyun arayüzü metinlerini yok sayar. OCR alanını altyazıya ortalayın. Altyazısı sola/sağa dayalı oyunlarda kapatın.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOcrCenterOnlyEnabled,
                            onCheckedChange = { onOcrCenterOnlyEnabledChange(it) }
                        )
                    }
                }
                }
            }

            // BLOCK 4: Sesli Okuma (Speech Reading)
            SettingsBlockCard {
                // Diğer bölümler gibi okla açılır; açma/kapama anahtarı bölümün içinde
                CollapsibleHeader(
                    title = "Sesli Okuma",
                    expanded = isTtsSectionExpanded,
                    onToggle = { isTtsSectionExpanded = !isTtsSectionExpanded },
                    badge = if (isTtsEnabled) "Açık" else "Kapalı"
                )
                if (isTtsSectionExpanded) {
                    Text(
                        "Çevrilen altyazıları sesli okur; ekrana bakmadan oyunu takip edebilirsiniz.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SettingsSubCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Sesli okumayı aç",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = isTtsEnabled,
                                onCheckedChange = { onTtsEnabledChange(it) }
                            )
                        }
                    }
                }

                if (isTtsSectionExpanded && isTtsEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // TTS Engine Selection
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Konuşma Motoru", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TtsEngineType.entries.forEach { engine ->
                                    val isSelected = engine == currentTtsEngineType
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clickable { onTtsEngineTypeChange(engine) },
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = when (engine) {
                                                    TtsEngineType.NATIVE -> "Google Translate"
                                                    TtsEngineType.CLOUD_TTS -> "Cloud Text-to-Speech"
                                                },
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Cloud TTS API Key Input Field (Only visible when CLOUD_TTS is selected)
                        if (currentTtsEngineType == TtsEngineType.CLOUD_TTS) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Cloud Text-to-Speech API", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = ttsApiKey,
                                    onValueChange = { ttsApiKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (isTtsApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation('•'),
                                    trailingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(48.dp)
                                                .pointerInput(Unit) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            if (event.type == PointerEventType.Press) {
                                                                isTtsApiKeyVisible = true
                                                            } else if (event.type == PointerEventType.Release || 
                                                                       event.type == PointerEventType.Exit) {
                                                                isTtsApiKeyVisible = false
                                                            }
                                                            event.changes.forEach { it.consume() }
                                                        }
                                                    }
                                                }
                                        ) {
                                            Icon(
                                                imageVector = if (isTtsApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = "Göster/Gizle",
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )

                                if (!isTtsKeySaved) {
                                    Button(
                                        onClick = {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()

                                            val keyToSave = ttsApiKey.trim()
                                            if (keyToSave.isNotBlank()) {
                                                onTtsApiKeySave(keyToSave)
                                                savedTtsApiKey = keyToSave
                                                ttsApiKey = keyToSave
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(
                                            text = "TTS Anahtarını Kaydet",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    // Side-by-Side equal height buttons for Saved state
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Vibrant Light Green Saved Button
                                        Button(
                                            onClick = {},
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF4CAF50) // Vibrant Light Green
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Kaydedildi",
                                                    tint = Color.White,
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .padding(end = 4.dp)
                                                )
                                                Text(
                                                    text = "Cloud TTS Anahtarı Kaydedildi",
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        // Red Delete Button
                                        Button(
                                            onClick = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()

                                                onTtsApiKeySave("")
                                                savedTtsApiKey = ""
                                                ttsApiKey = ""
                                            },
                                            modifier = Modifier.height(46.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFE53935)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                text = "Anahtarı Sil",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                Text(
                                    "API anahtarınız cihazınızda güvenli bir şekilde saklanır.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Voice Gender Selection
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ses Cinsiyeti", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                VoiceGender.entries.forEach { gender ->
                                    val isSelected = gender == currentVoiceGender
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clickable { onVoiceGenderChange(gender) },
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = when (gender) {
                                                    VoiceGender.FEMALE -> "Kadın"
                                                    VoiceGender.MALE -> "Erkek"
                                                },
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Voice Model Selector (Filtered strictly by selected gender)
                        if (currentTtsEngineType == TtsEngineType.CLOUD_TTS && isTtsKeySaved) {
                            var isVoiceDialogVisible by remember { mutableStateOf(false) }

                            val availableVoices = remember(isTrToEn, currentVoiceGender) {
                                val baseList = if (isTrToEn) CloudVoiceRegistry.englishVoices else CloudVoiceRegistry.turkishVoices
                                baseList.filter { it.gender == currentVoiceGender }
                            }

                            val selectedVoiceId = if (isTrToEn) currentEnCloudVoice else currentTrCloudVoice
                            val selectedVoiceObj = remember(selectedVoiceId, availableVoices) {
                                availableVoices.firstOrNull { it.id == selectedVoiceId }
                                    ?: availableVoices.firstOrNull()
                                    ?: CloudVoiceRegistry.turkishVoices.first()
                            }

                            val displayLabel = formatVoiceDisplayLabel(selectedVoiceObj.id)

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Ses Modeli (${if (isTrToEn) "İngilizce" else "Türkçe"})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                                        .clickable { isVoiceDialogVisible = true }
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = displayLabel,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (isVoiceDialogVisible) {
                                AlertDialog(
                                    onDismissRequest = { isVoiceDialogVisible = false },
                                    title = {
                                        Text(
                                            text = "Ses Modeli Seçin (${if (currentVoiceGender == VoiceGender.FEMALE) "Kadın" else "Erkek"})",
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    text = {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 350.dp)
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            availableVoices.forEach { voice ->
                                                val isSelected = voice.id == selectedVoiceObj.id
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isTrToEn) {
                                                                onEnCloudVoiceSave(voice.id)
                                                            } else {
                                                                onTrCloudVoiceSave(voice.id)
                                                            }
                                                            isVoiceDialogVisible = false
                                                        },
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(12.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = formatVoiceDisplayLabel(voice.id),
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 13.sp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Seçili",
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { isVoiceDialogVisible = false }) {
                                            Text("Kapat")
                                        }
                                    }
                                )
                            }
                        }

                        // Bulut Ses Hızı Slider (Visible ONLY when Cloud TTS engine is selected)
                        if (currentTtsEngineType == TtsEngineType.CLOUD_TTS) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SettingSlider(
                                    label = "Bulut ses hızı",
                                    valueText = String.format(Locale.US, "%.2f", cloudSpeechRate),
                                    value = cloudSpeechRate,
                                    onValueChange = { onCloudSpeechRateChange(it) },
                                    valueRange = 0.8f..1.6f,
                                    step = 0.05f
                                )
                            }
                        }

                        // Sliders are visible ONLY when Google Translate (NATIVE) engine is selected
                        if (currentTtsEngineType == TtsEngineType.NATIVE) {
                            // Ses Hızı Slider
                            SettingSlider(
                                label = "Ses hızı",
                                valueText = String.format(Locale.US, "%.2f", speechRate),
                                value = speechRate,
                                onValueChange = { onSpeechRateChange(it) },
                                valueRange = 0.5f..2.0f,
                                step = 0.05f
                            )

                            // Ses Perdesi Slider
                            SettingSlider(
                                label = "Ses perdesi",
                                valueText = String.format(Locale.US, "%.2f", speechPitch),
                                value = speechPitch,
                                onValueChange = { onSpeechPitchChange(it) },
                                valueRange = 0.5f..2.0f,
                                step = 0.05f
                            )
                        }

                        // Customizable Test Text Box
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .bringIntoViewRequester(bringIntoViewRequester)
                                .onFocusEvent { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            bringIntoViewRequester.bringIntoView()
                                        }
                                    }
                                }
                        ) {
                            Text("Test Metni", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            OutlinedTextField(
                                value = customTestText,
                                onValueChange = { customTestText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 140.dp),
                                placeholder = {
                                    Text(
                                        text = if (isTrToEn) "Hello, this is a voice test." else "Merhaba, sesli okuma testi yapılıyor.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onTestCustomSpeech(customTestText)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sesi Test Et", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // BLOCK 5: Google Cloud Translation (isteğe bağlı, ücretli; açılınca API alanı görünür)
            SettingsBlockCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("☁️ Google Cloud Çeviri", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Ücretli Google servisi; kendi Cloud Translation API anahtarınızı gerektirir. Açtığınızda çeviri yöntemleri arasına eklenir.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isCloudModeEnabled,
                        onCheckedChange = { onCloudModeEnabledChange(it) }
                    )
                }

                if (isCloudModeEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation('•'),
                    trailingIcon = {
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type == PointerEventType.Press) {
                                                isApiKeyVisible = true
                                            } else if (event.type == PointerEventType.Release || 
                                                       event.type == PointerEventType.Exit) {
                                                isApiKeyVisible = false
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Göster/Gizle",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                if (!isApiKeySaved) {
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val keyToSave = apiKey.trim()
                            if (keyToSave.isNotBlank()) {
                                onApiKeySave(keyToSave)
                                savedApiKey = keyToSave
                                apiKey = keyToSave
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "Cloud Translation Anahtarını Kaydet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    // Side-by-Side equal height buttons for Saved state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Vibrant Light Green Saved Button
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50) // Vibrant Light Green
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Kaydedildi",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 4.dp)
                                )
                                Text(
                                    text = "Cloud Translation Anahtarı Kaydedildi",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Red Delete Button
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()

                                onApiKeySave("")
                                savedApiKey = ""
                                apiKey = ""
                            },
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Anahtarı Sil",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
                
                Text(
                    "API anahtarınız cihazınızda güvenli bir şekilde saklanır.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }

            // BLOCK 1: Tema (Theme)
            SettingsBlockCard {
                Text("Tema", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SectionCardStyle.segmentBackground(), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppTheme.entries.forEach { theme ->
                        val isSelected = theme == currentTheme
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { onThemeChange(theme) },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (theme) {
                                        AppTheme.LIGHT -> "Açık"
                                        AppTheme.DARK -> "Koyu"
                                        AppTheme.SYSTEM -> "Sistem"
                                    },
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // BLOCK 6: Güncellemeler (Updates)
            SettingsBlockCard {
                UpdatesSection()
            }
        }
    }
}

/** Ekran Çevirisi'nin açıklaması (Ayarlar'da mod kartlarının altında) */
const val PHONE_SCREEN_DESCRIPTION = "Kamera kullanmaz; telefonun kendi ekranını okur ve oyunun üstüne, İngilizce yazının tam yerine Türkçesini yazar. Asıl gücü Samsung DeX'te: Samsung telefonunuzu kabloyla televizyona bağlayıp DeX'te Xbox Remote Play'i açtığınızda oyun büyük ekranda çok net görünür; Türkçe altyazılar doğrudan İngilizce altyazının üstüne gelir ve televizyonda görünür. Oyunu televizyonda, sanki orijinal Türkçe altyazılıymış gibi oynarsınız. Telefonda oynanan oyunlarda da çalışır. PS Remote Play bu modda çalışmaz: Sony, oyun görüntüsünü ekran paylaşımına kapatıyor ve uygulamaya yalnızca siyah görüntü geliyor. PlayStation oyunları için Kamera modunu ya da aşağıdaki liste görünümünü kullanın; Xbox Remote Play'de böyle bir kısıtlama yoktur, sorunsuz çalışır. DeX'e kablosuz (Smart View ile) bağlanmak bu modla çalışmaz: kablosuz DeX de telefonun ekran paylaşımını kullandığı için ikisi çakışır ve biri kapanır. Başlat'a basın, ekran paylaşımında \"Tek bir uygulamayı paylaş\" seçeneğiyle oyunu ya da Remote Play uygulamasını seçin; OCR alanlarını oyunun içinden, ekranın kenarındaki yüzen balonla yerleştirin. Oyunu oynarken çeviriler dokunmayı engellemez. Liste görünümü: çeviri oyunun üstüne yazılmak yerine telefonda akan iri puntolu bir liste olarak gösterilir. Oyunu televizyonda oynayıp kaçırdığınız replikleri telefondan okumak içindir; PlayStation'da konsoldan Twitch ya da YouTube'a yayın açıp telefonda o yayını paylaşarak bu modu kullanabilirsiniz. Ayarlar'dan ya da oyunun içinde yüzen düğmeden açılır."
