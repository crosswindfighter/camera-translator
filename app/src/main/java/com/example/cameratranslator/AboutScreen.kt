package com.example.cameratranslator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameratranslator.ui.theme.SectionCardStyle

private const val DEV_TAP_COUNT = 7
private const val DEV_TAP_RESET_MS = 1500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapAt by remember { mutableLongStateOf(0L) }
    var isDevModeEnabled by remember { mutableStateOf(OcrTuning.debugDumpEnabled) }
    var devToast by remember { mutableStateOf<Toast?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hakkında", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SectionCardStyle.screenBackground())
            )
        },
        containerColor = SectionCardStyle.screenBackground()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Cover Banner Image (Edge-to-Edge FillWidth in both Portrait and Landscape)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ct_banner),
                    contentDescription = "Camera Translator Banner",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Identity Header
            Text(
                text = "Camera Translator",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = (-0.5).sp
            )
            
            // Gizli geliştirici modu: sürüm yazısına 7 kez dokununca OCR teşhis kaydı açılır/kapanır
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        val now = SystemClock.elapsedRealtime()
                        versionTapCount = if (now - lastVersionTapAt > DEV_TAP_RESET_MS) 1 else versionTapCount + 1
                        lastVersionTapAt = now
                        val remaining = DEV_TAP_COUNT - versionTapCount
                        if (remaining <= 0) {
                            versionTapCount = 0
                            val enabled = !OcrTuning.debugDumpEnabled
                            if (enabled) OcrDebugDumper.clear()
                            OcrTuning.debugDumpEnabled = enabled
                            isDevModeEnabled = enabled
                            devToast?.cancel()
                            devToast = Toast.makeText(
                                context,
                                if (enabled) "Geliştirici modu açıldı: OCR kaydı başladı" else "Geliştirici modu kapatıldı",
                                Toast.LENGTH_SHORT
                            ).also { it.show() }
                        } else if (remaining <= 3) {
                            devToast?.cancel()
                            devToast = Toast.makeText(
                                context,
                                if (OcrTuning.debugDumpEnabled) "Kapatmak için $remaining dokunuş kaldı" else "Geliştirici modu için $remaining dokunuş kaldı",
                                Toast.LENGTH_SHORT
                            ).also { it.show() }
                        }
                    }
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (isDevModeEnabled) {
                Text(
                    text = "🛠 Geliştirici modu açık: OCR görüntüleri, okunan metin ve çeviriler kaydediliyor (son 600 kare ≈ 7 dk, ~180 MB).\nKonum: Android/data/com.example.cameratranslator/files/ocr_debug\nUygulama kapanınca kendiliğinden kapanır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main Overview Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.35f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Text(
                    text = "Camera Translator, öncelikli olarak Xbox ve PlayStation oyuncuları için geliştirilmiş, istenildiği takdirde PC oyunlarında da kullanılabilen, ekrandaki İngilizce altyazıları ve metinleri gerçek zamanlı olarak Türkçeye çeviren ve seslendiren açık kaynaklı bir kamera çeviri uygulamasıdır.",
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 1: Translation Engines
            SectionHeader("🌐 ÇEVİRİ YÖNTEMLERİ VE KULLANIM")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. ⚡ OPUS (Varsayılan — İnternetsiz, Ücretsiz):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Uygulamanın ana çeviri motoru. Helsinki Üniversitesi'nin açık kaynaklı OPUS-MT modeliyle (opus-mt-tc-big-en-tr) çeviri tamamen telefonunuzun içinde yapılır. Bir kez indirilir; sonrasında internet bağlantısı, API anahtarı, kota veya ücret olmadan hızlıca çeviri yapar. Uçak modunda bile çalışır.\n\nGünlük diyaloglarda çoğunlukla anlaşılır çeviriler verir; ancak argo, deyimler ve OCR'ın hatalı okuduğu metinlerde hata yapabilir. Daha doğru çeviri istediğinizde Google Translate modunu kullanabilirsiniz.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                    Text(
                        text = "Kurulum:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "1. Ayarlar → \"⚡ OPUS Çeviri Motoru\" bölümünden İndir'e dokunun (355 MB, tek seferlik; Wi-Fi önerilir).\n" +
                            "2. İndirme bitince dosyalar otomatik doğrulanır ve motor kullanıma hazır olur. İndirme yarıda kesilirse kaldığı yerden devam eder.\n" +
                            "3. Kamera ekranındaki çeviri yöntemi butonu \"⚡ OPUS\" iken çeviriye başlayın. Uygulama ilk açılışta zaten bu modda gelir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                    Text(
                        text = "Not: OPUS yalnızca İngilizce → Türkçe çeviri yapar. Dil yönü Türkçe → İngilizce seçildiğinde (veya model henüz indirilmemişse) uygulama otomatik olarak Google ML Kit çevrimdışı motoruna geçer; etikette \"⚡ OPUS (standart)\" yazar ve bu durumda çeviri kalitesi bir miktar düşer. Yön tekrar İngilizce → Türkçe yapıldığında OPUS'a otomatik olarak geri dönülür.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Model lisansı: CC-BY 4.0 — Helsinki-NLP / OPUS-MT, Tatoeba Challenge.\nOCR düzelticisinin kelime listesi: CC-BY-SA 4.0 — hermitdave/FrequencyWords (OpenSubtitles 2018).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "2. 🌐 Google Translate (Ücretsiz Online):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Herhangi bir API anahtarı veya ücret ödemeden, internet bağlantınız üzerinden ücretsiz Google Translate ile çeviri yapar. Model indirmek istemeyenler için hazır seçenektir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "3. ☁️ Google Cloud (İsteğe Bağlı, Ücretli):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Google Cloud Translation altyapısıyla çevrimiçi çeviri sağlar. Varsayılan olarak gizlidir; Ayarlar → \"☁️ Google Cloud Çeviri\" anahtarı açıldığında çeviri yöntemleri arasına eklenir ve kendi Cloud Translation API anahtarınızı girmeniz gerekir. Ücretsiz kullanım kotası ve fiyatlandırma koşulları Google Cloud'a bağlıdır.",
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
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• Sesli Okuma Kontrolü:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Sesli okuma, Ayarlar ekranındaki 'Sesli okuma' anahtarıyla açılıp kapatılabilir. Ayrıca aktif edildiğinde kamera ekranının sağ araç çubuğunda beliren hızlı hoparlör (🔊/🔇) butonuyla tek tıkla sessize alınabilir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "1. Google Translate (Yerleşik / Tamamen Ücretsiz):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Telefonunuzun dahili ses motorunu kullanır. Tamamen ücretsizdir, API anahtarı gerektirmez. Ayarlar'dan Ses Hızı ve Ses Perdesi ayarlanabilir; hızlı oyun diyaloglarında ses gecikmesi yaşanırsa ses hızı artırılarak akıcılık sağlanabilir.",
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
                        text = "Google Cloud'un insansı Chirp 3: HD yapay zeka ses modellerini (Kore, Puck, Charon vb.) kullanır. Kadın ve Erkek ses seçeneklerinde gerçekçi ve akıcı seslendirme sağlar. Çalışması için Google Cloud TTS API Key gereklidir. Ayarlar'dan Bulut Ses Hızı ayarlanabilir, böylece hızlı diyaloglarda seslendirme geride kalmaz.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Display Modes
            SectionHeader("📺 GÖRÜNÜM MODLARI")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• Sinematik Altyazı:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Çeviriyi telefon ekranının tam ortasında iri ve net bir puntoyla gösterir. Yeni çeviriler geldikçe eski altyazılar yukarı doğru kayar, adeta bir sinema altyazısı deneyimi sunar. OCR alanı yeşil bir çerçeve ve sağ alt köşesinde yeşil boyutlandırma okuyla (⇲) kontrol edilir.",
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
                        text = "Televizyon veya monitör altyazılarını yerinde takip etmek için tasarlanmıştır. Türkçe çeviriyi, orijinal altyazının üzerine konumlandırılan siyah bir şerit üzerinde gösterir. OCR alanı sağ alt köşesindeki açık mavi boyutlandırma okuyla (⇲) ayarlanabilir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 4: Control Bar Buttons Explanation
            SectionHeader("🎮 KONTROL ÇUBUĞU VE HIZLI BUTONLAR")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• ▶ Başlat / ■ Durdur:",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "'Başlat' butonuna basıldığında kamera altyazıları taramaya ve çevirmeye başlar. 'Durdur' butonuna basıldığında tarama, çeviri ve sesli okuma o milisaniyede tamamen kesilir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "• Dil Yönü (EN → TR / TR → EN):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Çeviri yönünü anında değiştirir. 'TR → EN' yönüne geçildiğinde Türkçe altyazılar İngilizceye çevrilir ve sesli okuma aktifse seslendirme de otomatik olarak İngilizce seslerine geçer. (OPUS modunda bu yönde ML Kit kullanılır.)",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )

                    Text(
                        text = "• Çeviri Yöntemi (⚡ OPUS / 🌐 Google Translate / ☁️ Google Cloud):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Tek tıkla internetsiz ve ücretsiz ⚡ OPUS ile ücretsiz online 🌐 Google Translate arasında geçiş yapmanızı sağlar. ☁️ Google Cloud, Ayarlar'dan açılırsa bu sıraya eklenir.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 5: Pro Tips
            SectionHeader("💡 EN İYİ OCR PERFORMANSI İÇİN PÜF NOKTALARI")
            
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• Parlaklık (Pozlama) Düzeltmesi (☀️ Güneş İkonu):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Karanlık odada TV veya monitördeki beyaz altyazılar kamera merceğinde parlama (blooming) yaparak harflerin yanlış okunmasına ve hatalı çevirilere neden olabilir. Sağ taraftaki Güneş ikonundan parlaklık kısıldığında parlamalar söner, OCR okuma doğruluğu ve netliği belirgin şekilde artar.",
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

                    Text(
                        text = "• Manuel Odak (Odak Çerçevesi İkonu):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Uzaktaki televizyonda altyazı bulanık görünüyorsa sağ taraftaki odak ikonuna dokunun ve kaydırıcıyla netliği elle ayarlayın. Kaydırıcının en altındaki yeşil bölge veya altındaki AUTO butonu otomatik odağa döner. Ayarınız uygulama içinde ekran değiştirseniz de korunur; uygulama yeniden açıldığında otomatik odakla başlar.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 6: Honest limitations of OCR
            SectionHeader("⚠️ BİLMENİZ GEREKENLER")

            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Metin okuma (OCR) hata yapabilir",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Camera Translator altyazıyı televizyon ekranından kamerayla okur. Kamera mesafesi, açı, odak, ekran parlaklığı, yazı tipi ve özellikle altyazının arkasındaki hareketli/dokulu sahneler (araba sürerken asfalt, karlı ya da parlak zeminler) nedeniyle bazı kelimeler yanlış okunabilir, eksik okunabilir veya hiç algılanmayabilir. Bu durumda çeviri de hatalı ya da anlamsız olabilir; çok kısa süre ekranda kalan altyazılar kaçabilir. Bu risk her zaman vardır ve tamamen ortadan kaldırılamaz; ekrandaki görüntüyü kamerayla okuyan her uygulamanın doğal bir sınırıdır.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                    Text(
                        text = "Daha iyi sonuç için: OCR alanını yalnızca altyazıyı kapsayacak şekilde daraltın, parlaklığı (☀️) biraz düşürün, gerekirse manuel odak kullanın ve arka planı olmayan beyaz altyazılarda Ayarlar'daki \"Beyaz altyazı filtresi\"ni açık tutun. Oyununuz destekliyorsa altyazı arka planını (siyah kutu) açmak doğruluğu en çok artıran ayardır.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 7: Development note
            SectionHeader("🤖 GELİŞTİRME NOTU")

            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = SectionCardStyle.cardColor(darkAlpha = 0.25f),
                border = SectionCardStyle.cardBorder(showInDark = false),
                shadowElevation = SectionCardStyle.cardShadow()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Camera Translator'ın temelleri Google Gemini ile atıldı; uygulama sonrasında Claude (Anthropic) ile geliştirildi. Bugün kullandığınız OPUS çeviri motoru, OCR iyileştirmeleri ve güvenlik düzenlemeleri bu sürecin ürünüdür.",
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                    Text(
                        text = "Her iki asistana da katkıları için teşekkürler. Fikirler, testler ve kararlar geliştiriciye aittir. Ayrıntılı geliştirme listesi GitHub sayfasındadır.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 8: Links
            SectionHeader("🔗 BAĞLANTILAR")
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { openLink(context, "https://github.com/crosswindfighter/camera-translator/issues") },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🐞 Hata bildir / öneri gönder", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { openLink(context, "https://github.com/crosswindfighter/camera-translator") },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("📄 Kaynak kodu (GitHub)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = { openLink(context, "https://github.com/crosswindfighter/camera-translator/blob/main/THIRD-PARTY-NOTICES.md") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lisanslar ve üçüncü taraf bildirimleri", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e("AboutScreen", "Cannot open link: $url", e)
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
