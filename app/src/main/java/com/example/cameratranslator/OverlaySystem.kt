package com.example.cameratranslator

import android.content.res.Configuration
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun MainOverlay(
    currentTranslation: String,
    history: List<String>,
    isPaused: Boolean,
    zoomLevel: Float,
    onTogglePause: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onOcrAreaChange: (RectF, Float, Float) -> Unit,
    translationMode: TranslationMode = TranslationMode.CLOUD,
    onCycleTranslationMode: () -> Unit = {},
    displayMode: DisplayMode = DisplayMode.DEFAULT,
    showDisplayModeQuickSwitch: Boolean = false,
    onToggleDisplayMode: () -> Unit = {},
    isStealthMode: Boolean = false,
    onToggleStealthMode: () -> Unit = {},
    isExposureControlsVisible: Boolean = false,
    onToggleExposureControls: () -> Unit = {},
    onExposureIncrement: () -> Unit = {},
    onExposureDecrement: () -> Unit = {},
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

    var left by rememberSaveable(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(0) { 0.15f } else activeOcrPortrait.getOrElse(0) { 0.1f }) }
    var top by rememberSaveable(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(1) { 0.7f } else activeOcrPortrait.getOrElse(1) { 0.7f }) }
    var right by rememberSaveable(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(2) { 0.85f } else activeOcrPortrait.getOrElse(2) { 0.9f }) }
    var bottom by rememberSaveable(displayMode, isLandscape) { mutableFloatStateOf(if (isLandscape) activeOcrLandscape.getOrElse(3) { 0.88f } else activeOcrPortrait.getOrElse(3) { 0.85f }) }

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

    // Save OCR rect coordinates to SharedPreferences independently per mode and orientation
    LaunchedEffect(left, top, right, bottom, isLandscape, displayMode) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerSize = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
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
            // VARSAYILAN (SİNEMATİK ALTYAZI) MOD: Center Subtitle Overlay
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val effectiveFontSize = remember(currentTranslation, subtitleFontSize) {
                    val len = currentTranslation.length
                    when {
                        len > 130 -> (subtitleFontSize * 0.60f).coerceAtLeast(24f)
                        len > 85  -> (subtitleFontSize * 0.78f).coerceAtLeast(26f)
                        else -> subtitleFontSize
                    }
                }

                var activeTextHeightDp by remember { mutableStateOf(48.dp) }

                // Past History Lines
                if (history.isNotEmpty()) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(history.size) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .heightIn(max = 120.dp)
                            .align(Alignment.Center)
                            .offset(y = - (activeTextHeightDp / 2 + 68.dp))
                            .clipToBounds(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            history.forEach { prev ->
                                Text(
                                    text = prev,
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Active Current Translation
                if (currentTranslation.isNotEmpty()) {
                    Text(
                        text = currentTranslation,
                        color = Color.White,
                        fontSize = effectiveFontSize.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = (effectiveFontSize * 1.15f).sp,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .align(Alignment.Center)
                            .onGloballyPositioned { coordinates ->
                                val heightDp = with(density) { coordinates.size.height.toDp() }
                                if (heightDp > 0.dp) {
                                    activeTextHeightDp = heightDp
                                }
                            }
                    )
                }
            }
        } else {
            // EKRAN ÇEVİRİSİ MODU: Entire OCR Selection Area is a Solid Black Box with Small Cyan Diagonal Arrow ⇲
            if (currentTranslation.isNotEmpty() && containerSize != Size.Zero) {
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
                        (subtitleFontSize * 0.70f).coerceIn(16f, 48f)
                    }
                    Text(
                        text = currentTranslation,
                        color = Color.White,
                        fontSize = betaFontSize.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = (betaFontSize * 1.20f).sp
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
                    .pointerInput(containerSize, displayMode) {
                        detectDragGestures { change, dragAmount ->
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
                        .pointerInput(containerSize, displayMode) {
                            detectDragGestures { change, dragAmount ->
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

        // LAYER 4 (TOP-MOST): Interactive Control Buttons
        // Top Left Menu Button with anchored Dropdown (Hidden in Stealth Mode)
        if (!isStealthMode) {
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .clickable { isMenuExpanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("☰", color = Color.White, fontSize = 28.sp, modifier = Modifier.offset(y = (-1).dp))
                }

                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false },
                    offset = if (isLandscape) DpOffset(32.dp, (-145).dp) else DpOffset(0.dp, 0.dp),
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                ) {
                    DropdownMenuItem(
                        text = { Text("Ayarlar") },
                        onClick = {
                            isMenuExpanded = false
                            onNavigate(Screen.SETTINGS)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Hakkında") },
                        onClick = {
                            isMenuExpanded = false
                            onNavigate(Screen.ABOUT)
                        }
                    )
                }
            }
        }

        // Left Zoom Controls with 1x, 2x, 3x, 4x, 5x (Hidden in Stealth Mode)
        if (!isStealthMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .width(40.dp)
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
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Quick Display Mode Switcher Button (Visible ONLY when enabled in Settings)
            if (showDisplayModeQuickSwitch && !isStealthMode) {
                IconButton(
                    onClick = onToggleDisplayMode,
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("🔄", fontSize = 22.sp)
                }
            }

            // 2. Audio Speaker / Mute Toggle Button (Visible ONLY when Speech Reading is enabled in Settings)
            if (isTtsEnabled && !isStealthMode) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(44.dp)
                ) {
                    Text(if (isMuted) "🔇" else "🔊", fontSize = 24.sp)
                }
            }

            // 3. Stealth Mode Eye Button (ALWAYS VISIBLE so user can toggle back!)
            IconButton(
                onClick = onToggleStealthMode,
                modifier = Modifier.size(44.dp)
            ) {
                Text(if (isStealthMode) "👁️‍🗨️" else "👁️", fontSize = 24.sp)
            }

            // 4. Exposure Control Sun Button (Hidden in Stealth Mode)
            if (!isStealthMode) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isExposureControlsVisible) {
                        IconButton(onClick = onExposureIncrement, modifier = Modifier.size(36.dp)) {
                            Text("＋", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onToggleExposureControls, modifier = Modifier.size(44.dp)) {
                        Text("☀️", fontSize = 24.sp)
                    }
                    if (isExposureControlsVisible) {
                        IconButton(onClick = onExposureDecrement, modifier = Modifier.size(36.dp)) {
                            Text("－", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
                    color = Color.Transparent,
                    shape = RoundedCornerShape(2.dp)
                ) {
                    val (text, color) = when (translationMode) {
                        TranslationMode.CLOUD -> "☁️ CLOUD" to Color.Cyan
                        TranslationMode.FREE -> "🆓 FREE" to Color.White
                        TranslationMode.OFFLINE -> "📴 OFFLINE" to Color.Yellow
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
    }
}
