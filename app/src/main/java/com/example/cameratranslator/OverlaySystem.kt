package com.example.cameratranslator

import android.content.res.Configuration
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun MainOverlay(
    currentTranslation: String,
    /** Ekrandaki metin çeviri değil bilgi notu ("OPUS indirilmedi" gibi): küçük ve ince yazılır */
    isStatusMessage: Boolean = false,
    /** Ekran çevirisi: çevrilen İngilizce yazının OCR alanı içindeki yeri (0..1); null = kutu gösterilmez */
    screenTextBounds: RectF? = null,
    /** İngilizce satırın yüksekliği (OCR alanına oranla); Türkçe yazı bu boyutta başlar, sığmazsa küçülür */
    screenTextLineHeight: Float = 0f,
    /** Ekran çevirisindeki ek OCR alanları (2. ve 3.); boş = yalnızca ana alan */
    extraAreas: List<ExtraAreaUi> = emptyList(),
    onExtraAreaChange: (index: Int, rect: RectF) -> Unit = { _, _ -> },
    onExtraAreaSave: (index: Int, landscape: Boolean, rect: RectF) -> Unit = { _, _, _ -> },
    /** İlk açılış rehberinin adımı; -1 = rehber kapalı */
    guideStep: Int = -1,
    onGuideNext: () -> Unit = {},
    onGuideBack: () -> Unit = {},
    onGuideSkip: () -> Unit = {},
    history: List<String>,
    /** Sinematik altyazıda önceki çevirilerin akış yönü ve kaç satırının görüneceği */
    subtitleFlow: SubtitleFlow = SubtitleFlow.UP,
    flowLines: Int = 2,
    isPaused: Boolean,
    zoomLevel: Float,
    onTogglePause: () -> Unit,
    onZoomChange: (Float) -> Unit,
    /** true: dikey kaydırıcı (geniş açı–20x), false: 1x–5x düğmeleri */
    useZoomSlider: Boolean = true,
    /** Kameranın zoom aralığı biliniyor mu? Bilinmiyorsa kaydırıcı yanlış yerde çizilmesin diye gizli. */
    isZoomRangeKnown: Boolean = true,
    minZoom: Float = 1f,
    maxZoom: Float = ZOOM_SLIDER_MAX,
    onOcrAreaChange: (RectF, Float, Float) -> Unit,
    translationMode: TranslationMode = TranslationMode.CLOUD,
    isOfflinePlusActive: Boolean = false,
    onCycleTranslationMode: () -> Unit = {},
    displayMode: DisplayMode = DisplayMode.DEFAULT,
    showDisplayModeQuickSwitch: Boolean = false,
    onToggleDisplayMode: () -> Unit = {},
    isStealthMode: Boolean = false,
    onToggleStealthMode: () -> Unit = {},
    isExposureControlsVisible: Boolean = false,
    onToggleExposureControls: () -> Unit = {},
    exposureRange: IntRange = -4..4,
    exposureIndex: Int = 0,
    onExposureIndexChange: (Int) -> Unit = {},
    isFocusControlsVisible: Boolean = false,
    onToggleFocusControls: () -> Unit = {},
    manualFocusValue: Float = 0f,
    onManualFocusChange: (Float) -> Unit = {},
    onFocusRequest: (Float, Float) -> Unit = { _, _ -> },
    onNavigate: (Screen) -> Unit = {},
    isTrToEn: Boolean = false,
    onToggleDirection: () -> Unit = {},
    isTtsEnabled: Boolean = false,
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    subtitleFontSize: Float = 40f,
    initialOcrRectDefaultPortrait: FloatArray = floatArrayOf(0.1f, 0.7f, 0.9f, 0.85f),
    initialOcrRectDefaultLandscape: FloatArray = floatArrayOf(0.15f, 0.7f, 0.85f, 0.88f),
    initialOcrRectScreenPortrait: FloatArray = floatArrayOf(0.1f, 0.75f, 0.9f, 0.88f),
    initialOcrRectScreenLandscape: FloatArray = floatArrayOf(0.15f, 0.75f, 0.85f, 0.90f),
    onOcrRectSaveDefaultPortrait: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onOcrRectSaveDefaultLandscape: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onOcrRectSaveScreenPortrait: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onOcrRectSaveScreenLandscape: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> }
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val activeOcrPortrait = if (displayMode == DisplayMode.DEFAULT) initialOcrRectDefaultPortrait else initialOcrRectScreenPortrait
    val activeOcrLandscape = if (displayMode == DisplayMode.DEFAULT) initialOcrRectDefaultLandscape else initialOcrRectScreenLandscape

    // Konum her mod (sinematik/ekran) ve yön (dikey/yatay) için ayrı kayıttan okunur. rememberSaveable değil:
    // telefon dönünce ekran yeniden kurulurken eski yönün konumunu geri getirip yeni yönün kaydına yazıyordu.
    var left by remember(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(0) { 0.15f } else activeOcrPortrait.getOrElse(0) { 0.1f }) }
    var top by remember(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(1) { 0.7f } else activeOcrPortrait.getOrElse(1) { 0.7f }) }
    var right by remember(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(2) { 0.85f } else activeOcrPortrait.getOrElse(2) { 0.9f }) }
    var bottom by remember(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(3) { 0.88f } else activeOcrPortrait.getOrElse(3) { 0.85f }) }

    var containerSize by remember { mutableStateOf(Size.Zero) }
    var isMenuExpanded by rememberSaveable { mutableStateOf(false) }

    // When orientation or display mode switches, load the corresponding saved coordinates
    LaunchedEffect(isLandscape, displayMode) {
        val rect = if (isLandscape) activeOcrLandscape else activeOcrPortrait
        left = rect.getOrElse(0) { if (isLandscape) 0.15f else 0.1f }
        top = rect.getOrElse(1) { 0.7f }
        right = rect.getOrElse(2) { if (isLandscape) 0.85f else 0.9f }
        bottom = rect.getOrElse(3) { if (isLandscape) 0.88f else 0.85f }
    }

    // Kayıt yalnızca kullanıcı alanı sürükleyip bırakınca, o anki mod ve yönün kaydına yapılır
    val saveOcrRect = {
        if (displayMode == DisplayMode.DEFAULT) {
            if (isLandscape) {
                onOcrRectSaveDefaultLandscape(left, top, right, bottom)
            } else {
                onOcrRectSaveDefaultPortrait(left, top, right, bottom)
            }
        } else {
            if (isLandscape) {
                onOcrRectSaveScreenLandscape(left, top, right, bottom)
            } else {
                onOcrRectSaveScreenPortrait(left, top, right, bottom)
            }
        }
    }

    val normalizedRect = remember(left, top, right, bottom) {
        RectF(left, top, right, bottom)
    }

    val ocrRect = remember(normalizedRect, containerSize) {
        Rect(
            normalizedRect.left * containerSize.width,
            normalizedRect.top * containerSize.height,
            normalizedRect.right * containerSize.width,
            normalizedRect.bottom * containerSize.height
        )
    }

    LaunchedEffect(normalizedRect, containerSize) {
        if (containerSize.width > 0f && containerSize.height > 0f) {
            onOcrAreaChange(normalizedRect, containerSize.width, containerSize.height)
        }
    }

    // Kaydırıcılar kendi düğmelerinin hizasında açılsın: düğmelerin dikey merkezi (kök koordinatında)
    var overlayTopY by remember { mutableFloatStateOf(0f) }
    var overlayLeftX by remember { mutableFloatStateOf(0f) }
    // Rehberin parlak göstereceği düğmelerin yeri (kök koordinatında)
    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    var startBounds by remember { mutableStateOf<Rect?>(null) }
    var eyeBounds by remember { mutableStateOf<Rect?>(null) }
    var zoomBounds by remember { mutableStateOf<Rect?>(null) }
    var sunBounds by remember { mutableStateOf<Rect?>(null) }
    var focusBounds by remember { mutableStateOf<Rect?>(null) }
    var directionBounds by remember { mutableStateOf<Rect?>(null) }
    var engineBounds by remember { mutableStateOf<Rect?>(null) }
    var sunButtonCenterY by remember { mutableStateOf<Float?>(null) }
    var focusButtonCenterY by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerSize = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                overlayTopY = coordinates.positionInRoot().y
                overlayLeftX = coordinates.positionInRoot().x
            }
    ) {
        // LAYER 1: Scrim & Green OCR Box Border (Green border rendered ONLY in DEFAULT mode!)
        if (containerSize != Size.Zero && !isStealthMode) {
            if (displayMode == DisplayMode.DEFAULT) {
                // Sinematik Altyazı modunda ekran kararır, OCR alanı şeffaf kesilir ve yeşil çerçeve çizilir
                Canvas(modifier = Modifier.fillMaxSize()) {
                    with(drawContext.canvas.nativeCanvas) {
                        val checkPoint = saveLayer(null, null)
                        drawRect(Color.Black.copy(alpha = 0.85f))
                        drawRoundRect(
                            color = Color.Transparent,
                            topLeft = Offset(ocrRect.left, ocrRect.top),
                            size = Size(ocrRect.width, ocrRect.height),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                            blendMode = BlendMode.Clear
                        )
                        restoreToCount(checkPoint)
                    }
                }

                // Green Border Frame and Green Corner Arrow Handle (ONLY in DEFAULT mode!)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(ocrRect.left.roundToInt(), ocrRect.top.roundToInt()) }
                        .size(with(density) { ocrRect.width.toDp() }, with(density) { ocrRect.height.toDp() })
                        .border(2.dp, Color(0xFF76FF03), RoundedCornerShape(8.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 2.dp, end = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⇲",
                            color = Color(0xFF76FF03),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // LAYER 2: Subtitle Display
        if (displayMode == DisplayMode.DEFAULT) {
            // Altyazı alanının sınırı: kontroller görünürken yazı soldaki zoom'un ve sağdaki ikonların
            // iç kenarında biter (kontroller kamera deliğinden kaçınca sınır da onlarla kayar);
            // göz modunda ekranın kenarına kadar uzanır, yalnızca kamera deliğinin hemen yanında durur.
            val layoutDirection = LocalLayoutDirection.current
            val cutout = WindowInsets.displayCutout.asPaddingValues()
            val cutoutLeft = cutout.calculateLeftPadding(layoutDirection)
            val cutoutRight = cutout.calculateRightPadding(layoutDirection)
            val subtitleStart: Dp
            val subtitleEnd: Dp
            val subtitleTop: Dp
            val subtitleBottom: Dp
            if (isStealthMode) {
                // Göz modunda yazı kamera deliğine olabildiğince yaklaşır ama üstünden geçmez:
                // telefonun bildirdiği delik sınırı + 4 dp (deliksiz kenarda 12/8 dp kenar payı)
                subtitleStart = maxOf(12.dp, cutoutLeft + 4.dp)
                subtitleEnd = maxOf(12.dp, cutoutRight + 4.dp)
                subtitleTop = maxOf(8.dp, cutout.calculateTopPadding() + 4.dp)
                subtitleBottom = maxOf(8.dp, cutout.calculateBottomPadding() + 4.dp)
            } else {
                // Sol zoom: kenar payı + 40 dp genişlik; sağ ikonlar: kenar payı + 44 dp; araya 10 dp boşluk
                subtitleStart = (if (cutoutLeft > 0.dp) cutoutLeft + 8.dp else 12.dp) + 40.dp + 10.dp
                subtitleEnd = (if (cutoutRight > 0.dp) cutoutRight + 12.dp else 16.dp) + 44.dp + 10.dp
                // Kontrol çubuğu yatayda üstte, dikeyde altta
                subtitleTop = cutout.calculateTopPadding() + if (isLandscape) 56.dp else 12.dp
                subtitleBottom = cutout.calculateBottomPadding() + if (isLandscape) 12.dp else 52.dp
            }

            // VARSAYILAN (SİNEMATİK ALTYAZI) MOD: Center Subtitle Overlay with Invisible Wall Boundary
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = subtitleStart, end = subtitleEnd, top = subtitleTop, bottom = subtitleBottom),
                contentAlignment = Alignment.Center
            ) {
                val effectiveFontSize = remember(currentTranslation, subtitleFontSize) {
                    val len = currentTranslation.length
                    when {
                        // Alt sınır kullanıcının seçtiği boyutu aşmasın (küçük boyut seçildiyse büyütülmesin)
                        len > 130 -> (subtitleFontSize * 0.60f).coerceAtLeast(minOf(24f, subtitleFontSize))
                        len > 85  -> (subtitleFontSize * 0.78f).coerceAtLeast(minOf(26f, subtitleFontSize))
                        else -> subtitleFontSize
                    }
                }

                if (isStatusMessage) {
                    // Bilgi notu (OPUS indirme notu) eskisi gibi ortada
                    AutoFitText(
                        text = currentTranslation,
                        maxFontSize = STATUS_FONT_SIZE,
                        fontWeight = FontWeight.Normal,
                        color = Color.White.copy(alpha = 0.85f),
                        lineHeightFactor = 1.15f,
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                        textModifier = Modifier.fillMaxWidth()
                    )
                } else if (currentTranslation.isNotEmpty()) {
                    // Akan altyazı: güncel çeviri OCR alanının hemen üstünde, öncekiler onun üstünde yukarı kayar.
                    // Alan altyazı sınırının (zoom, simgeler, başlat çubuğu, kamera deliği dışı) içinde kesilir.
                    // OCR alanı üstte kalmışsa (üstünde yer yoksa) altyazı alanının dibine dizilir.
                    // Akıllı akış: OCR'ın üstünde daha çok yer varsa çeviri hemen üstünde çıkar ve yukarı akar;
                    // altında daha çok yer varsa (OCR üstte) hemen altında çıkar ve aşağı akar. Akış OCR'a binmez.
                    val ocrTopInArea = with(density) { ocrRect.top.toDp() } - subtitleTop
                    val ocrBottomInArea = with(density) { ocrRect.bottom.toDp() } - subtitleTop
                    val spaceAbove = (ocrTopInArea - 12.dp).coerceIn(0.dp, maxHeight)
                    val spaceBelow = (maxHeight - ocrBottomInArea - 12.dp).coerceIn(0.dp, maxHeight)
                    val flowUp = spaceAbove >= spaceBelow
                    // İki taraf da çok darsa (OCR alanı ekranın neredeyse tamamı) bütün alan kullanılır
                    val flowHeight = when {
                        maxOf(spaceAbove, spaceBelow) < 72.dp -> maxHeight
                        flowUp -> spaceAbove
                        else -> spaceBelow
                    }
                    val flowAlignment = if (flowUp || flowHeight == maxHeight) Alignment.TopCenter else Alignment.BottomCenter
                    val lines = if (subtitleFlow == SubtitleFlow.OFF) {
                        listOf(currentTranslation)
                    } else {
                        history.takeLast(flowLines) + currentTranslation
                    }
                    // OCR'dan uzak kenara gelen satırlar kararıp kaybolur (başlat çubuğuna/kamera deliğine değmeden)
                    val fadePx = with(density) { 56.dp.toPx() }
                    FlowingSubtitles(
                        lines = lines,
                        currentMaxFontSize = effectiveFontSize,
                        flowUp = flowUp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(flowHeight)
                            .align(flowAlignment)
                            .clipToBounds()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                val fade = (fadePx / size.height).coerceIn(0.01f, 0.5f)
                                drawRect(
                                    brush = if (flowUp) {
                                        Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black)
                                    } else {
                                        Brush.verticalGradient(1f - fade to Color.Black, 1f to Color.Transparent)
                                    },
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    )
                }
            }
        } else {
            // EKRAN ÇEVİRİSİ MODU: OCR alanı şeffaf (ince camgöbeği çerçeve); çeviri, İngilizce yazının tam
            // üstünde siyah kutuda beyaz yazıyla durur ve yazıyı örter
            val allowedArea = controlsFreeArea(containerSize, isLandscape, isStealthMode)
            if (screenTextBounds != null && !isStatusMessage && containerSize != Size.Zero) {
                ScreenTranslationBox(
                    ocrRect, screenTextBounds, screenTextLineHeight, currentTranslation,
                    minFontSize = subtitleFontSize, containerSize = containerSize, allowedArea = allowedArea
                )
            }
            // Alanın yeri belli olsun (göz modunda gizli)
            if (!isStealthMode && containerSize != Size.Zero) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(ocrRect.left.roundToInt(), ocrRect.top.roundToInt()) }
                        .size(with(density) { ocrRect.width.toDp() }, with(density) { ocrRect.height.toDp() })
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    // Okuma sürüyor işareti: çeviri gelene kadar uygulamanın çalıştığı belli olsun
                    if (!isPaused) {
                        ReadingDot(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
                    }
                    // Birden çok alan varken numara: 1 ana (altyazı) alanı
                    if (extraAreas.isNotEmpty()) {
                        Text(
                            text = "1",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp)
                        )
                    }
                    Text(
                        text = "⇲",
                        color = Color(0xFF00E5FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp, end = 4.dp)
                    )
                }
            }
            // Durum yazıları (OPUS indirme notu gibi) eskisi gibi alanın tamamında
            if (screenTextBounds == null && isStatusMessage && currentTranslation.isNotEmpty() && containerSize != Size.Zero) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(ocrRect.left.roundToInt(), ocrRect.top.roundToInt()) }
                        .size(with(density) { ocrRect.width.toDp() }, with(density) { ocrRect.height.toDp() })
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .border(
                            width = if (!isStealthMode) 1.dp else 0.dp,
                            color = if (!isStealthMode) Color(0xFF00E5FF).copy(alpha = 0.5f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val betaFontSize = remember(subtitleFontSize) {
                        (subtitleFontSize * 0.70f).coerceIn(14f, 48f)
                    }
                    // Kutuya sığmazsa kendiliğinden küçülür (kutu küçültülünce yazı taşmasın)
                    AutoFitText(
                        text = currentTranslation,
                        maxFontSize = if (isStatusMessage) STATUS_FONT_SIZE else betaFontSize,
                        fontWeight = if (isStatusMessage) FontWeight.Normal else FontWeight.Bold,
                        color = if (isStatusMessage) Color.White.copy(alpha = 0.85f) else Color.White,
                        lineHeightFactor = 1.20f,
                        modifier = Modifier.fillMaxSize(),
                        textModifier = Modifier.fillMaxWidth()
                    )

                    // Bottom-Right Corner Resize Arrow Indicator (Small Cyan Diagonal Arrow ⇲, visible when not in Stealth Mode)
                    if (!isStealthMode) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 2.dp, end = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⇲",
                                color = Color(0xFF00E5FF),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // LAYER 3: Touch Interaction Layer for OCR Box Dragging/Resizing (Hidden in Stealth Mode)
        // Bound dynamically to containerSize and displayMode so dragging never gets stuck!
        if (containerSize.width > 0f && containerSize.height > 0f && !isStealthMode) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(ocrRect.left.roundToInt(), ocrRect.top.roundToInt()) }
                    .size(with(density) { ocrRect.width.toDp() }, with(density) { ocrRect.height.toDp() })
                    .pointerInput(containerSize, displayMode, isLandscape) {
                        detectDragGestures(onDragEnd = { saveOcrRect() }) { change, dragAmount ->
                            change.consume()
                            val deltaX = dragAmount.x / containerSize.width
                            val deltaY = dragAmount.y / containerSize.height

                            val currentWidth = right - left
                            val currentHeight = bottom - top

                            val newLeft = (left + deltaX).coerceIn(0f, 1f - currentWidth)
                            val newTop = (top + deltaY).coerceIn(0f, 1f - currentHeight)

                            left = newLeft
                            top = newTop
                            right = newLeft + currentWidth
                            bottom = newTop + currentHeight
                        }
                    }
                    .pointerInput(containerSize, displayMode) {
                        detectTapGestures(onTap = { offset ->
                            val screenX = (ocrRect.left + offset.x) / containerSize.width
                            val screenY = (ocrRect.top + offset.y) / containerSize.height
                            onFocusRequest(screenX, screenY)
                        })
                    }
            ) {
                // Bottom-right 40.dp resize handle touch target
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.BottomEnd)
                        .pointerInput(containerSize, displayMode, isLandscape) {
                            detectDragGestures(onDragEnd = { saveOcrRect() }) { change, dragAmount ->
                                change.consume()
                                val deltaX = dragAmount.x / containerSize.width
                                val deltaY = dragAmount.y / containerSize.height

                                right = (right + deltaX).coerceIn(left + 0.05f, 1f)
                                bottom = (bottom + deltaY).coerceIn(top + 0.02f, 1f)
                            }
                        }
                )
            }
        }

        // Ekran çevirisinin ek alanları (kendi çevirileri, sürükleme ve boyutlandırmalarıyla)
        if (displayMode != DisplayMode.DEFAULT) {
            extraAreas.forEachIndexed { index, area ->
                ExtraOcrArea(
                    number = index + 2,
                    area = area,
                    isLandscape = isLandscape,
                    containerSize = containerSize,
                    isStealthMode = isStealthMode,
                    minFontSize = subtitleFontSize,
                    allowedArea = controlsFreeArea(containerSize, isLandscape, isStealthMode),
                    onRectChange = { rect -> onExtraAreaChange(index, rect) },
                    onRectSave = { landscape, rect -> onExtraAreaSave(index, landscape, rect) }
                )
            }
        }

        // LAYER 4 (TOP-MOST): Interactive Control Buttons
        // Sol üst menü düğmesi (göz modunda gizli): yuvarlak, yarı saydam; menü düğmenin hemen altında (dikey)
        // ya da hemen sağında (yatay; altta yer az) açılır
        if (!isStealthMode) {
            // Soldaki zoom kontrolüyle aynı dikey hizada (kamera deliği yandaysa onun yanında)
            val menuCutoutLeft = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LocalLayoutDirection.current)
            val menuStartPadding = if (menuCutoutLeft > 0.dp) menuCutoutLeft + 8.dp else 12.dp
            Box(
                modifier = Modifier
                    .padding(start = menuStartPadding, top = 14.dp)
                    .align(Alignment.TopStart)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .onGloballyPositioned { menuBounds = it.boundsInRoot() }
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (isMenuExpanded) 0.6f else 0.35f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable { isMenuExpanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Menü",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )

                    // Menü düğmeye göre konumlanır: dikeyde hemen altında, yatayda hemen sağında ve üst kenarları
                    // aynı hizada. (Hazır açılır menü ekranın üstünden 48 dp boşluk bıraktığı için yatayda aşağıda kalıyordu.)
                    if (isMenuExpanded) {
                        val gapPx = with(density) { (if (isLandscape) 4.dp else 6.dp).roundToPx() }
                        Popup(
                            popupPositionProvider = remember(isLandscape, gapPx) { MenuPositionProvider(isLandscape, gapPx) },
                            onDismissRequest = { isMenuExpanded = false },
                            properties = PopupProperties(focusable = true)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xF21C1F26),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                            ) {
                                Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
                                    listOf(
                                        Triple(Icons.Rounded.Settings, "Ayarlar", Screen.SETTINGS),
                                        Triple(Icons.Rounded.History, "Çeviri geçmişi", Screen.HISTORY),
                                        Triple(Icons.Rounded.Info, "Hakkında", Screen.ABOUT)
                                    ).forEach { (icon, label, screen) ->
                                        DropdownMenuItem(
                                            text = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium) },
                                            leadingIcon = {
                                                Icon(icon, contentDescription = null, tint = MENU_ICON_COLORS.getValue(screen), modifier = Modifier.size(22.dp))
                                            },
                                            colors = MenuDefaults.itemColors(textColor = Color.White),
                                            onClick = {
                                                isMenuExpanded = false
                                                onNavigate(screen)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Left Zoom Controls: kaydırıcı ya da 1x–5x düğmeleri (Hidden in Stealth Mode)
        // Automatically shifts away from camera punch-hole/notch on left edge in landscape mode!
        if (!isStealthMode && useZoomSlider && isZoomRangeKnown) {
            val cutoutLeftDp = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LocalLayoutDirection.current)
            val dynamicStartPadding = if (cutoutLeftDp > 0.dp) cutoutLeftDp + 8.dp else 12.dp

            ZoomSlider(
                zoomLevel = zoomLevel,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onZoomChange = onZoomChange,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = dynamicStartPadding)
                    .onGloballyPositioned { zoomBounds = it.boundsInRoot() }
            )
        } else if (!isStealthMode && !useZoomSlider) {
            val cutoutLeftDp = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LocalLayoutDirection.current)
            val dynamicStartPadding = if (cutoutLeftDp > 0.dp) cutoutLeftDp + 8.dp else 12.dp

            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = dynamicStartPadding)
                    .width(40.dp)
                    .onGloballyPositioned { zoomBounds = it.boundsInRoot() }
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                listOf(5f, 4f, 3f, 2f, 1f).forEach { level ->
                    TextButton(
                        onClick = { onZoomChange(level) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = "${level.toInt()}x",
                            color = if (zoomLevel == level) Color.Yellow else Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Right Controls Column (Vertically Centered on Right Edge)
        // Automatically shifts away from camera punch-hole/notch on right edge when rotated!
        val cutoutRightDp = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LocalLayoutDirection.current)
        val dynamicEndPadding = if (cutoutRightDp > 0.dp) cutoutRightDp + 12.dp else 16.dp

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = dynamicEndPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Quick Display Mode Switcher Button (Visible ONLY when enabled in Settings)
            if (showDisplayModeQuickSwitch && !isStealthMode) {
                IconButton(
                    onClick = onToggleDisplayMode,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cached,
                        contentDescription = "Görünüm modunu değiştir",
                        tint = ICON_DISPLAY_MODE,
                        modifier = Modifier.size(SIDE_ICON_SIZE)
                    )
                }
            }

            // 2. Audio Speaker / Mute Toggle Button (Visible ONLY when Speech Reading is enabled in Settings)
            if (isTtsEnabled && !isStealthMode) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = if (isMuted) "Sesli okumayı aç" else "Sesli okumayı kapat",
                        tint = if (isMuted) ICON_SPEAKER_MUTED else ICON_SPEAKER,
                        modifier = Modifier.size(SIDE_ICON_SIZE)
                    )
                }
            }

            // 3. Stealth Mode Eye Button (ALWAYS VISIBLE so user can toggle back!)
            IconButton(
                onClick = onToggleStealthMode,
                // Arayüz gizliyken göz simgesi karanlık ekranda dikkat çekmesin: yarı saydam (dokunma alanı aynı)
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (isStealthMode) 0.4f else 1f)
                    .onGloballyPositioned { eyeBounds = it.boundsInRoot() }
            ) {
                // Emoji yerine odak simgesiyle aynı tarzda vektör simge: gizliyken "kapalı göz"
                Icon(
                    imageVector = if (isStealthMode) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (isStealthMode) "Arayüzü göster" else "Arayüzü gizle",
                    tint = ICON_EYE,
                    modifier = Modifier.size(SIDE_ICON_SIZE)
                )
            }

            // 4. Exposure Control Sun Button
            if (!isStealthMode) {
                IconButton(
                    onClick = onToggleExposureControls,
                    modifier = Modifier.size(44.dp).onGloballyPositioned {
                        sunButtonCenterY = it.boundsInRoot().center.y
                        sunBounds = it.boundsInRoot()
                    }
                ) {
                    // Panel açıkken vurgulu (odak düğmesiyle aynı davranış)
                    Icon(
                        imageVector = Icons.Rounded.WbSunny,
                        contentDescription = "Parlaklık",
                        tint = if (isExposureControlsVisible) ICON_SUN_ACTIVE else ICON_SUN,
                        modifier = Modifier.size(SIDE_ICON_SIZE)
                    )
                }
            }

            // 5. Manual Focus Control Button
            if (!isStealthMode) {
                IconButton(
                    onClick = onToggleFocusControls,
                    modifier = Modifier.size(44.dp).onGloballyPositioned {
                        focusButtonCenterY = it.boundsInRoot().center.y
                        focusBounds = it.boundsInRoot()
                    }
                ) {
                    // Kameralardaki odak çerçevesi simgesi; panel açıkken vurgulu
                    Icon(
                        imageVector = Icons.Filled.CenterFocusStrong,
                        contentDescription = "Manuel odak",
                        tint = if (isFocusControlsVisible) ICON_FOCUS_ACTIVE else ICON_FOCUS,
                        modifier = Modifier.size(SIDE_ICON_SIZE)
                    )
                }
            }
        }

        // Floating Sliders Overlay (Fixed Anchors on Right, DOES NOT CAUSE TOOLBAR BUTTONS TO SHIFT OR JUMP!)
        if (!isStealthMode && isExposureControlsVisible) {
            // Telefonun sınırının altına elle pozlamayla "ekstra karanlık" adımlar eklenir (mor bölge)
            val extraDark = if (CameraTuning.supportsManualExposure) CameraTuning.EXTRA_DARK_STEPS else 0
            val sliderMin = exposureRange.first - extraDark
            val sliderSpan = (exposureRange.last - sliderMin).coerceAtLeast(1)
            VerticalSliderCard(
                value = exposureIndex.toFloat(),
                valueRange = sliderMin.toFloat()..exposureRange.last.toFloat(),
                autoZoneFraction = extraDark.toFloat() / sliderSpan,
                zoneColor = Color(0xFFB388FF),
                onValueChange = { onExposureIndexChange(it.roundToInt()) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = dynamicEndPadding + 52.dp)
                    .alignedToButton(sunButtonCenterY?.minus(overlayTopY), containerSize.height)
            )
        }

        if (!isStealthMode && isFocusControlsVisible) {
            val isAutoFocus = manualFocusValue < AUTO_FOCUS_ZONE
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = dynamicEndPadding + 52.dp)
                    .alignedToButton(focusButtonCenterY?.minus(overlayTopY), containerSize.height),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // En alttaki yeşil bölge otomatik odak; oraya çekilince AUTO'ya oturur
                VerticalSliderCard(
                    value = manualFocusValue,
                    valueRange = 0f..1f,
                    onValueChange = { v -> onManualFocusChange(if (v < AUTO_FOCUS_ZONE) 0f else v) },
                    autoZoneFraction = AUTO_FOCUS_ZONE
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .background(
                            if (isAutoFocus) Color(0xFF2E7D32) else Color.Black.copy(alpha = 0.85f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(1.dp, if (isAutoFocus) Color(0xFF7CFC00) else Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .clickable { onManualFocusChange(0f) }
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "AUTO",
                        color = if (isAutoFocus) Color.White else Color.White.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Control Bar (Top-Center ONLY in Landscape orientation; Bottom-Center in Portrait for BOTH modes!)
        if (!isStealthMode) {
            val isTopPositioned = isLandscape
            val barAlignment = if (isTopPositioned) Alignment.TopCenter else Alignment.BottomCenter
            val barPaddingModifier = if (isTopPositioned) Modifier.padding(top = 16.dp) else Modifier.padding(bottom = 12.dp)

            Row(
                modifier = Modifier
                    .align(barAlignment)
                    .then(barPaddingModifier)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Stop Toggle
                Surface(
                    onClick = onTogglePause,
                    modifier = Modifier.onGloballyPositioned { startBounds = it.boundsInRoot() },
                    color = Color.Transparent,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isPaused) "▶ Başlat" else "■ Durdur",
                            color = if (isPaused) Color.Yellow else Color.Green,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))
                
                // Language Direction Toggle
                Surface(
                    onClick = onToggleDirection,
                    modifier = Modifier.onGloballyPositioned { directionBounds = it.boundsInRoot() },
                    color = Color.Transparent,
                    shape = RoundedCornerShape(2.dp)
                ) {
                    Text(
                        text = if (isTrToEn) "TR → EN" else "EN → TR",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))
                
                Surface(
                    onClick = onCycleTranslationMode,
                    modifier = Modifier.onGloballyPositioned { engineBounds = it.boundsInRoot() },
                    color = Color.Transparent,
                    shape = RoundedCornerShape(2.dp)
                ) {
                    val (text, color) = when (translationMode) {
                        TranslationMode.FREE -> "🌐 Google Translate" to Color.White
                        TranslationMode.CLOUD -> "☁️ Google Cloud" to Color.Cyan
                        TranslationMode.OFFLINE ->
                            // Model yüklü ve EN -> TR ise OPUS motoru; değilse ML Kit yedeği ("standart")
                            if (isOfflinePlusActive) "⚡ OPUS" to Color(0xFF7CFC00) else "⚡ OPUS (standart)" to Color.Yellow
                    }
                    Text(
                        text = text,
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // İlk açılış rehberi (en üst katman): anlatılan düğme ya da alan parlak kalır
        if (guideStep >= 0 && containerSize != Size.Zero) {
            val offset = Offset(overlayLeftX, overlayTopY)
            val target = when (guideTargetOf(guideStep)) {
                GuideTarget.NONE -> null
                GuideTarget.OCR_AREA -> ocrRect
                GuideTarget.START -> startBounds?.translate(-offset)
                GuideTarget.EYE -> eyeBounds?.translate(-offset)
                GuideTarget.MENU -> menuBounds?.translate(-offset)
                GuideTarget.ZOOM -> zoomBounds?.translate(-offset)
                GuideTarget.SUN -> sunBounds?.translate(-offset)
                GuideTarget.FOCUS -> focusBounds?.translate(-offset)
                GuideTarget.DIRECTION -> directionBounds?.translate(-offset)
                GuideTarget.ENGINE -> engineBounds?.translate(-offset)
            }
            OnboardingGuide(
                step = guideStep,
                target = target,
                containerHeight = containerSize.height,
                onNext = onGuideNext,
                onBack = onGuideBack,
                onSkip = onGuideSkip
            )
        }
    }
}

@Composable
private fun VerticalSliderCard(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** >0 ise kaydırıcının alt kısmında bu oranda renkli bölge çizilir (odak: otomatik, parlaklık: ekstra karanlık). */
    autoZoneFraction: Float = 0f,
    zoneColor: Color = Color(0xFF7CFC00)
) {
    val density = LocalDensity.current
    val defaultHeightPx = with(density) { 125.dp.toPx() }
    var componentHeightPx by remember { mutableFloatStateOf(defaultHeightPx) }
    var internalValue by remember(value) { mutableFloatStateOf(value) }

    val normalizedValue = remember(internalValue, valueRange) {
        val range = valueRange.endInclusive - valueRange.start
        if (range <= 0f) 0.5f else ((internalValue - valueRange.start) / range).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .width(28.dp)
            .height(125.dp)
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .onGloballyPositioned { coordinates ->
                if (coordinates.size.height > 0) {
                    componentHeightPx = coordinates.size.height.toFloat()
                }
            }
            .pointerInput(valueRange) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val totalRange = valueRange.endInclusive - valueRange.start
                    val deltaValue = (-dragAmount / componentHeightPx) * totalRange
                    val newValue = (internalValue + deltaValue).coerceIn(valueRange.start, valueRange.endInclusive)
                    internalValue = newValue
                    onValueChange(newValue)
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        val density = LocalDensity.current
        val trackMarginDp = 12.dp
        val trackMarginPx = with(density) { trackMarginDp.toPx() }
        val availableTrackPx = remember(componentHeightPx, trackMarginPx) {
            (componentHeightPx - (trackMarginPx * 2f)).coerceAtLeast(1f)
        }
        val currentKnobCenterPx = remember(normalizedValue, availableTrackPx, trackMarginPx) {
            trackMarginPx + (normalizedValue * availableTrackPx)
        }

        // 1. Background Track Line
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = trackMarginDp)
                .width(4.dp)
                .height(with(density) { availableTrackPx.toDp() })
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
        )

        // 1b. Otomatik bölge (yeşil, izin en altında)
        if (autoZoneFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = trackMarginDp)
                    .width(8.dp)
                    .height(with(density) { (autoZoneFraction * availableTrackPx).toDp() })
                    .background(zoneColor.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
            )
        }

        // 2. Active Filled Blue Level Bar (Aligned EXACTLY to Knob Center!)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = trackMarginDp)
                .width(4.dp)
                .height(with(density) { (normalizedValue * availableTrackPx).toDp() })
                .background(Color(0xFF2196F3), RoundedCornerShape(2.dp))
        )

        // 3. Cyan Knob Handle (Centered EXACTLY at currentKnobCenterPx!)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = with(density) { (-currentKnobCenterPx).toDp() + 5.dp })
                .size(width = 18.dp, height = 10.dp)
                .background(Color(0xFF00E5FF), RoundedCornerShape(5.dp))
                .border(1.dp, Color.White, RoundedCornerShape(5.dp))
        )
    }
}

