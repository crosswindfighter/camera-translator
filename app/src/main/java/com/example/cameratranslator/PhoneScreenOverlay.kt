package com.example.cameratranslator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

private val PHONE_ACCENT = Color(0xFF00E5FF)

/** Oyunun üstündeki tam ekran katman: oyun sırasında yalnızca çeviriler, düzenlemede OCR alanları. */
@Composable
fun PhoneScreenOverlay(service: PhoneScreenService) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerSize = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val landscape = service.isLandscape.value
        val count = service.areaCount.value
        if (service.isEditing.value) {
            // Düzenleme: hafif karartma, alanlar sürüklenip köşeden boyutlanır
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            key(service.areaVersion.value) {
                for (index in 0 until count) {
                    ExtraOcrArea(
                        number = index + 1,
                        area = ExtraAreaUi(
                            initialPortrait = { service.areaRect(index, landscape = false) },
                            initialLandscape = { service.areaRect(index, landscape = true) },
                            translation = "",
                            bounds = null,
                            lineHeight = 0f
                        ),
                        isLandscape = landscape,
                        containerSize = containerSize,
                        isStealthMode = false,
                        minFontSize = service.minFontSize(),
                        allowedArea = null,
                        onRectChange = {},
                        onRectSave = { isLand, rect -> service.saveAreaRect(index, isLand, rect) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .background(Color(0xF21C1F26), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (count < PhoneScreenService.MAX_AREAS) ToolbarButton("+ Alan") { service.setAreaCount(count + 1) }
                if (count > 1) ToolbarButton("− Alan") { service.setAreaCount(count - 1) }
                ToolbarButton("Bitti", accent = true) { service.setEditing(false) }
            }
        } else {
            if (service.listView.value) {
                // Paylaşılan uygulama ekranda değilse liste gizlenir, yoksa öndeki uygulamanın üstünde kalır
                if (!service.captureDark.value) TranslationList(service.minFontSize())
            } else {
                for (index in 0 until count) {
                    service.translations.getOrNull(index)?.let { translation ->
                        PhoneTranslationBox(translation, service.minFontSize(), containerSize, service.coverText.value)
                    }
                }
            }
            // Ekran paylaşımı engelleniyor: uyarı oyunun üstünde gösterilir (bildirimler kapalıysa Toast hiç görünmüyor)
            val captureBlocked by PhoneScreenService.captureBlocked.collectAsState()
            // Öncelik: siyah ekran > metin okuma hatası > kısa bilgi notu
            val warning = when {
                captureBlocked -> PhoneScreenService.CAPTURE_BLOCKED_BANNER
                service.ocrFailing.value -> PhoneScreenService.OCR_FAILING_NOTICE
                else -> null
            }
            val info = service.notice.value
            if (warning != null) {
                OverlayNotice("⚠ $warning", large = service.onExternalDisplay, warning = true)
            } else if (info != null) {
                OverlayNotice(info, large = service.onExternalDisplay, warning = false)
            }
        }
    }
}

/**
 * Oyunun üstündeki uyarı ya da bilgi notu: uygulamaların çizdiği alanın (kamera deliği, gezinme ve durum çubukları hariç) tam ortasında.
 * Katman bu çubukların altına da uzandığı için düz ortalama yatayda uyarıyı oyunun ortasından kaydırıyordu.
 * DeX'te (TV) uzaktan okunacağı için yazı daha büyük.
 */
@Composable
private fun OverlayNotice(text: String, large: Boolean, warning: Boolean) {
    // Çubukların ve kamera deliğinin payı, telefon dönünce Compose tarafından kendiliğinden güncellenir. (Önceki elle
    // ölçüm dönüşten sonra eski yönün payını tutup uyarıyı dikeyde sağa kaydırıyordu.)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 20.sp else 12.sp,
            lineHeight = if (large) 27.sp else 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = if (large) 640.dp else 360.dp)
                // Uyarı kırmızı, bilgi notu koyu (çeviri kutularıyla aynı tonda)
                .background(if (warning) Color(0xE6B3261E) else Color(0xE61C1F26), RoundedCornerShape(if (large) 16.dp else 12.dp))
                .padding(horizontal = if (large) 20.dp else 12.dp, vertical = if (large) 14.dp else 8.dp)
        )
    }
}

