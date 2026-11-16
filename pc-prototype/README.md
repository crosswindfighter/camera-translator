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
2. **BAŞLAT** — 4 saniye geri sayar, o sırada oyun penceresine geç. Uygulama o pencereyi
   kilitler; başka pencereye geçince okumayı durdurur.
3. `F8` duraklat/devam, `F9` durdur.

**Görünüm:** yazı tipi, yazı rengi, kutu rengi ve saydamlık ayarlanabilir. Yazı boyutu
ayarlanmaz — İngilizce altyazının boyuna göre kendini ayarlar, böylece kutu yerinden oynamaz.
Başlık çubuğundaki ay/güneş düğmesi gece ve gündüz modu arasında geçer.

**Ayrıntılı kayıt:** her okumayı, çeviriyi, çizimi ve kusuru `kayit_TARIH.txt` dosyasına yazar.
Bir sorunu bildirirken bu dosya işe yarar.

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

Lisans: deponun kökündeki [LICENSE.md](../LICENSE.md) (MIT). Üçüncü taraf bileşenler için
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).
