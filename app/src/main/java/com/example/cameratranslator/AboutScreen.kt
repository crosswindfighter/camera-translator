package com.example.cameratranslator

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
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
                    text = "Camera Translator, Xbox ve PlayStation oyuncuları için geliştirilmiş, İngilizce altyazıları gerçek zamanlı olarak Türkçeye çevirmeye yardımcı olan bir altyazı çeviri uygulamasıdır. Uygulama, konsolların yanı sıra PC oyunlarında da kullanılabilir. Kamera ve gelişmiş OCR teknolojisiyle ekrandaki altyazıları algılar ve seçilen çeviri yöntemine göre Türkçeye aktarır.\n\nUygulama; Google Cloud Translation API ile yüksek kaliteli çevrimiçi çeviri, internet üzerinden ücretsiz Google Translate tabanlı çeviri ve Google ML Kit kullanılarak tamamen çevrimdışı çeviri olmak üzere üç farklı çeviri seçeneği sunar. Cloud Translation kullanmak için kullanıcının kendi Google Cloud Translation API anahtarını uygulamanın Ayarlar bölümünden girmesi gerekir. Free modu internet bağlantısıyla ücretsiz çeviri sağlar, Offline modu ise internet bağlantısı olmadan çalışır.\n\nAyrıca farklı zoom seviyeleri, pozlama ayarı, manuel odaklama ve göz modu gibi özelliklerle oyun sırasında daha rahat ve kullanışlı bir çeviri deneyimi sunmak üzere tasarlanmıştır. Camera Translator, özellikle Türkçe dil desteği bulunmayan oyunları daha anlaşılır ve erişilebilir hale getirmeyi amaçlar.\n\nUygulamayla ilgili görüşlerinizi, geri bildirimlerinizi, karşılaştığınız sorunları veya geliştirme önerilerinizi bizimle paylaşabilirsiniz. Kullanıcı geri bildirimleri, Camera Translator'ın geliştirilmesinde önemli bir rol oynamaktadır.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Contact Section
            SectionHeader("İletişim")
            
            Card(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:crosswindfighter@gmail.com")
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "crosswindfighter@gmail.com",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
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