/**
 * Düğmelerin dışında kalan alan (ekran koordinatı): soldaki zoom, sağdaki simgeler, başlat çubuğu (yatayda üstte,
 * dikeyde altta) ve kamera deliği hariç. Sinematik altyazının sınırıyla aynı. Göz modunda düğmeler gizli: null.
 */
@Composable
private fun controlsFreeArea(containerSize: Size, isLandscape: Boolean, isStealthMode: Boolean): Rect? {
    if (isStealthMode || containerSize.width <= 0f || containerSize.height <= 0f) return null
    val density = LocalDensity.current
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val cutoutLeft = cutout.calculateLeftPadding(layoutDirection)
    val cutoutRight = cutout.calculateRightPadding(layoutDirection)
    with(density) {
        val left = ((if (cutoutLeft > 0.dp) cutoutLeft + 8.dp else 12.dp) + 40.dp + 10.dp).toPx()
        val right = ((if (cutoutRight > 0.dp) cutoutRight + 12.dp else 16.dp) + 44.dp + 10.dp).toPx()
        val top = (cutout.calculateTopPadding() + if (isLandscape) 56.dp else 12.dp).toPx()
        val bottom = (cutout.calculateBottomPadding() + if (isLandscape) 12.dp else 52.dp).toPx()
        if (containerSize.width - left - right <= 0f || containerSize.height - top - bottom <= 0f) return null
        return Rect(left, top, containerSize.width - right, containerSize.height - bottom)
    }
}