@Composable
private fun ToolbarButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (accent) PHONE_ACCENT else Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/**
 * Çeviri kutusu İngilizce yazının hemen üstünde (yer yoksa altında) durur, yazıyı örtmez: örtseydi ekran
 * yakalamada kutu siyah göründüğü için altındaki yeni altyazı okunamazdı. Yazı boyutu ekrandaki yazıya göre,
 * ayardaki boyutun altına inmez; kutu yazıyı sarar ve ekrandan taşmaz.
 */
@Composable
private fun PhoneTranslationBox(
    translation: PhoneScreenService.AreaTranslation,
    minFontSize: Float,
    container: Size,
    /** true: İngilizce yazının tam üstünde ve onu örter; false: hemen üstünde (yer yoksa altında) */
    cover: Boolean
) {
    if (container.width <= 0f || container.height <= 0f) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val margin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 6.dp.toPx() }
    val padX = with(density) { 10.dp.toPx() } * 2
    val padY = with(density) { 4.dp.toPx() } * 2
    val text = translation.textRect
    val textLeft = text.left * container.width
    val textRight = text.right * container.width
    val textTop = text.top * container.height
    val textBottom = text.bottom * container.height

    val lineHeightPx = translation.lineHeight * container.height
    var fontSize = maxOf(
        if (lineHeightPx > 0f) lineHeightPx * 0.75f / (density.density * density.fontScale) else 16f,
        minFontSize
    ).coerceAtMost(48f)
    fun style(size: Float) = TextStyle(
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = (size * 1.15f).sp,
        color = Color.White
    )
    val maxWidth = minOf(container.width - margin * 2, maxOf(textRight - textLeft + padX, container.width * 0.7f))
    fun measure(size: Float) = measurer.measure(
        translation.text, style(size), constraints = Constraints(maxWidth = (maxWidth - padX).roundToInt().coerceAtLeast(1))
    )
    var layout = measure(fontSize)
    // Kutu ekranın yarısından uzun olmasın: gerekirse yazı küçülür
    while (layout.size.height + padY > container.height * 0.5f && fontSize > 12f) {
        fontSize -= 1f
        layout = measure(fontSize)
    }
    val longest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
    // Üstüne konuyorsa en az İngilizce yazıyı örtecek kadar büyük
    val coverPad = with(density) { 6.dp.toPx() }
    val boxWidth = minOf(
        maxWidth,
        maxOf(ceil(longest) + padX + 2f, if (cover) textRight - textLeft + coverPad * 2 else 0f)
    )
    val boxHeight = maxOf(layout.size.height + padY, if (cover) textBottom - textTop + coverPad * 2 else 0f)

    val centerX = (textLeft + textRight) / 2f
    val boxLeft = (centerX - boxWidth / 2f).coerceIn(margin, maxOf(margin, container.width - margin - boxWidth))
    val boxTop = if (cover) {
        ((textTop + textBottom) / 2f - boxHeight / 2f).coerceIn(margin, maxOf(margin, container.height - margin - boxHeight))
    } else {
        val above = textTop - gap - boxHeight
        if (above >= margin) above else (textBottom + gap).coerceAtMost(maxOf(margin, container.height - margin - boxHeight))
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
            .size(with(density) { boxWidth.toDp() }, with(density) { boxHeight.toDp() })
            // Simsiyah: yarı saydamken oyunun kendi altyazısı arkadan seçiliyordu
            .background(Color.Black, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = translation.text,
            style = style(fontSize),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Listede hazır tutulan çeviri sayısı (en yenisi en altta). Ekrana sığandan fazlası bilerek verilir: liste alta
 * yaslanır, sığmayanlar üstten kırpılır ve üst kenarda sönerek kaybolur. Yazı boyutuna göre azaltılmaz, yoksa
 * iri puntoda satırlar ekranın ortasında yok oluyor.
 */
private const val LIST_LINES = 14
/**
 * Listede bir çeviri bu kadar süre kalır. Eskiler silinmeyince ekran dakikalar önceki repliklerle doluyor, yeni
 * çeviri en altta belirse bile göz onu yakalayamıyor ve çeviri gecikiyormuş gibi görünüyordu.
 */
private const val LIST_MAX_AGE_MS = 20_000L
/** Tek bir çeviri ekranı kaplamasın */
private const val LIST_MAX_LINES = 3
/** Eski satırlar ekranın üst kenarına yaklaşırken bu yükseklik boyunca sönerek kaybolur */
private val LIST_FADE = 80.dp

/**
 * Liste görünümü: çeviri oyunun üstüne yazılmaz, telefonda akan bir liste olarak gösterilir. Oyun televizyonda
 * oynanırken telefon yanda durur, kaçırılan replik oradan okunur. En yeni çeviri en altta, yukarıdakiler soluklaşır.
 * İngilizcesi küçük puntoyla altında durur.
 *
 * En yeni satır her zaman tam görünür: liste alta yaslanır, eski satırlar yukarı çıktıkça ekranın üst kenarına
 * yaklaşırken sönerek kaybolur. Solma satır sayısına değil konuma bağlıdır: yazı ister iri ister ufak olsun
 * satırlar hep aynı yerde, üst çerçevenin dibinde kaybolur. Oyunun görüntüsü tamamen kapatılır (siyah zemin),
 * çünkü oyun televizyonda izlenir ve arkadaki görüntü yalnızca okumayı zorlaştırır.
 */
@Composable
private fun TranslationList(fontSize: Float) {
    // Saniyede bir yenilenir: yeni çeviri gelmese de süresi dolan satırlar ekrandan düşsün
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val fresh = TranslationHistory.entries.filter { now - it.time <= LIST_MAX_AGE_MS }
    val shown = fresh.takeLast(LIST_LINES)
    val fadePx = with(LocalDensity.current) { LIST_FADE.toPx() }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .clipToBounds()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val fade = (fadePx / size.height).coerceIn(0.01f, 0.5f)
                    drawRect(
                        brush = Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black),
                        blendMode = BlendMode.DstIn
                    )
                },
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
        ) {
            if (shown.isEmpty() && TranslationHistory.entries.isEmpty()) {
                Text(
                    text = "Çeviriler burada listelenecek. OCR alanının altyazının üstünde olduğundan emin olun.",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.3f).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            shown.forEach { entry ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.translation,
                        color = Color.White,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.3f).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = LIST_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = entry.source,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = (fontSize * 0.6f).sp,
                        lineHeight = (fontSize * 0.85f).sp,
                        textAlign = TextAlign.Center,
                        maxLines = LIST_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Yüzen düğme: sürüklenir; dokununca menü (alanları düzenle, uygulamaya dön, durdur). */
@Composable
fun PhoneScreenBubble(service: PhoneScreenService) {
    var expanded by remember { mutableStateOf(false) }
    val editing = service.isEditing.value
    val onRight = service.bubbleOnRight.value
    val onBottom = service.bubbleOnBottom.value
    // Menü ekranın içine doğru açılır: düğme sağdaysa sola, alttaysa yukarı
    Column(
        horizontalAlignment = if (onRight) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (expanded && onBottom) BubbleMenu(service, editing) { expanded = false }
        Box(
            modifier = Modifier
                .size(48.dp)
                .alpha(if (expanded || editing) 1f else 0.55f)
                .background(Color(0xE61C1F26), CircleShape)
                .border(1.dp, PHONE_ACCENT.copy(alpha = 0.6f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { service.anchorBubble() }) { change, drag ->
                        change.consume()
                        service.moveBubble(drag.x, drag.y)
                    }
                }
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Translate, contentDescription = "Çeviri menüsü", tint = PHONE_ACCENT, modifier = Modifier.size(24.dp))
        }
        if (expanded && !onBottom) BubbleMenu(service, editing) { expanded = false }
    }
}

@Composable
private fun BubbleMenu(service: PhoneScreenService, editing: Boolean, close: () -> Unit) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .background(Color(0xF21C1F26), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(vertical = 6.dp)
    ) {
        BubbleItem(if (editing) "Düzenlemeyi bitir" else "OCR alanlarını düzenle") {
            service.setEditing(!editing)
            close()
        }
        BubbleItem(if (service.listView.value) "Liste görünümü  ✓" else "Liste görünümü") {
            service.setListView(!service.listView.value)
            close()
        }
        BubbleItem("Uygulamaya dön") {
            close()
            service.openApp()
        }
        BubbleItem("Durdur", color = Color(0xFFEF9A9A)) {
            close()
            service.stopSelf()
        }
    }
}

@Composable
private fun BubbleItem(label: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
