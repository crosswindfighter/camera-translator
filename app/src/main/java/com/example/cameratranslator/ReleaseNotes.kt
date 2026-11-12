package com.example.cameratranslator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sürüm notları: güncellemeden sonraki ilk açılışta bir kez gösterilir ("Yenilikler").
 * Yeni sürümde buraya o sürümün maddeleri eklenir; notu olmayan sürümde pencere çıkmaz.
 */
object ReleaseNotes {
    /** Her "Yenilikler" penceresinin altında gösterilen teşekkür yazısı */
    const val THANKS =
        "Sorun bildiren, kayıt gönderen ve öneride bulunan herkese çok teşekkürler. Bu sürümdeki düzeltmelerin çoğu " +
            "sizden gelen geri bildirimler sayesinde oldu. Uygulama sizinle birlikte gelişiyor; desteğiniz için minnettarız. 💙"

    private val NOTES = mapOf(
        "1.1.4" to listOf(
            "Yeni Nasıl kullanılır sayfası: menüden açılır. Uygulamanın A'dan Z'ye kullanımı, daha iyi sonuç için taktikler ve sık sorulan sorular burada.",
            "Ekran Çevirisi'nde siyah ekran uyarısı: ekran paylaşımından görüntü simsiyah gelirse ekranın ortasında uyarı çıkar. Yanlış uygulama seçildiğinde ya da seçilen uygulama ekran paylaşımını engellediğinde ne yapmanız gerektiğini anlatır.",
            "Ekran Çevirisi'nin uyarıları, bildirimler kapalı olsa bile çeviri ekranının ortasında gösteriliyor.",
            "Bir süre hiç yazı okunmazsa OCR alanlarını kontrol etmeniz için bir kez ipucu çıkar. Metin okuma çalışmazsa da uyarı gösterilir.",
            "Kaydı indir: Hakkında > Sorun bildir kısmından kayıt dosyasını telefonun İndirilenler klasörüne kaydedebilirsiniz.",
            "Ekran Çevirisi'nde sorunları bulmak için daha fazla bilgi kaydediliyor.",
            "Ekran Çevirisi açıkken telefonu döndürünce uygulamanın kapanması düzeltildi.",
            "PS Remote Play'in Ekran Çevirisi'nde çalışmadığı açıklamalara eklendi. Sony oyun görüntüsünü ekran paylaşımına kapatıyor; PlayStation için Kamera modunu kullanın. Xbox Remote Play sorunsuz çalışıyor."
        ),
        "1.1.3" to listOf(
            "Metin okuma artık uygulamanın içinde geliyor; Google Play Hizmetleri'ne bağlı değil. Bazı telefonlarda çevirinin \"Altyazı bekleniyor\" yazısında takılı kalması sorunu düzeltildi.",
            "Metin okuma bir sorun yüzünden çalışamazsa ekranda ne yapmanız gerektiğini söyleyen bir uyarı çıkıyor.",
            "Sorun bildirmek artık daha kolay: Hakkında'da sürüm yazısına 7 kez dokunarak geliştirici modunu açın, uygulama kayıt almaya başlar. Kaydı paylaş ile hazırlanan dosyayı GitHub'daki Issues sayfasından geliştiriciye iletebilirsiniz.",
            "Ekran Çevirisi'nde çeviri, İngilizce yazı ekrandan kalkınca daha çabuk (yaklaşık 3 saniyede) kayboluyor."
        ),
        "1.1.2" to listOf(
            "Ayarlar yenilendi: görünüm modu iki seçenekte toplandı, Kamera ve Ekran Çevirisi. Kamerada Sinematik ile televizyondaki yazının üstünde gösterim arasında seçim yaparsınız. Kaydırıcılar ve açıklamalar da yenilendi.",
            "Ana ekrandaki mod düğmesi artık modları sırayla değiştirmiyor; seçenekleri gösteren küçük bir menü açıyor.",
            "Göz modunda büyük yazı: göz simgesine basınca son çeviri ekrana sığan en büyük boyuta çıkar. Geçmiş çeviriler açıkken de çalışır; Ayarlar'dan kapatılabilir.",
            "Yazının üstünde: Başlat'a basınca OCR alanlarının çerçevesi gizlenir, siyah kutu yazıyı daha sıkı sarar ve yazı boyutu tamamen ekrandaki yazıya göre ayarlanır.",
            "Durdurup hemen yeniden başlatınca ekrandaki altyazının çevrilmemesi düzeltildi.",
            "Bazı OCR okuma hataları daha düzeltildi."
        ),
        "1.1.1" to listOf(
            "OPUS çeviri motoru altyazı verisiyle yeniden eğitildi: günlük konuşmaları ve argoyu daha doğal çeviriyor. OPUS'u daha önce indirdiyseniz Ayarlar > ⚡ OPUS Çeviri Motoru'ndan güncelleyebilirsiniz (355 MB); indirme bitene kadar mevcut model çalışmaya devam eder.",
            "Sinematik Altyazı'ya Ortadan akış seçeneği: çeviri, OCR alanının yerinden bağımsız olarak ekranın ortasında belirir, geçmiş çeviriler yukarı doğru akar.",
            "Geçmiş çeviriler kapalıyken göz modunda yazı, ekrana sığan en büyük boyuta çıkar. Görünen geçmiş satır sayısı 20'ye kadar artırılabilir.",
            "Daha fazla OCR hatası ve yanlış çevrilen argo kalıbı düzeltildi; doğru okunan bazı kelimelerin yanlışlıkla değiştirilmesi önlendi.",
            "Dikkatli çeviri ayarı kaldırıldı. Yarım kalan ya da bozuk görünen çeviriler yine kendiliğinden yeniden deneniyor.",
            "Uygulama içi güncellemede indirilen dosya kurulumdan önce doğrulanıyor; eksik ya da bozuk inen dosya kuruluma gönderilmiyor."
        ),
        "1.1.0" to listOf(
            "Yapışık okunan \"I'm a\" gibi kalıplar artık doğru çevriliyor.",
            "Beyaz altyazı filtresi çeviriyi biraz geciktirebildiği için varsayılan olarak kapalı; isterseniz Ayarlar'dan açabilirsiniz.",
            "Güncelleme kurulduktan sonra indirilen dosya telefondan otomatik siliniyor."
        ),
        "1.0.9" to listOf(
            "Ekran Çevirisi daha hızlı: yazılar daha sık okunuyor, çeviri ekrana daha çabuk geliyor.",
            "Ekran Çevirisi'ne yumuşak geçiş: çeviri hafifçe kayarak belirir ve yumuşakça kaybolur. Ayarlar'dan kapatabilir, hızını Yavaş veya Hızlı yapabilirsiniz.",
            "Ekran Çevirisi'nin ana ekranı yenilendi: daha şık görünüm ve adım adım anlatım.",
            "Açık renkli zeminlerde (beton, asfalt, gökyüzü) beyaz altyazılar daha iyi okunuyor. Beyaz altyazı filtresi artık tüm modlarda çalışıyor; isterseniz Ayarlar'dan açabilirsiniz.",
            "Çeviri kutusu sahnedeki başka yazılar (ceketteki logo gibi) yüzünden büyümüyor ve kaymıyor; çevrilmeden dönen bir cümle yüzünden çeviri altyazının üstünden ayrılmıyor.",
            "OPUS indirme ekranı yenilendi: OCR alanının üzerine binmiyor, \"Daha sonra\" seçeneğiyle kapatılabiliyor.",
            "Telefon döndürüldüğünde OCR alanının yeri doğru geri geliyor.",
            "OCR satırın son harflerini kaçırdığında çeviri, yazının tamamı görülünce düzeltiliyor.",
            "Yapışık okunan kelimeler ayrılıyor, yanlış çevrilen sık kalıplar düzeltildi.",
            "Google Translate'in kısa cümlelerdeki hataları giderildi: \"Tamam aşkım\" yerine \"Tamam\", \"Beklemek\" yerine \"Bekle\".",
            "Zoom açıklamaları tüm telefonlara uyacak şekilde yeniden yazıldı."
        ),
        "1.0.8" to listOf(
            "Yeni Ekran Çevirisi modu: kamera kullanmadan telefonun kendi ekranını okur. Samsung telefonunuzu kabloyla televizyona bağlayıp DeX'te Xbox veya PlayStation Remote Play'i açtığınızda oyun büyük ekranda çok net görünür; Türkçe altyazılar doğrudan İngilizce altyazının üstüne gelir ve televizyonda görünür. Oyunu televizyonda, sanki orijinal Türkçe altyazılıymış gibi oynarsınız.",
            "Oyunu oynarken dokunmayı engellemeyen çeviri katmanı ve kenardaki yüzen balon: OCR alanlarını oyunun içinden yerleştirin, uygulamaya dönün ya da durdurun.",
            "Görünüm modları yeniden adlandırıldı: Sinematik Altyazı, Yerinde Çeviri (eski adıyla Ekran Çevirisi) ve Ekran Çevirisi. Sağdaki mod düğmesi üçü arasında geçer ve artık varsayılan olarak açık.",
            "Ayarlar'da her modun ne işe yaradığını anlatan açıklamalar.",
            "Samsung DeX desteği: uygulama pencerede boyutlandırılabilir, DeX'e geçerken yeniden başlamaz."
        ),
        "1.0.7" to listOf(
            "Yerinde Çeviri (eski adıyla Ekran Çevirisi) yenilendi: çeviri, İngilizce yazının tam üstünde yazıyı saran siyah bir kutuda gösterilir; yazı boyutu ekrandaki yazıya göre ayarlanır, kutu düğmelerin üstüne binmez, yazı kaybolunca çeviri de kaybolur.",
            "Yerinde Çeviri'de 3'e kadar OCR alanı: altyazının yanında görev ve bilgi mesajlarını da ayrı ayrı çevirebilirsiniz.",
            "Akıllı akış: çeviriler OCR alanının konumuna göre kendiliğinden akar. OCR alanı aşağıdaysa yeni çeviri üstünde belirir ve geçmiş çeviriler yukarı doğru, yukarıdaysa altında belirir ve aşağı doğru yumuşakça akar.",
            "Yeni Çeviri geçmişi ekranı: kaçırdığınız altyazılara menüden bakabilirsiniz.",
            "Kendini denetleyen çeviri: yarım kalan ya da bozuk görünen çeviriler otomatik olarak yeniden denenir.",
            "Daha doğru okuma ve çeviri: sık karşılaşılan OCR hataları ve argo kalıpları düzeltildi.",
            "Uygulama içinden güncelleme: yeni sürüm tek dokunuşla indirilip kurulabilir.",
            "İlk açılış rehberi ve yenilenen görünüm: renkli simgeler, yeni menü, daha derli toplu ayarlar, sade açılış ekranı.",
            "Daha kararlı: telefon döndürülünce kamera kapanıp açılmaz, Ayarlar'dayken kamera kapanır (pil), OCR alanlarının yeri dikey ve yatay için ayrı kaydedilir."
        )
    )

    fun notesFor(version: String): List<String>? = NOTES[version]
}

/** "Yenilikler" penceresi: bu sürümde gelen değişiklikler. */
@Composable
fun WhatsNewDialog(version: String, notes: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎉 v$version ile gelen yenilikler") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                notes.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(note, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Text(
                    ReleaseNotes.THANKS,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Harika") } }
    )
}
