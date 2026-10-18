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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Identity Header
            Spacer(modifier = Modifier.height(16.dp))
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
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(
                    text = "v1.0.0",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main Overview Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Text(
                    text = "Camera Translator, öncelikli olarak Xbox ve PlayStation konsol oyuncuları için geliştirilmiş, istenildiği takdirde PC oyunlarında da kullanılabilen, ekrandaki İngilizce altyazıları ve metinleri gerçek zamanlı olarak Türkçeye çeviren ve seslendiren açık kaynaklı bir kamera çeviri uygulamasıdır.",
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 1: Translation Engines
            SectionHeader("🌐 ÇEVİRİ MODLARI VE KULLANIM")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. Cloud Translation (En Yüksek Kalite):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Google Cloud altyapısıyla en doğru çeviriyi sunar. Çalışması için kullanıcıların Ayarlar bölümünden kendi Google Cloud API Key anahtarlarını girmeleri gerekir. Google Cloud, her ay ilk 500.000 karakteri ücretsiz sunar; bu limit aşıldığında Google hesabınız üzerinden ücretlendirme uygulanır.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "2. FREE Modu (Ücretsiz Online):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Herhangi bir API anahtarı veya ücret ödemeden, internet bağlantınız üzerinden ücretsiz Google Translate altyazısıyla çeviri yapar.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "3. OFFLINE Modu (İnternetsiz Çevrimdışı):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Google ML Kit teknolojisini kullanarak internet bağlantısına veya API anahtarına ihtiyaç duymadan %100 cihaz üzerinde çevrimdışı çalışır.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Speech Engines
            SectionHeader("🔊 SESLİ OKUMA VE KONUŞMA MOTORLARI")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. Google Translate (Yerleşik / Tamamen Ücretsiz):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Telefonunuzun dahili ses motorunu kullanır. Tamamen ücretsizdir, API anahtarı gerektirmez ve anında çalışır.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "2. Cloud Text-to-Speech (Yapay Zeka Sesleri):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Google Cloud'un insansı Chirp 3: HD yapay zeka ses modellerini (Kore, Puck, Charon vb.) kullanır. Kadın ve Erkek ses seçeneklerinde gerçekçi ve akıcı seslendirme sağlar. Çalışması için Google Cloud TTS API Key gereklidir ve Google Cloud'un aylık ücretsiz kota kullanım limitleri dahilinde çalışır.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Display Modes
            SectionHeader("📺 ARAYÜZ VE ÇEVİRİ MODLARI")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• Sinematik Altyazı:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Çeviriyi telefon ekranının tam ortasında iri ve net bir puntoyla gösterir. Yeni çeviriler geldikçe eski altyazılar yukarı doğru kayar, adeta bir sinema altyazısı deneyimi sunar.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "• Ekran Çevirisi:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Televizyon veya monitör altyazılarını yerinde kapatmak için tasarlanmıştır. Yeşil OCR kutusunu TV altyazısının üzerine getirdiğinizde, Türkçe çeviri siyah bir şerit üzerinde doğrudan TV altyazısının üzerini kapatarak görüntülenir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 4: Pro Tips
            SectionHeader("💡 EN İYİ OCR PERFORMANSI İÇİN PÜF NOKTALARI")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• Parlaklık (Pozlama) Düzeltmesi (☀️ Güneş İkonu):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Karanlık odada TV veya monitördeki beyaz altyazılar kamera merceğinde parlama (blooming) yaparak harflerin yanlış okunmasına ve hatalı çevirilere neden olabilir. Sağ taraftaki Güneş ikonundan parlaklığı kıstığınızda parlamalar söner, OCR harfleri jilet gibi net okur ve çeviri doğruluğu %100'e ulaşır.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "• Hayalet / Göz Modu (👁️‍🗨️ Göz İkonu):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Ekrandaki tüm çerçeve ve butonları gizleyerek odağınızı tamamen altyazıya vermenizi sağlar.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "• Zoom Kontrolleri (1x - 5x):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Koltukta otururken televizyona yaklaşmadan 1x ile 5x arasında yakınlaştırma yapabilirsiniz.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "• Dokunarak Odaklama:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Kamera ekranında altyazının olduğu yere dokunarak merceğin tam o noktaya netleme yapmasını sağlayabilirsiniz.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start,
        letterSpacing = 0.8.sp
    )
}
