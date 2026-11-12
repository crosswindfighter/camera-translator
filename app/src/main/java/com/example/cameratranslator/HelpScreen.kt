package com.example.cameratranslator

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameratranslator.ui.theme.SectionCardStyle

/**
 * "Nasıl Kullanılır": uygulamanın A'dan Z'ye kullanımı, daha iyi sonuç için taktikler ve sık sorulan sorular.
 * Konular kapalı kartlar olarak durur, dokununca açılır.
 */
private data class HelpTopic(val emoji: String, val title: String, val points: List<String>)

private data class HelpSection(val title: String, val subtitle: String, val accent: Color, val topics: List<HelpTopic>)

private val HELP_SECTIONS = listOf(
    HelpSection(
        title = "🚀 Başlarken",
        subtitle = "Uygulamayı ilk kez kullanıyorsanız buradan başlayın",
        accent = Color(0xFF64B5F6),
        topics = listOf(
            HelpTopic(
                "📷", "Kamera modu ile ilk çeviri",
                listOf(
                    "Yerleştirin: Telefonu TV'ye bakacak şekilde sabit bir yere koyun. Tripod ya da bir destek idealdir; elde tutunca görüntü titrer ve okuma zorlaşır.",
                    "Yakınlaştırın: Soldaki zoom ile altyazı ekranda büyük ve net görünene kadar yaklaşın. Kaydırıcı yerine 1x–5x düğmeleri isterseniz Ayarlar'dan değiştirebilirsiniz.",
                    "Alanı seçin: OCR alanını (çerçeve) altyazının üstüne sürükleyin, sağ alt köşesinden boyutunu ayarlayın.",
                    "Başlatın: Başlat'a basın. Altyazı okunur, çevrilir ve ekranda gösterilir. Duraklatınca OCR alanını yeniden ayarlayabilirsiniz.",
                    "Rehber: Düğmeleri adım adım tanıtan rehberi Hakkında ekranının altından tekrar açabilirsiniz."
                )
            ),
            HelpTopic(
                "📱", "Ekran Çevirisi (Xbox Remote Play, DeX)",
                listOf(
                    "Ne işe yarar: Kamera kullanmaz; telefonun kendi ekranını okur ve Türkçe çeviriyi oyunun üstüne, İngilizce yazının tam yerine yazar.",
                    "Seçin: Ana ekrandaki mod düğmesinden ya da Ayarlar > Görünüm Modu'ndan Ekran Çevirisi'ne geçin.",
                    "Başlatın: Başlat'a basın, ekran paylaşımında \"Tek bir uygulamayı paylaş\" seçeneğiyle oyunu ya da Xbox Remote Play uygulamasını seçin.",
                    "Alanları yerleştirin: Oyunun içindeyken ekranın kenarındaki yüzen düğmeye dokunun ve OCR alanlarını düzenle ile alanları altyazının üstüne koyun.",
                    "DeX ile TV'de: Samsung telefonunuzu kabloyla televizyona bağlayıp DeX'te Xbox Remote Play'i açın. Oyunu büyük ekranda, sanki orijinal Türkçe altyazılıymış gibi oynarsınız.",
                    "Rahat oynayın: Çeviriler dokunmayı engellemez, oyunu normal şekilde oynarsınız.",
                    "Tüm ekranı paylaşırsanız: Çeviri altyazının yanında gösterilir. Tam üstünde görmek için \"Tek bir uygulamayı paylaş\" seçeneğini kullanın."
                )
            ),
            HelpTopic(
                "🎬", "Görünüm modları",
                listOf(
                    "Sinematik: Çeviri iri ve net bir altyazı olarak gösterilir. Geçmiş çeviriler yumuşakça akar ve hiçbir zaman altyazının üstüne binmez. İsterseniz Ortadan akış seçilebilir.",
                    "Yazının üstünde: Türkçe çeviri, TV'deki İngilizce yazının tam üstünde, yazıyı saran siyah bir kutuda gösterilir. Yazı boyutu ekrandaki yazıya göre kendiliğinden ayarlanır.",
                    "Ekran Çevirisi: Telefonun kendi ekranındaki oyunu çevirir (yukarıya bakın).",
                    "Hızlı geçiş: Ana ekrandaki mod düğmesine basınca küçük bir menü açılır, modu oradan seçersiniz."
                )
            ),
            HelpTopic(
                "🌐", "Hangi çeviri motorunu seçmeliyim?",
                listOf(
                    "⚡ OPUS: İnternetsiz ve ücretsiz çalışır, Ayarlar'dan bir kez indirilir (355 MB). Günlük diyaloglarda çoğunlukla anlaşılır çeviri verir; argo ve deyimlerde hata yapabilir.",
                    "🌐 Google Translate: İnternet ister, ücretsizdir. Argo ve deyimlerde genellikle daha doğrudur.",
                    "☁️ Google Cloud: Ayarlar'dan açılır ve kendi API anahtarınızla çalışır.",
                    "Değiştirmek için: Ana ekrandaki motor düğmesine dokunun. EN → TR düğmesi de çeviri yönünü değiştirir."
                )
            ),
            HelpTopic(
                "🔊", "Sesli okuma",
                listOf(
                    "Açmak için: Ayarlar'dan sesli okumayı açın. Açıkken ana ekrandaki hoparlör simgesiyle sesi hızlıca kapatıp açabilirsiniz.",
                    "Ses seçenekleri: Dahili ses ücretsizdir ve internet ister. Google Cloud (Chirp 3: HD) sesleri daha doğaldır, kendi API anahtarınızı gerektirir.",
                    "Geride kalıyorsa: Ses hızını Ayarlar'dan artırın; seslendirme altyazıya yetişir."
                )
            )
        )
    ),
    HelpSection(
        title = "💡 Daha iyi sonuç için",
        subtitle = "Okumayı hızlandıran ve doğruluğu artıran taktikler",
        accent = Color(0xFFFFD54F),
        topics = listOf(
            HelpTopic(
                "🎯", "Okumayı iyileştiren taktikler",
                listOf(
                    "Altyazı arka planı: Oyununuz destekliyorsa altyazı arka planını (siyah kutu) açın. Doğruluğu en çok artıran ayar budur.",
                    "Altyazı boyutu: Oyunda altyazıyı büyütmek, uzaktan okumayı belirgin şekilde kolaylaştırır.",
                    "Dar alan: OCR alanını yalnızca altyazıyı kapsayacak kadar daraltın. Alan ne kadar dar olursa sahnedeki diğer yazılar o kadar az karışır ve okuma o kadar hızlı olur.",
                    "☀️ Parlaklık: TV ekranı kameraya genelde fazla parlak gelir. Parlaklığı kıstıkça yazılar netleşir; kaydırıcının alt kısmındaki mor bölge ekstra karartmadır.",
                    "🔍 Odak: Yazı bulanıksa odak kaydırıcısıyla elle netleyin ya da OCR alanına dokunarak yeniden netleyin. AUTO ile otomatik odağa dönersiniz.",
                    "Yansımalar: TV'ye vuran lamba ya da pencere ışığı okumayı bozar. Mümkünse odadaki yansımaları azaltın.",
                    "Beyaz altyazı filtresi: Arka planı olmayan beyaz altyazılarda, dokulu sahnelerin (araba sürerken yol gibi) okumayı bozmasını azaltır. Ayarlar'dan açılır; çeviriyi biraz geciktirebilir."
                )
            ),
            HelpTopic(
                "👁️", "Göz modu ve büyük yazı",
                listOf(
                    "Sade ekran: Göz simgesine basınca düğmeler ve çerçeveler gizlenir, ekranda yalnızca çeviri kalır. Tekrar basınca geri gelir.",
                    "Sinematik'te: Kamera görüntüsü de gizlenir, çeviri karanlık zeminde tek başına durur ve göz yormadan okunur.",
                    "Büyük yazı: Göz modunda son çeviri, uzaktan bir bakışta okunsun diye ekrana sığan en büyük boyuta çıkar. İstemezseniz Ayarlar'dan kapatabilirsiniz."
                )
            ),
            HelpTopic(
                "🧩", "Birden fazla OCR alanı",
                listOf(
                    "Yazının üstünde ve Ekran Çevirisi'nde: Ayarlar'dan en fazla 3 OCR alanı kullanabilirsiniz; örneğin biri altyazı, diğerleri görev ve bilgi mesajları için.",
                    "Ayrı çeviri: Her alan kendi yazısını ayrı çevirir; konumları dikey ve yatay için ayrı kaydedilir."
                )
            ),
            HelpTopic(
                "📜", "Çeviri geçmişi",
                listOf(
                    "Kaçırdığınız cümleler: Menü > Çeviri geçmişi'nde çevrilen altyazılar İngilizceleriyle birlikte listelenir.",
                    "Gizlilik: Geçmiş yalnızca uygulama açıkken tutulur, telefona kaydedilmez. Temizle ile hemen silebilirsiniz."
                )
            )
        )
    ),
    HelpSection(
        title = "❓ Sık sorulan sorular",
        subtitle = "Takıldığınız bir yer varsa cevabı büyük ihtimalle burada",
        accent = Color(0xFFA5D6A7),
        topics = listOf(
            HelpTopic(
                "🤔", "Çeviri gelmiyor, ne yapmalıyım?",
                listOf(
                    "Başlat'a bastığınızdan ve OCR alanının altyazının tam üstünde olduğundan emin olun.",
                    "Kamera modunda: Yazı ekranda küçük ya da bulanıksa yakınlaştırın, odağı ayarlayın ve parlaklığı biraz kısın.",
                    "Ekran Çevirisi'nde: Uzun süre hiç yazı okunmazsa uygulama OCR alanlarını kontrol etmeniz için bir kez ipucu gösterir."
                )
            ),
            HelpTopic(
                "⬛", "Ekran Çevirisi'nde görüntü siyah geliyor",
                listOf(
                    "Yanlış uygulama: Ekran paylaşımında yanlış uygulama seçilmiş olabilir. Durdurup yeniden başlatın ve \"Tek bir uygulamayı paylaş\" seçeneğiyle oyunu oynadığınız uygulamayı seçin.",
                    "Engelleyen uygulama: Seçtiğiniz uygulama ekran paylaşımını engelliyor olabilir. Bu durumda Ekran Çevirisi çalışmaz; oyunu televizyonda ya da monitörde açıp Kamera moduyla çevirebilirsiniz."
                )
            ),
            HelpTopic(
                "🎮", "PS Remote Play neden çalışmıyor?",
                listOf(
                    "Sony, PS Remote Play'de oyun görüntüsünü ekran paylaşımına kapatıyor; uygulamaya yalnızca siyah görüntü geliyor. Bu kısıtlama Sony'den kaynaklanır, uygulama tarafından aşılamaz.",
                    "PlayStation oyunları için: Kamera modunu kullanın; televizyondaki altyazıyı kamerayla okuyup çevirir.",
                    "Xbox Remote Play'de böyle bir kısıtlama yoktur, Ekran Çevirisi sorunsuz çalışır."
                )
            ),
            HelpTopic(
                "📡", "Kablosuz DeX ile neden çalışmıyor?",
                listOf(
                    "Kablosuz DeX (Smart View) da telefonun ekran paylaşımını kullanır; Ekran Çevirisi ile aynı anda çalışamaz ve biri kapanır.",
                    "Çözüm: Telefonunuzu televizyona kabloyla bağlayın."
                )
            ),
            HelpTopic(
                "🔤", "Çeviri yanlış ya da anlamsız geliyor",
                listOf(
                    "Uygulama altyazıyı görüntüden okur; bazı kelimeler yanlış ya da eksik okunabilir ve bu, çeviriyi bozabilir. Bu risk tamamen ortadan kaldırılamaz.",
                    "Okumayı iyileştirmek için yukarıdaki taktikleri deneyin; en etkilisi oyunda altyazı arka planını açmaktır.",
                    "Argo ve deyimlerde Google Translate genellikle OPUS'tan daha doğru sonuç verir."
                )
            ),
            HelpTopic(
                "📶", "İnternetsiz çalışır mı?",
                listOf(
                    "Evet: OPUS'u bir kez indirdiyseniz İngilizce → Türkçe çeviri internetsiz, uçak modunda bile çalışır.",
                    "Google Translate, Google Cloud ve sesli okuma internet ister."
                )
            ),
            HelpTopic(
                "🔄", "Uygulamayı nasıl güncellerim?",
                listOf(
                    "Yeni sürüm çıktığında uygulama sizi bilgilendirir; güncelleme uygulamanın içinden indirilip tek dokunuşla kurulur.",
                    "Uygulama içi güncelleme olmazsa yeni sürümün APK'sını GitHub'daki Releases sayfasından indirip kurabilirsiniz."
                )
            ),
            HelpTopic(
                "🐞", "Sorunu nasıl bildiririm?",
                listOf(
                    "Geliştirici modu: Hakkında ekranında sürüm yazısına 7 kez dokunun; uygulama kayıt almaya başlar.",
                    "Sorunu yaşayın: Uygulamayı sorunun yaşandığı şekilde kullanın.",
                    "Kaydı gönderin: Hakkında > Sorun bildir kısmında Kaydı indir ya da Kaydı paylaş'a basın, dosyayı GitHub'daki Issues sayfasına ekleyin.",
                    "Kayıtta okunan altyazılar, çeviriler, telefon modeli ve ayarlar bulunur; kamera görüntüleri dosyaya eklenmez."
                )
            )
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    // Açık kartlar ekran dönünce de açık kalsın
    var openTopic by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nasıl Kullanılır", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
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
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { HelpHero() }
                HELP_SECTIONS.forEach { section ->
                    item(key = section.title) { HelpSectionHeader(section) }
                    section.topics.forEach { topic ->
                        item(key = topic.title) {
                            HelpTopicCard(
                                topic = topic,
                                accent = section.accent,
                                expanded = openTopic == topic.title,
                                onToggle = { openTopic = if (openTopic == topic.title) null else topic.title }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Sayfanın başındaki karşılama kartı */
@Composable
private fun HelpHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF3949AB), Color(0xFF00897B))),
                RoundedCornerShape(20.dp)
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📖  A'dan Z'ye Camera Translator", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "Kurulumdan ince ayarlara kadar bilmeniz gereken her şey burada. Bir konuya dokunun, ayrıntısı açılsın.",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun HelpSectionHeader(section: HelpSection) {
    Column(modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp)) {
        Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(section.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpTopicCard(topic: HelpTopic, accent: Color, expanded: Boolean, onToggle: () -> Unit) {
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "helpArrow")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SectionCardStyle.cardColor(darkAlpha = 0.45f),
        border = SectionCardStyle.cardBorder(),
        shadowElevation = SectionCardStyle.cardShadow()
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(topic.emoji, fontSize = 20.sp)
                }
                Text(
                    topic.title,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Kapat" else "Aç",
                    modifier = Modifier.rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    topic.points.forEach { HelpPoint(it, accent) }
                }
            }
        }
    }
}

/** Bir madde: "Başlık: açıklama" biçimindeyse başlık kalın yazılır */
@Composable
private fun HelpPoint(text: String, accent: Color) {
    val colon = text.indexOf(": ")
    val hasLabel = colon in 1..40
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.padding(top = 7.dp).size(6.dp).background(accent, CircleShape)
        )
        Text(
            buildAnnotatedString {
                if (hasLabel) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(0, colon + 1)) }
                    append(text.substring(colon + 1))
                } else {
                    append(text)
                }
            },
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
