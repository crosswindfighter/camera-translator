# OPUS-MT en->tr cevirmeni (ONNX), Android'deki OpusTranslationEngine'in Python karsiligi.
# Marian tokenizer (SentencePiece unigram + Viterbi) + KV onbellekli greedy decode.
#
# Kullanim:
#   python translate.py "Hello there."          -> tek seferlik
#   python translate.py                          -> satir satir stdin okur, stdout'a yazar
#
# Model klasoru: OPUS_DIR ortam degiskeni, yoksa yanindaki models/opus_en_tr,
# yoksa Masaustu\opus_en_tr

import json
import os
import re
import sys
import time
import unicodedata

import numpy as np
import onnxruntime as ort

import corrector
import llm
import ocrfix
import slang

_HERE = os.path.dirname(os.path.abspath(__file__))


def _find_model_dir():
    env = os.environ.get("OPUS_DIR")
    if env:
        return env
    candidates = [
        os.path.join(_HERE, "models", "opus_en_tr"),
        os.path.join(os.path.expanduser("~"), "Desktop", "opus_en_tr"),
        os.path.join(os.path.expanduser("~"), "Masaüstü", "opus_en_tr"),
    ]
    for path in candidates:
        if os.path.isfile(os.path.join(path, "encoder_model.onnx")):
            return path
    return candidates[0]


MODEL_DIR = _find_model_dir()

# Okuma cok bozuksa bu isaret donulur; arayuz altyaziyi hic gostermez
SKIP = "__atla__"

PAD_ID = 57059
EOS_ID = 43741
UNK_ID = 52508

DECODER_LAYERS = 6
HEADS = 16
HEAD_DIM = 64
MAX_NEW_TOKENS = 128
MAX_REPEATS = 6
# Dikkatli cevirideki yol sayisi (Helsinki modelin kendi degerlendirme ayari)
BEAM_SIZE = 4

WORD = re.compile(r"[^\W\d_']+(?:'[^\W\d_']+)*", re.UNICODE)
# OPUS'un film/altyazi verisinden uydurdugu kaliplar
MADE_UP = re.compile(r"(?i)tarafından (yazıldı|çevrildi)|altyazı(lar)?:|çeviri:")

SPACE_MARK = "\u2581"
WHITESPACE = re.compile(r"\s+")
STANDALONE_PIPE = re.compile(r"(?<!\S)\|(?!\S)")
SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+")
STANDALONE_HEY = re.compile(r"(?i)^hey[.!]*$")
LANGUAGE_NOTE = re.compile(r"\s*\((?:İngilizce|ingilizce|İspanyolca)\)")

CHAR_REPLACEMENTS = [
    ("\u2026", "..."), ("\u0131", "i"), ("\u00df", "ss"),
    ("\u00f8", "o"), ("\u00e6", "ae"), ("\u0142", "l"), ("\u0111", "d"),
]