/** Sağdaki kontrol simgelerinin ortak boyutu (göz, ses, görünüm modu, parlaklık, odak) */
private val SIDE_ICON_SIZE = 25.dp

// Sağdaki simgelerin renkleri: bembeyaz yerine yumuşak tonlar; açık olan kaydırıcının simgesi daha canlı
private val ICON_DISPLAY_MODE = Color(0xFFB39DDB) // lila
private val ICON_SPEAKER = Color(0xFFA5D6A7) // yeşil (sesli okuma açık)
private val ICON_SPEAKER_MUTED = Color(0xFFEF9A9A) // kırmızımsı (sessiz)
private val ICON_EYE = Color(0xFF80DEEA) // açık mavi
private val ICON_SUN = Color(0xFFFFE082) // açık sarı
private val ICON_SUN_ACTIVE = Color(0xFFFFC107)
private val ICON_FOCUS = Color(0xFF80CBC4) // turkuaz
private val ICON_FOCUS_ACTIVE = Color(0xFF00E5FF)

/** Sol üst menüdeki simgelerin renkleri (sağdaki simgelerle aynı yumuşak tonlar) */
private val MENU_ICON_COLORS = mapOf(
    Screen.SETTINGS to Color(0xFFB39DDB),
    Screen.HISTORY to Color(0xFFA5D6A7), // yeşil
    Screen.ABOUT to Color(0xFF64B5F6) // mavi (sarı uyarı gibi görünüyordu)
)

