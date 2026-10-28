# Offline+ modelini üretme

Uygulamanın indirdiği 4 dosya (`encoder_model.onnx`, `decoder_model_merged.onnx`,
`source_pieces.tsv`, `vocab.json`) bu adımlarla üretildi. Model:
[Helsinki-NLP/opus-mt-tc-big-en-tr](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-tr) (CC-BY 4.0).

```bash
python -m venv venv
venv/Scripts/python -m pip install torch transformers sentencepiece sacremoses onnx onnxruntime "optimum[onnxruntime]"

# 1) ONNX'e çevir (KV önbellekli, birleşik decoder dahil)
venv/Scripts/optimum-cli export onnx --model Helsinki-NLP/opus-mt-tc-big-en-tr --task text2text-generation-with-past onnx_fp32

# 2) 8-bit sıkıştır: encoder ~135 MB, decoder ~220 MB
#    (birleşik decoder If dalları içerdiği için EnableSubgraph gerekiyor; betik bunu yapar)
venv/Scripts/python quantize.py onnx_fp32 onnx_int8

# 3) Tokenizer verisini çıkar ve Kotlin'deki Viterbi ile birebir aynı olduğunu doğrula
venv/Scripts/python extract_tokenizer.py onnx_fp32 onnx_int8
cp onnx_fp32/vocab.json onnx_int8/

# 4) Uygulamadaki çeviri döngüsünün Python kopyasıyla uçtan uca dene
venv/Scripts/python verify_onnx.py onnx_int8 onnx_int8/vocab.json
```

Dosyalar değişirse `OfflineModelDownloader.kt` içindeki boyut ve SHA-256 değerleri ile
GitHub Release etiketini (`offline-model-v1`) güncelleyin.

Önemli iki ayrıntı (Kotlin motoru bunlara göre yazıldı):
- Birleşik decoder'ın önbellek kolu encoder K/V çıktısını geri vermez; encoder K/V ilk adımdan saklanmalı.
- Çok cümleli metni tek parça çevirince model bazen ilk cümleyi atlıyor; cümle cümle çevrilmeli.