class MarianTokenizer:
    def __init__(self, piece_scores, piece_to_id, id_to_piece):
        self.scores = piece_scores
        self.to_id = piece_to_id
        self.to_piece = id_to_piece
        self.max_len = max(len(p) for p in piece_scores)
        self.unknown_score = min(piece_scores.values()) - 10.0
        chars = set()
        for piece in piece_scores:
            chars.update(piece)
        self.known_chars = chars

    @staticmethod
    def load(pieces_path, vocab_path):
        scores = {}
        with open(pieces_path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                tab = line.rfind("\t")
                if tab > 0:
                    scores[line[:tab]] = float(line[tab + 1:])
        with open(vocab_path, "r", encoding="utf-8") as fh:
            vocab = json.load(fh)
        max_id = max(vocab.values())
        to_piece = [None] * (max_id + 1)
        for piece, idx in vocab.items():
            to_piece[idx] = piece
        return MarianTokenizer(scores, vocab, to_piece)

    def clean_source(self, text):
        """OCR'in uydurdugu sapkali harfleri duzlestirir, modelin tanimadigi karakteri atar."""
        for src, dst in CHAR_REPLACEMENTS:
            text = text.replace(src, dst)
        text = STANDALONE_PIPE.sub("I", text)
        decomposed = unicodedata.normalize("NFD", text)
        text = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
        text = "".join(c if (c in self.known_chars or c.isspace()) else " " for c in text)
        return " ".join(text.split())

    def encode(self, text):
        normalized = unicodedata.normalize("NFKC", text).strip()
        s = SPACE_MARK + SPACE_MARK.join(WHITESPACE.split(normalized))
        n = len(s)

        neg = float("-inf")
        best = [neg] * (n + 1)
        back_start = [0] * (n + 1)
        back_piece = [None] * (n + 1)
        best[0] = 0.0
        scores = self.scores
        for i in range(n):
            if best[i] == neg:
                continue
            limit = min(self.max_len, n - i)
            for length in range(1, limit + 1):
                piece = s[i:i + length]
                score = scores.get(piece)
                if score is None:
                    continue
                total = best[i] + score
                if total > best[i + length]:
                    best[i + length] = total
                    back_start[i + length] = i
                    back_piece[i + length] = piece
            if best[i + 1] == neg:
                best[i + 1] = best[i] + self.unknown_score
                back_start[i + 1] = i
                back_piece[i + 1] = s[i:i + 1]

        ids = []
        j = n
        while j > 0:
            piece = back_piece[j]
            ids.append(self.to_id.get(piece, UNK_ID) if piece is not None else UNK_ID)
            j = back_start[j]
        ids.reverse()
        ids.append(EOS_ID)
        return ids

    def decode(self, ids):
        out = []
        for i in ids:
            if i in (EOS_ID, PAD_ID, UNK_ID):
                continue
            if 0 <= i < len(self.to_piece) and self.to_piece[i] is not None:
                out.append(self.to_piece[i])
        return "".join(out).replace(SPACE_MARK, " ").strip()


def _has_repeat(words):
    return any(words[i].lower() == words[i - 1].lower() == words[i - 2].lower()
               for i in range(2, len(words)))


def suspicion(source, output):
    """Cevirinin bicimsel supphe puani (0 = sorun gorunmuyor).

    Anlam hatalarini degil, bozuk ciktiyi yakalar: cumlenin atlanmasi, cevrilmeden
    kalan Ingilizce kelime, tekrar dongusu, uydurma kalip.
    """
    out = output.strip()
    if not out:
        return 3
    score = 0
    source_words = WORD.findall(source)
    out_words = WORD.findall(out)
    if len(source_words) >= 5 and len(out_words) < len(source_words) * 0.4:
        score += 1
    source_lower = set(w.lower() for w in source_words)
    for w in out_words:
        if (len(w) >= 4 and w[:1].islower() and all('a' <= c <= 'z' for c in w)
                and w in source_lower):
            score += 1
            break
    if _has_repeat(out_words) and not _has_repeat(source_words):
        score += 1
    if MADE_UP.search(out):
        score += 2
    return score


def trim_repetition(output):
    """Model donguye girdiyse tekrarlari atar ve True doner."""
    for size in (1, 2, 3):
        span = size * MAX_REPEATS
        if len(output) < span:
            continue
        start = len(output) - span
        if all(output[i] == output[i - size] for i in range(start + size, len(output))):
            del output[len(output) - (span - size):]
            return True
    return False


class Engine:
    def __init__(self, model_dir=MODEL_DIR, use_slang=True, use_corrector=True, use_beam=True):
        self.use_slang = use_slang
        self.use_corrector = use_corrector
        self.use_beam = use_beam
        # 'opus' = hizli, 'llm' = kaliteli (yerel GPU modeli), 'auto' = LLM varsa onu kullan
        self.llm_model = os.environ.get('CT_LLM_MODEL') or llm.MODEL
        # Gelismis OCR duzeltme: Ingilizceyi LLM onarir, ceviriyi yine OPUS yapar
        self.use_ocrfix = os.environ.get('CT_OCRFIX', '0') == '1'
        if self.use_ocrfix and llm.available() is None:
            print("ollama yok, gelismis OCR duzeltme kapatildi", file=sys.stderr)
            self.use_ocrfix = False
        elif self.use_ocrfix:
            try:
                llm.warm(self.llm_model)
            except Exception as exc:
                print("gelismis duzeltme isitilamadi: %s" % exc, file=sys.stderr)
                self.use_ocrfix = False
        if use_corrector:
            try:
                corrector.get()
            except Exception as exc:
                print("duzeltici yuklenemedi: %s" % exc, file=sys.stderr)
                self.use_corrector = False
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.tok = MarianTokenizer.load(
            os.path.join(model_dir, "source_pieces.tsv"),
            os.path.join(model_dir, "vocab.json"),
        )
        # OPUS islemcide kalir. Kurulu onnxruntime DirectML surumu oldugu icin
        # saglayici yazilmazsa oturum kendini ekran kartina alir; int8 agirliklarla
        # bu kazanc getirmiyor, olculene kadar acik tercih CPU.
        cpu = ["CPUExecutionProvider"]
        self.encoder = ort.InferenceSession(
            os.path.join(model_dir, "encoder_model.onnx"), opts, providers=cpu)
        self.decoder = ort.InferenceSession(
            os.path.join(model_dir, "decoder_model_merged.onnx"), opts, providers=cpu)
        self.decoder_outputs = [o.name for o in self.decoder.get_outputs()]

    def _greedy(self, sentence):
        ids = np.array([self.tok.encode(sentence)], dtype=np.int64)
        mask = np.ones_like(ids, dtype=np.int64)
        hidden = self.encoder.run(None, {"input_ids": ids, "attention_mask": mask})[0]

        empty = np.zeros((1, HEADS, 0, HEAD_DIM), dtype=np.float32)
        past = {}
        for layer in range(DECODER_LAYERS):
            for kind in ("decoder", "encoder"):
                for part in ("key", "value"):
                    past["past_key_values.%d.%s.%s" % (layer, kind, part)] = empty

        output = []
        token = PAD_ID
        max_tokens = min(MAX_NEW_TOKENS, ids.shape[1] * 2 + 8)
        for step in range(max_tokens):
            feeds = dict(past)
            feeds["encoder_attention_mask"] = mask
            feeds["input_ids"] = np.array([[token]], dtype=np.int64)
            feeds["encoder_hidden_states"] = hidden
            feeds["use_cache_branch"] = np.array([step > 0], dtype=bool)

            result = self.decoder.run(None, feeds)

            logits = result[0][0, -1].copy()
            logits[PAD_ID] = -np.inf
            logits[UNK_ID] = -np.inf
            token = int(np.argmax(logits))

            # Onbellek kolu encoder K/V'yi geri vermiyor: o ilk adimdan kalir.
            for i in range(1, len(self.decoder_outputs)):
                name = self.decoder_outputs[i]
                if step == 0 or ".decoder." in name:
                    past[name.replace("present", "past_key_values")] = result[i]

            if token == EOS_ID:
                break
            output.append(token)
            if trim_repetition(output):
                break

        return self.tok.decode(output)

    def _beam(self, sentence):
        """Dikkatli cozumleme: her adimda en olasi BEAM_SIZE yol birlikte ilerletilir,
        sonunda uzunluga gore normalize edilmis en yuksek puanli cumle secilir."""
        k = BEAM_SIZE
        ids = np.array([self.tok.encode(sentence)], dtype=np.int64)
        length = ids.shape[1]
        mask1 = np.ones_like(ids, dtype=np.int64)
        hidden1 = self.encoder.run(None, {"input_ids": ids, "attention_mask": mask1})[0]

        hidden = np.repeat(hidden1, k, axis=0)
        mask = np.ones((k, length), dtype=np.int64)
        empty = np.zeros((k, HEADS, 0, HEAD_DIM), dtype=np.float32)
        past = {}
        for layer in range(DECODER_LAYERS):
            for kind in ("decoder", "encoder"):
                for part in ("key", "value"):
                    past["past_key_values.%d.%s.%s" % (layer, kind, part)] = empty

        tokens = np.full(k, PAD_ID, dtype=np.int64)
        scores = np.full(k, -np.inf)
        scores[0] = 0.0
        sequences = [[] for _ in range(k)]
        finished = []
        max_tokens = min(MAX_NEW_TOKENS, length * 2 + 8)

        for step in range(max_tokens):
            feeds = dict(past)
            feeds["encoder_attention_mask"] = mask
            feeds["input_ids"] = tokens.reshape(k, 1)
            feeds["encoder_hidden_states"] = hidden
            feeds["use_cache_branch"] = np.array([step > 0], dtype=bool)
            result = self.decoder.run(None, feeds)

            logits = result[0][:, -1, :].astype(np.float64)
            logits[:, PAD_ID] = -np.inf
            logits[:, UNK_ID] = -np.inf

            candidates = []
            for b in range(k):
                if scores[b] == -np.inf:
                    continue
                row = logits[b]
                top = np.argpartition(-row, 2 * k)[:2 * k]
                mx = np.max(row[np.isfinite(row)])
                log_z = mx + np.log(np.sum(np.exp(row[np.isfinite(row)] - mx)))
                for t in top:
                    candidates.append((scores[b] + row[t] - log_z, b, int(t)))
            candidates.sort(key=lambda c: -c[0])

            nxt = []
            for sc, b, t in candidates:
                if t == EOS_ID:
                    seq = sequences[b]
                    finished.append((sc / max(1, len(seq)), list(seq)))
                else:
                    nxt.append((sc, b, t))
                if len(nxt) == k:
                    break
            if len(finished) >= k or not nxt:
                break
            while len(nxt) < k:
                nxt.append((-np.inf, nxt[0][1], nxt[0][2]))

            # Decoder K/V'yi secilen yollarin sirasina gore yeniden diz;
            # encoder K/V ilk adimdan kalir (onbellek kolu geri vermiyor).
            order = np.array([b for _, b, _ in nxt], dtype=np.int64)
            for i in range(1, len(self.decoder_outputs)):
                name = self.decoder_outputs[i]
                key = name.replace("present", "past_key_values")
                if ".decoder." in name:
                    past[key] = np.ascontiguousarray(result[i][order])
                elif step == 0:
                    past[key] = result[i]

            sequences = [sequences[b] + [t] for _, b, t in nxt]
            tokens = np.array([t for _, _, t in nxt], dtype=np.int64)
            scores = np.array([sc for sc, _, _ in nxt])

        if not finished:
            for b in range(k):
                if scores[b] > -np.inf:
                    finished.append((scores[b] / max(1, len(sequences[b])), sequences[b]))
        if not finished:
            return ""
        best = max(finished, key=lambda f: f[0])[1]
        out = list(best)
        trim_repetition(out)
        return self.tok.decode(out)

    def _sentence(self, sentence):
        """Once acgozlu; cikti supheliyse bir de dikkatli cevirip az supheli olani sec."""
        fast = self._greedy(sentence)
        if not self.use_beam:
            return fast
        fast_score = suspicion(sentence, fast)
        if fast_score == 0:
            return fast
        careful = self._beam(sentence)
        return careful if suspicion(sentence, careful) < fast_score else fast

    def translate(self, text):
        # Android'deki sira: fixPipes -> cleanSource -> OCR duzeltici -> argo sadelestirme
        cleaned = self.tok.clean_source(corrector.fix_pipes(text))
        if not cleaned:
            return ""
        return self._after_clean(cleaned)

    def _after_clean(self, cleaned):
        # OCR'in bozdugu kelimeleri duzelt (sorne -> some, ofyou -> of you)
        if self.use_corrector:
            try:
                cleaned = corrector.get().correct(cleaned)
                # Gelismis duzeltme: sozlugun beceremedigi bozulmalari yerel LLM onarir.
                # Ceviriden ONCE calisir, cunku OPUS temiz Ingilizceyi cok daha iyi ceviriyor.
                if self.use_ocrfix:
                    cleaned = ocrfix.fix(cleaned, model=self.llm_model)
                # Yarim yakalanmis okuma: bozuk parcalari at, geriye bir sey kalmadiysa cevirme
                trimmed = corrector.strip_junk(cleaned)
                if corrector.is_garbage(trimmed):
                    return SKIP
                cleaned = trimmed
            except Exception:
                pass
        # OPUS'un bilmedigi argoyu modele vermeden once sade Ingilizceye cevir
        if self.use_slang:
            cleaned = slang.normalize(cleaned)
        parts = [p for p in SENTENCE_SPLIT.split(cleaned) if p.strip()]
        pieces = []
        for part in parts:
            if STANDALONE_HEY.match(part.strip()):
                pieces.append(part.strip())
            else:
                pieces.append(self._sentence(part))
        return LANGUAGE_NOTE.sub("", " ".join(pieces)).strip()


def _warm_ocr():
    """Ekran karti yolunda modellerin yuklenmesi ve gol derlemesi ~2,5 sn
    suruyor. Bu bedeli acilista odeyelim, ilk altyazida degil."""
    try:
        import cv2
        import numpy as np
        import ocr
        img = np.full((64, 320, 3), 255, dtype=np.uint8)
        cv2.putText(img, "warm up", (10, 45), cv2.FONT_HERSHEY_SIMPLEX,
                    1.2, (0, 0, 0), 2)
        ocr.get()(img)
        return ocr.GPU
    except Exception as exc:
        print("ocr isinmasi atlandi: %s" % exc, file=sys.stderr)
        return False


def main():
    started = time.time()
    engine = Engine()
    load_ms = int((time.time() - started) * 1000)

    args = sys.argv[1:]
    if args:
        print("[model %d ms]" % load_ms, file=sys.stderr)
        for text in args:
            t0 = time.time()
            out = engine.translate(text)
            print("%4d ms | %s\n        | %s" % (int((time.time() - t0) * 1000), text, out))
        return

    gpu = _warm_ocr()
    load_ms = int((time.time() - started) * 1000)
    print("READY %d %s" % (load_ms, "gpu" if gpu else "cpu"), flush=True)
    for line in sys.stdin:
        # PowerShell tarafindaki akis ilk satirin basina BOM koyabiliyor;
        # suzulmezse ilk OCR istegi komut degil duz metin saniliyor ve
        # dosya yolu ceviriye gidiyor
        text = line.lstrip("\ufeff").rstrip("\n")
        if not text:
            continue
        if text == "__quit__":
            break
        # Ekran okuma: "#ocr<TAB>dosya<TAB>genislik<TAB>kip"
        #   -> "ms<TAB>x,y,w,h<TAB>satir<TAB>metin"
        # Kip 'ek' ise ortaya denk gelme sarti aranmaz: o cerceveyi kullanici
        # zaten dar cizdi, kenardaki satirlari atmak dogru degil.
        if text.startswith("#ocr\t"):
            parts = text.split("\t")
            path = parts[1] if len(parts) > 1 else ''
            width = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else None
            kip = parts[3].strip() if len(parts) > 3 else 'ana'
            t0 = time.time()
            try:
                import ocr
                found = ocr.read(path, width, center=(kip != 'ek'))
            except Exception as exc:
                print("ocr hatasi: %s" % exc, file=sys.stderr)
                found = None
            ms = int((time.time() - t0) * 1000)
            if found is None:
                print("%d\t\t0\t" % ms, flush=True)
            else:
                body, lines, rect = found
                print("%d\t%d,%d,%d,%d\t%d\t%s" % (
                    ms, rect[0], rect[1], rect[2], rect[3], lines,
                    body.replace("\t", " ")), flush=True)
            continue
        t0 = time.time()
        try:
            out = engine.translate(text)
        except Exception as exc:  # prototipte cokmesin, hatayi bildir
            out = "<hata: %s>" % exc
        print("%d\t%s" % (int((time.time() - t0) * 1000), out), flush=True)


if __name__ == "__main__":
    main()