/**
 * Açılan kaydırıcı panelini kendi düğmesinin dikey hizasına ortalar (yatayda da dikeyde de);
 * ekran kenarına taşacaksa içeride tutar. Konum ölçüm aşamasında, panelin gerçek yüksekliğiyle
 * hesaplanır: önceden yükseklik bir kare sonra öğrenildiği için panel önce aşağıda görünüp zıplıyordu.
 */
private fun Modifier.alignedToButton(buttonCenterY: Float?, containerHeight: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val margin = 12.dp.toPx()
            val center = buttonCenterY ?: (containerHeight / 2f)
            val maxTop = (containerHeight - placeable.height - margin).coerceAtLeast(margin)
            placeable.place(0, (center - placeable.height / 2f).coerceIn(margin, maxTop).roundToInt())
        }
    }

/** Bilgi notlarının yazı boyutu (çeviri yazısından küçük) */
private const val STATUS_FONT_SIZE = 17f

/** Sol üst menünün konumu: [beside] ise düğmenin sağında ve üstü aynı hizada, değilse hemen altında. */
private class MenuPositionProvider(private val beside: Boolean, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = if (beside) anchorBounds.right + gapPx else anchorBounds.left
        val y = if (beside) anchorBounds.top else anchorBounds.bottom + gapPx
        return IntOffset(
            x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
        )
    }
}
