package com.example.cameratranslator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
    private val NOTES = mapOf(
        "1.0.8" to listOf(
            "Yeni Ekran Çevirisi modu: telefonun kendi ekranını okur, oyunun altyazısını oyunun üstünde, yazının tam yerine çevirir. En iyi sonuç için Samsung telefonu kabloyla TV'ye bağlayıp DeX'te Xbox veya PlayStation Remote Play ile oynayın.",
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Harika") } }
    )
}
