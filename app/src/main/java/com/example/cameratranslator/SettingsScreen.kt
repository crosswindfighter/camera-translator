package com.example.cameratranslator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
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
    onApiKeySave: (String) -> Unit,
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
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
            // BLOCK 1: Tema (Theme)
            SettingsBlockCard {
                Text("Tema", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
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

            // BLOCK 2: Çeviri Yazı Boyutu (Subtitle Font Size)
            SettingsBlockCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Çeviri yazı boyutu", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${subtitleFontSize.toInt()} sp",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = subtitleFontSize,
                    onValueChange = { onSubtitleFontSizeChange(it) },
                    valueRange = 24f..80f,
                    steps = 27,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // BLOCK 3: Çeviri Modu (Display Mode)
            SettingsBlockCard {
                Text("Çeviri Modu", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DisplayMode.entries.forEach { mode ->
                        val isSelected = mode == currentDisplayMode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { onDisplayModeChange(mode) },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (mode) {
                                        DisplayMode.DEFAULT -> "Sinematik Altyazı"
                                        DisplayMode.SCREEN_BETA -> "Ekran Çevirisi"
                                    },
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Show Quick Switcher on Main Screen Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Çeviri Modunu ana ekranda göster", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = showDisplayModeQuickSwitch,
                        onCheckedChange = { onShowDisplayModeQuickSwitchChange(it) }
                    )
                }
            }

            // BLOCK 4: Sesli Okuma (Speech Reading)
            SettingsBlockCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sesli okuma", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Switch(
                        checked = isTtsEnabled,
                        onCheckedChange = { onTtsEnabledChange(it) }
                    )
                }

                if (isTtsEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // TTS Engine Selection
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Konuşma Motoru", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
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
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
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
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Bulut ses hızı", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = String.format(Locale.US, "%.2f", cloudSpeechRate),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = cloudSpeechRate,
                                    onValueChange = { onCloudSpeechRateChange(it) },
                                    valueRange = 0.8f..1.6f,
                                    steps = 15,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Sliders are visible ONLY when Google Translate (NATIVE) engine is selected
                        if (currentTtsEngineType == TtsEngineType.NATIVE) {
                            // Ses Hızı Slider
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Ses hızı", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = String.format(Locale.US, "%.2f", speechRate),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = speechRate,
                                    onValueChange = { onSpeechRateChange(it) },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Ses Perdesi Slider
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Ses perdesi", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        text = String.format(Locale.US, "%.2f", speechPitch),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = speechPitch,
                                    onValueChange = { onSpeechPitchChange(it) },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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

            // BLOCK 5: Translation API Key Section
            SettingsBlockCard {
                Text("Cloud Translation API", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                
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
    }
}
