# Camera Translator — PC sürümü

Oyun altyazılarını ekrandan okur, Türkçeye çevirir ve **İngilizce yazının tam üstüne** basar.
Oyun Türkçe altyazılıymış gibi görünür.

Mobil sürümün kardeşi: aynı çeviri motorunu (OPUS-MT), aynı sözlükleri ve aynı düzeltme
mantığını kullanır. Tamamı çevrimdışı çalışır; hiçbir veri dışarı gitmez.

```
ekran alanı  →  PP-OCR  →  düzeltme  →  OPUS çeviri  →  şeffaf katman
```

Gerçek bir oyun kaydında ölçülen gecikme (altyazı ekranda belirdikten Türkçesi görünene
kadar): **medyan 173 ms**, onda dokuzu 331 ms'nin altında.

---

## Kurulum

### Hazır paket (önerilen)

Sürümler sayfasındaki ZIP'i indir, bir klasöre çıkar, **`CameraTranslator.exe`**'ye çift tıkla. Bu kadar — Python kurmana, komut yazmana, model indirmene gerek yok; hepsi paketin içinde.

Paket hiçbir şey kurmaz. Klasörü nereye koyarsan oradan çalışır, silince de iz bırakmaz.

> **Açılmazsa:** klasörde `baslatma_kaydi.txt` oluşur, hatayı orada yazar. Program ayrıca bir uyarı penceresiyle sebebini gösterir.

> **Windows uyarı verebilir.** Paket imzasız olduğu için SmartScreen "Bilgisayarınız korundu" diyebilir — **Daha fazla bilgi → Yine de çalıştır**. Bazı antivirüsler de PowerShell çalıştıran programlara şüpheyle bakar; dosya silinirse klasörü taramadan hariç tut.

Program güncelleme olup olmadığını açılışta bir kere kontrol eder. Yeni sürüm varsa başlık çubuğundaki indirme simgesinin köşesinde turuncu bir nokta belirir ve bir kere sorar; "hayır" dersen bir daha sormaz ama nokta kalır. Simgeye basınca indirme sayfası açılır.

Paketi kendin üretmek istersen (depodan):

```bash
.\paket_yap.ps1
```

Masaüstünde `CameraTranslator` klasörü oluşturur; `-Zip` eklersen ZIP de üretir.

---

### Sürüm yayınlama (depo sahibi için)

**1.** `app.ps1` içindeki `$APP_SURUM` değerini yükselt. Tek yer burası; paket betiği sürümü oradan okur.

**2.** İki ZIP'i üret:

```bash
.\paket_yap.ps1 -Zip
```

Masaüstünün yanına iki dosya çıkar:

| dosya | ne zaman |
|---|---|
| `CameraTranslator-pc-v1.0.0.zip` | ilk kurulum — her şey içinde, ~650 MB |
| `CameraTranslator-pc-v1.0.0-guncelleme.zip` | sonraki sürümler — sadece program, ~11 MB |

**3.** GitHub'da **Releases → Draft a new release**. Etiket **`pc-v` + sürüm** olmalı (örnek: `pc-v1.0.1`). Bu önek şart: mobil APK sürümleri de aynı depoda yayınlanıyor, program yalnızca `pc-v` ile başlayanlara bakıyor.

**4.** **"Set as the latest release" kutusunu İŞARETLEME.** Mobil uygulama güncelleme ararken `/releases/latest` adresine bakıyor (`UpdateChecker.kt`). PC sürümü "latest" olursa mobil uygulama bir daha yeni APK göremez — yayınlasan bile "güncelsiniz" der. PC sürümleri bu kutu kapalı yayınlanmalı; `pc-v` etiketlerini zaten tüm sürüm listesinden buluyoruz.

**5.** İki ZIP'i de sürüme ekle, yayınla. Kullanıcılar bir sonraki açılışta uyarıyı görür.

İlk sürümde (`pc-v1.0.0`) güncelleme ZIP'ini yüklemeye gerek yok: kimsede önceki kurulum olmadığı için işe yaramaz, sadece hangisini indireceği konusunda kafa karıştırır.

Güncelleme ZIP'i mevcut klasörün üstüne çıkarılır — `python\` ve `models\` yerinde kalır, sadece program dosyaları değişir. Ayarlar ve OCR alanları korunur.

Kendi makinendeki test paketini tazelemek için (2 saniye, Python ve modele dokunmaz):

```bash
.\paket_yap.ps1 -SadeceProgram
```

---

### Elle kurulum (geliştirici)

Beş adım. Hepsi bir kere yapılır.

### 1. Python kur

