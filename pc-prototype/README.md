# Camera Translator — PC sürümü

Oyun altyazılarını ekrandan okur, Türkçeye çevirir ve **İngilizce yazının tam üstüne** basar.
Oyun Türkçe altyazılıymış gibi görünür.

Mobil sürümün kardeşi: aynı çeviri motorunu (OPUS-MT), aynı sözlükleri ve aynı
OCR düzeltme mantığını kullanır. Tamamı çevrimdışı çalışır; hiçbir veri dışarı gitmez.

```
ekran alanı  →  Windows OCR  →  düzeltme  →  OPUS çeviri  →  şeffaf katman
```

Ölçülen gecikme: altyazı ekranda belirdikten Türkçesi görünene kadar **ortalama ~250 ms**.

---

## Gereksinimler

| | |
|---|---|
| Windows 10 / 11 | OCR için `en-US` dil paketi kurulu olmalı |
| Python 3.10+ | `numpy` ve `onnxruntime` paketleriyle |
| OPUS model dosyaları | aşağıda |
| *(isteğe bağlı)* Ollama + GPU | gelişmiş OCR düzeltme için |

Windows'un OCR motoru kurulu mu, şöyle bakarsın:

```powershell
powershell -Command "[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]::AvailableRecognizerLanguages | ForEach-Object LanguageTag"
```

Listede `en-US` yoksa: **Ayarlar → Saat ve dil → Dil ve bölge → İngilizce (ABD) → Dil seçenekleri → İsteğe bağlı özellikler → Optik karakter tanıma**.

---

## Kurulum

**1. Python paketleri**

```bash
python -m venv .venv
.venv\Scripts\pip install numpy onnxruntime
```

Betikler Python'u şu sırayla arar: `-Python` parametresi → `CT_PYTHON` ortam
değişkeni → yanındaki `.venv` → `PATH`.

**2. Çeviri modeli**

Mobil sürümün yayınladığı dosyaların aynısı:
[offline-model-v2](https://github.com/crosswindfighter/camera-translator/releases/tag/offline-model-v2)

| dosya | boyut |
|---|---|
| `encoder_model.onnx` | 135 MB |
| `decoder_model_merged.onnx` | 220 MB |
| `source_pieces.tsv` | 966 KB |
| `vocab.json` | 1,5 MB |

Dördünü `pc-prototype/models/opus_en_tr/` klasörüne koy. (Başka bir yere koyduysan
`OPUS_DIR` ortam değişkeniyle göster.)

**3. Çalıştır**

```bash
powershell -ExecutionPolicy Bypass -STA -File app.ps1
```

Konsol penceresi istemiyorsan `Baslat.vbs` dosyasına çift tıkla; istersen masaüstüne
kısayol yapıp `app.ico` simgesini verebilirsin.

---

## Kullanım

1. **OCR alanını çiz** — ekran kararır, altyazının çıktığı yerin etrafına dikdörtgen
   çizersin. İçinden tutup taşıyabilir, köşelerinden boyutlandırabilirsin. `Enter` kaydeder.
2. **BAŞLAT** — 4 saniye geri sayar, o sırada oyun penceresine geç. Uygulama o pencereyi
   kilitler; başka pencereye geçince okumayı durdurur.
3. `F8` duraklat/devam, `F9` durdur.

**Görünüm:** yazı tipi, yazı rengi, kutu rengi ve saydamlık ayarlanabilir. Yazı boyutu
ayarlanmaz — İngilizce altyazının boyuna göre kendini ayarlar, böylece kutu yerinden oynamaz.

**Ayrıntılı kayıt:** her okumayı, çeviriyi, çizimi ve kusuru `kayit_TARIH.txt` dosyasına yazar.
Bir sorunu bildirirken bu dosya işe yarar.

---

## Gelişmiş OCR düzeltme (isteğe bağlı, GPU)

Sıkıştırılmış görüntüde OCR kelimeleri bozabiliyor. Bu seçenek, bozuk **İngilizceyi**
yerel bir dil modeline onartır; çeviriyi yine OPUS yapar.

```
"You owe roe. Michael."           →  "You owe me. Michael."
"Did ichael tell you€about Lif"   →  "Did Michael tell you about Life?"
```

Kurulum:

```bash
winget install Ollama.Ollama
ollama pull aya-expanse:8b
```

Sonra uygulamada **Gelişmiş OCR düzeltme** kutusunu işaretle. Ollama kapalıysa uygulama
kendisi başlatır; model yanıt vermezse sessizce normal yola düşer.

Model yalnızca **İngilizceyi onarır**; Türkçeye çeviren her zaman OPUS'tur. Modelin
çeviri yapması da denendi ve vazgeçildi: küfrü ve argoyu yumuşatıyor, kelime düşürüyor.

Cümlelerin yalnızca ~%22'si modele gider (şüpheli kelime içerenler), ortalama ek maliyet
**+34 ms**. Modelin cevabı orijinale yeterince benzemiyorsa reddedilir — uydurmaya karşı koruma.

> **Lisans notu:** Aya Expanse **CC-BY-NC** (ticari olmayan kullanım) lisanslıdır.
> Bu seçenek kapalıyken uygulama ona hiç dokunmaz.

---

## Nasıl çalışıyor

- **OCR** — Windows'un kendi motoru (`Windows.Media.Ocr`). Kurulum gerektirmez, çevrimdışıdır.
- **Alan filtresi** — bir satırdaki kelimeler boşluklara göre gruplanır; yalnızca ortayı
  kapsayan gruplar tutulur, kenardaki HUD yazıları atılır.
- **Kararlılık** — altyazı her karede biraz farklı okunur. 1,5 saniyelik pencerede iki benzer
  okuma birikince onaylanır; araya bozuk kareler girse de olur. Kelime oylamasıyla en güvenilir
  okuma seçilir.
- **Düzeltme** — sözlük tabanlı düzeltici (`sorne` → `some`, `ofyou` → `of you`), argo
  sadeleştirici, ve isteğe bağlı LLM düzelticisi.
- **Çeviri** — OPUS-MT (ONNX). Çıktı şüpheli görünürse bir de beam search denenir.
- **Çizim** — katman `SetWindowDisplayAffinity` ile kendi ekran yakalamamızdan gizlenir;
  yoksa bastığımız Türkçeyi OCR tekrar okurdu.

---

## Bilinen sınırlar

- Çeviri kalitesi OPUS'un sınırına bağlı. Deyimlerde zorlanır
  (`face the cops head on` → "polislerin kafalarına bakman").
- Oyunun süslü/stilize fontları (ör. RDR2 görev yazısı) OCR tarafından okunamıyor.
- Kaynak görüntü ne kadar sıkıştırılmışsa (YouTube, uzak oynatma) OCR o kadar oynar,
  gecikme artar.
- Yalnızca İngilizce → Türkçe.

---

## Dosyalar

| | |
|---|---|
| `app.ps1` | uygulama penceresi, yakalama döngüsü, katman |
| `translate.py` | OPUS çeviri motoru (ONNX), süreç olarak çalışır |
| `corrector.py` | sözlük tabanlı OCR düzeltici |
| `slang.py` | argo/deyim sadeleştirici |
| `ocrfix.py` | isteğe bağlı LLM OCR düzelticisi |
| `llm.py` | Ollama istemcisi |
| `compare.py` | A/B ölçüm aracı |
| `ocr_probe.ps1` | OCR hız/doğruluk sondası (tanı için) |

Lisans: deponun kökündeki [LICENSE.md](../LICENSE.md) (MIT). Üçüncü taraf bileşenler için
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).
