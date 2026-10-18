package com.example.cameratranslator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hakkında", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Identity Header
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Camera Translator",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = (-0.5).sp
            )
            
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "v1.0.0",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Description Section
            SectionHeader("Uygulama Hakkında")
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                border = null
            ) {
                Text(
                    text = "Camera Translator, Xbox, PlayStation ve PC oyuncuları için geliştirilmiş, İngilizce altyazıları gerçek zamanlı olarak Türkçeye çeviren açık kaynaklı bir kamera ve altyazı çeviri uygulamasıdır. Kamera ve gelişmiş OCR teknolojisiyle ekrandaki altyazıları algılar ve seçilen çeviri moduna göre Türkçeye aktarır.\n\nUygulama; Google Cloud Translation API ile yüksek kaliteli çevrimiçi çeviri, internet üzerinden ücretsiz Google Translate tabanlı çeviri ve Google ML Kit kullanılarak tamamen çevrimdışı çalışabilen üç farklı çeviri seçeneği sunar.\n\nSinematik Altyazı ve Ekran Çevirisi modları, ayarlanabilir yazı boyutları, bağımsız OCR alan hafızaları, farklı zoom seviyeleri, pozlama ayarı ve hayalet modu gibi özelliklerle oyun sırasında son derece pratik ve konforlu bir çeviri deneyimi sunmak üzere tasarlanmıştır.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.forLanguageTag("tr")),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start,
        letterSpacing = 1.2.sp
    )
}