[python.org](https://www.python.org/downloads/) adresinden Python 3.10 veya üstünü indir.
Kurulum ekranındaki **"Add python.exe to PATH"** kutusunu işaretlemeyi unutma.

### 2. Programı indir

GitHub'daki depo sayfasında yeşil **Code** düğmesi → **Download ZIP**. İnen dosyayı
istediğin yere çıkar; işin tamamı `pc-prototype` klasöründe.

> Windows, internetten inen dosyalara "başka bilgisayardan geldi" damgası vurur ve
> betiği açmayı reddedebilir. ZIP dosyasına sağ tıkla → **Özellikler** → altta
> **Engellemeyi kaldır** kutusunu işaretle → Tamam, sonra çıkar.

### 3. Paketleri kur

`pc-prototype` klasöründe PowerShell aç (klasörde boş bir yere `Shift` + sağ tık →
"PowerShell penceresini burada aç") ve iki komutu sırayla çalıştır:

```bash
python -m venv .venv
```

```bash
.venv\Scripts\pip install numpy onnxruntime-directml rapidocr-onnxruntime
```

Ekranı okuyan modeller `rapidocr-onnxruntime` paketinin içinden geliyor, ayrıca bir şey
indirmen gerekmiyor.

`onnxruntime-directml`, okumayı ekran kartında çalıştırır ve kare başına 40 ms'ye indirir.
Ekran kartın yoksa ya da kurulum hata verirse `onnxruntime-directml` yerine düz
`onnxruntime` kur — program işlemciyle çalışmaya devam eder, okuma aynı doğrulukta olur,
sadece kare başı ~250 ms sürer. Hangisini kullandığını açılışta pencereye yazar.

### 4. Çeviri modelini indir

Depoya sığmayan tek parça bu (GitHub dosya başına 100 MB sınırı koyuyor). Mobil sürümün
yayınladığı dosyaların aynısı:
**[offline-model-v2](https://github.com/crosswindfighter/camera-translator/releases/tag/offline-model-v2)**

| dosya | boyut |
|---|---|
| `encoder_model.onnx` | 135 MB |
| `decoder_model_merged.onnx` | 220 MB |
| `source_pieces.tsv` | 966 KB |
| `vocab.json` | 1,5 MB |

Dördünü `pc-prototype\models\opus_en_tr\` klasörüne koy. Klasör yoksa oluştur.

### 5. Çalıştır

`Baslat.vbs` dosyasına çift tıkla. Konsol penceresi açılmaz.

Masaüstüne kısayol yapıp `app.ico` simgesini verebilirsin. Konsolu görmek istersen:

```bash
powershell -ExecutionPolicy Bypass -STA -File app.ps1
```

Betikler Python'u şu sırayla arar: `-Python` parametresi → `CT_PYTHON` ortam değişkeni →
yanındaki `.venv` → `PATH`. Model dosyalarını başka bir yere koyduysan `OPUS_DIR` ortam
değişkeniyle gösterebilirsin.

---

## Kullanım

1. **OCR alanını çiz** — ekran kararır, altyazının çıktığı yerin etrafına dikdörtgen
   çizersin. İçinden tutup taşıyabilir, köşelerinden boyutlandırabilirsin. `Enter` kaydeder.
2. **BAŞLAT** — 4 saniye geri sayar, o sırada oyuna geç.
3. `F8` duraklat/devam, `F9` durdur.

Okuma yalnızca Camera Translator penceresi önde olduğunda durur; başka her pencerede
devam eder. Yani ikinci ekrana tıklayıp geri döndüğünde çeviri kaldığı yerden sürer.

**+ OCR alanı ekle:** ikinci, üçüncü bir alan tanımlayabilirsin — görev yazısı,
telefon ekranı, ne okutmak istersen. Ana alanla aralarında bir fark var: ana alanda
çeviri gerekirse alanın dışına taşarak İngilizcenin üstünü kapatır, **ek alanlarda ise
çerçevenin içinde kalır** ve sığmazsa punto küçülür. Alanı yanındaki kırmızı çarpıyla
silersin.

**Görünüm:** yazı tipi, yazı rengi ve kutu rengi ayarlanabilir. Yazı boyutu ayarlanmaz —
İngilizce altyazının boyuna göre kendini ayarlar, böylece kutu yerinden oynamaz.
Başlık çubuğundaki ay/güneş düğmesi gece ve gündüz modu arasında geçer.

**Güncelleme:** soldaki indirme simgesi açılışta bir kere yeni sürüm olup olmadığına
bakar. Varsa köşesinde turuncu nokta belirir ve bir kere sorar; "hayır" dersen bir daha
sormaz ama nokta kalır. Simgeye basınca indirme sayfası açılır.

**Ayrıntılı kayıt:** her okumayı, çeviriyi, çizimi ve kusuru `kayit_TARIH.txt` dosyasına yazar.
Bir sorunu bildirirken bu dosya işe yarar. Açıkken okunan kareler de klasöre kaydedilir,
o yüzden gerekmedikçe kapalı tutmakta fayda var.

---

## Gelişmiş OCR düzeltme (isteğe bağlı, GPU)

Bozuk okunan **İngilizceyi** yerel bir dil modeline onartır; çeviriyi yine OPUS yapar.

```
"You owe roe. Michael."           →  "You owe me. Michael."
"Did ichael tell you€about Lif"   →  "Did Michael tell you about Life?"
```

Kurulum:

```bash
winget install Ollama.Ollama
```

```bash
ollama pull aya-expanse:8b
```

Sonra uygulamada **Gelişmiş OCR düzeltme** anahtarını aç.

> **Açık söyleyelim: bu seçenek artık pek bir işe yaramıyor.** Windows OCR kullanırken
> cümlelerin ~%22'si düzeltmeye gidiyordu. PP-OCR'a geçtikten sonra okuma o kadar temiz
> geliyor ki son gerçek oyun kaydında **bir kez bile tetiklenmedi**. Karşılığında 5 GB
> bellek tutuyor. Kapalı bırakmanı öneririz; kutu, ileride lazım olur diye duruyor.

Model yalnızca İngilizceyi onarır; Türkçeye çeviren her zaman OPUS'tur. Modelin çeviri
yapması da denendi ve vazgeçildi: küfrü ve argoyu yumuşatıyor, kelime düşürüyor.
Modelin cevabı orijinale yeterince benzemiyorsa reddedilir — uydurmaya karşı koruma.

> **Lisans notu:** Aya Expanse **CC-BY-NC** (ticari olmayan kullanım) lisanslıdır.
> Bu seçenek kapalıyken uygulama ona hiç dokunmaz.

---

## Nasıl çalışıyor

- **OCR** — PP-OCR (PaddleOCR) modelleri, ONNX üzerinde (`rapidocr-onnxruntime`).
  Windows'un kendi motoruyla gerçek oyun kareleri üzerinde karşılaştırıldı: gerçek kelime
  oranı %76 → %83, hiç okunamayan kare 40 → 20, ve en önemlisi aynı altyazıyı kareden
  kareye aynı okuyor (Windows motoru `Dont / Dohrt / d6n't` diye her karede farklı
  okuyordu). Ekran kartında kare başı ~40 ms, işlemcide ~250 ms.
- **Açı sınıflandırıcısı kapalı** — PP-OCR yan yatmış belgeleri düzeltmek için satırları
  döndürebiliyor. Oyun altyazısı hiç dönük olmadığı halde ara sıra düzgün satırı ters
  sanıp 180 derece çeviriyordu; kapatmak okumayı hiç değiştirmedi, üstüne yarı yarıya
  hızlandırdı.
- **Alan filtresi** — PP-OCR metni ayrı bloklar hâlinde verir; yalnızca alanın ortasına
  denk gelenler tutulur, kenardaki HUD yazıları atılır.
- **Kararlılık** — altyazı her karede biraz farklı okunabilir. 1,5 saniyelik pencerede iki
  benzer okuma birikince onaylanır; araya bozuk kareler girse de olur. Kelime oylamasıyla
  en güvenilir okuma seçilir.
- **Düzeltme** — sözlük tabanlı düzeltici (`sorne` → `some`, `ofyou` → `of you`), argo
  sadeleştirici, ve isteğe bağlı LLM düzelticisi.
- **Çeviri** — OPUS-MT (ONNX), işlemcide. Çıktı şüpheli görünürse bir de beam search denenir.
- **Çizim** — katman `SetWindowDisplayAffinity` ile kendi ekran yakalamamızdan gizlenir;
  yoksa bastığımız Türkçeyi OCR tekrar okurdu.

---

## Bilinen sınırlar

- Çeviri kalitesi OPUS'un sınırına bağlı. Deyimlerde ve ağız taklidinde zorlanır
  (`You wanna get lit now?` → "Şimdi yanmak ister misin?").
- Oyunun süslü/stilize fontları (ör. RDR2 görev yazısı) OCR tarafından okunamıyor.
- Kaynak görüntü ne kadar sıkıştırılmışsa (YouTube, uzak oynatma) OCR o kadar oynar,
  gecikme artar.
- Yalnızca İngilizce → Türkçe.

---

## Dosyalar

| | |
|---|---|
| `app.ps1` | uygulama penceresi, yakalama döngüsü, katman |
| `ocr.py` | PP-OCR sarmalayıcısı ve alan filtresi |
| `translate.py` | OPUS çeviri motoru (ONNX), süreç olarak çalışır |
| `corrector.py` | sözlük tabanlı OCR düzeltici |
| `slang.py` | argo/deyim sadeleştirici |
| `ocrfix.py` | isteğe bağlı LLM OCR düzelticisi |
| `llm.py` | Ollama istemcisi |
| `compare.py` | A/B ölçüm aracı |
| `paket_yap.ps1` | taşınabilir paketi üretir |

Lisans: deponun kökündeki [LICENSE.md](../LICENSE.md) (MIT). Üçüncü taraf bileşenler için
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).
