# Üçüncü Taraf Bildirimleri (Third-Party Notices)

Camera Translator'ın kaynak kodu [MIT lisansı](LICENSE.md) ile yayımlanır. Uygulama aşağıdaki üçüncü taraf bileşenleri kullanır; bu bileşenler **kendi lisanslarına** tabidir ve MIT lisansının kapsamında değildir.

---

## ⚡ OPUS çeviri modeli — OPUS-MT (Helsinki-NLP)

- **Model:** [Helsinki-NLP/opus-mt-tc-big-en-tr](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-tr)
- **Geliştiren:** Language Technology Research Group, University of Helsinki — OPUS-MT / Tatoeba Translation Challenge
- **Lisans:** [Creative Commons Attribution 4.0 International (CC-BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- **Yapılan değişiklikler:** Uygulamanın indirdiği model dosyaları, orijinal modelin
  - ONNX formatına dönüştürülmüş,
  - 8-bit (int8) dinamik kuantizasyonla sıkıştırılmış,
  - tokenizer sözlüğü (`source_pieces.tsv`) düz metin olarak çıkarılmış

  halidir. Model ağırlıkları yeniden eğitilmemiştir. Dönüştürme betikleri: [tools/offline_model](tools/offline_model).
- **Dağıtım:** Dönüştürülmüş dosyalar bu reponun [`offline-model-v1`](https://github.com/crosswindfighter/camera-translator/releases/tag/offline-model-v1) sürümünde yayımlanır ve uygulama içinden indirilir.
- **Atıf:**
  > Jörg Tiedemann and Santhosh Thottingal. 2020. *OPUS-MT — Building open translation services for the World.* In Proceedings of the 22nd Annual Conference of the European Association for Machine Translation (EAMT).
  >
  > Jörg Tiedemann. 2020. *The Tatoeba Translation Challenge — Realistic Data Sets for Low Resource and Multilingual MT.* In Proceedings of the Fifth Conference on Machine Translation (WMT).

---

## 🧠 Gelişmiş OCR düzeltme modeli — Gemma 4 E2B (Google)

- **Model:** Gemma 4 E2B instruction-tuned, LiteRT-LM biçimi ([litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), dosya `gemma-4-E2B-it.litertlm`)
- **Lisans:** Apache License 2.0
- **Kullanım:** İsteğe bağlıdır ve APK'ya dahil değildir. Kullanıcı Ayarlar'dan indirmeyi seçerse dosya projenin GitHub Releases sayfasından (`gemma-model-v1`; GitHub'ın 2 GB dosya sınırı nedeniyle değiştirilmeden iki parçaya bölünmüş halde) indirilir; GitHub'a ulaşılamazsa aynı dosya Hugging Face'teki sabit sürümden indirilir. Tamamı SHA-256 ile doğrulanır. Model değiştirilmeden kullanılır; yalnızca OCR'ın bozduğu İngilizce altyazıyı çeviriden önce düzeltmek için çalıştırılır.

---

## İngilizce kelime listesi — SymSpell (Wolf Garbe)

- **Kaynak:** [SymSpell](https://github.com/wolfgarbe/SymSpell) `frequency_dictionary_en_82_765.txt` (yalnızca kelimeler alındı, sıklık değerleri çıkarıldı → `app/src/main/assets/en_words.txt`)
- **Lisans:** MIT License — Copyright (c) 2018 Wolf Garbe
- **Kullanım:** Bir altyazının bozuk okunup okunmadığını anlamak için (Gemma düzeltmesinin ne zaman çalışacağına karar verir).

```
MIT License

Copyright (c) 2018 Wolf Garbe

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Yazılım kütüphaneleri

| Bileşen | Kullanım | Lisans |
|---|---|---|
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (Microsoft) | OPUS modelini telefonda çalıştırma | MIT |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google) | Gemma modelini telefonda çalıştırma | Apache License 2.0 |
| [Google ML Kit](https://developers.google.com/ml-kit) — Text Recognition, Translation | Metin okuma (OCR) ve yedek çevrimdışı çeviri | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| [AndroidX / Jetpack Compose / CameraX](https://developer.android.com/jetpack/androidx) | Arayüz ve kamera | Apache License 2.0 |
| [Material Icons](https://fonts.google.com/icons) | Arayüz ikonları | Apache License 2.0 |
| [Accompanist](https://github.com/google/accompanist) | İzin yönetimi | Apache License 2.0 |
| [Kotlin](https://kotlinlang.org/) ve [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Programlama dili ve eşzamanlılık | Apache License 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Ağ istekleri | Apache License 2.0 |
| [Guava](https://github.com/google/guava) | Yardımcı kütüphane | Apache License 2.0 |

Apache License 2.0 metni: https://www.apache.org/licenses/LICENSE-2.0

---

## Çevrimiçi servisler

Google Translate, Google Cloud Translation ve Google Cloud Text-to-Speech modlarında çeviri ve seslendirme Google servisleri üzerinden yapılır; bu servisler Google'ın kendi kullanım koşullarına tabidir. Google Cloud servisleri kullanıcının kendi API anahtarıyla çalışır ve ücretlendirmesi Google Cloud'a bağlıdır.

Camera Translator bağımsız bir açık kaynak projedir; Google, Microsoft veya University of Helsinki ile bir bağlantısı yoktur ve bu kuruluşlar tarafından desteklenmemektedir.
