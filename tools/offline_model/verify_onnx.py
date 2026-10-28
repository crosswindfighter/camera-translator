"""Greedy decoding with ONNX Runtime exactly as the Kotlin engine will do it:
own tokenizer (source_pieces.tsv + vocab.json), sentence split, encoder once,
merged decoder with KV cache, pad masked, stop at </s>."""
import json
import os
import re
import sys
import time
import unicodedata

import numpy as np
import onnxruntime as ort

sys.stdout.reconfigure(encoding="utf-8")
int8_dir, vocab_path = sys.argv[1], sys.argv[2]

PAD, EOS, UNK = 57059, 43741, 52508
LAYERS, HEADS, HEAD_DIM = 6, 16, 64

pieces = {}
for line in open(os.path.join(int8_dir, "source_pieces.tsv"), encoding="utf-8"):
    p, s = line.rstrip("\n").split("\t")
    pieces[p] = float(s)
max_len = max(len(p) for p in pieces)
min_score = min(pieces.values())
vocab = json.load(open(vocab_path, encoding="utf-8"))
id_to_piece = {v: k for k, v in vocab.items()}


def encode(text):
    s = "▁" + " ".join(unicodedata.normalize("NFKC", text).split()).replace(" ", "▁")
    n = len(s)
    best = [-1e30] * (n + 1)
    back = [(0, "")] * (n + 1)
    best[0] = 0.0
    for i in range(n):
        if best[i] <= -1e29:
            continue
        for L in range(1, min(max_len, n - i) + 1):
            sc = pieces.get(s[i:i + L])
            if sc is not None and best[i] + sc > best[i + L]:
                best[i + L] = best[i] + sc
                back[i + L] = (i, s[i:i + L])
        if best[i + 1] <= -1e29:
            best[i + 1] = best[i] + min_score - 10.0
            back[i + 1] = (i, s[i])
    out, j = [], n
    while j > 0:
        i, p = back[j]
        out.append(vocab.get(p, UNK))
        j = i
    return out[::-1] + [EOS]


def decode(ids):
    return "".join(id_to_piece[i] for i in ids if i not in (PAD, EOS)).replace("▁", " ").strip()


opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
enc = ort.InferenceSession(os.path.join(int8_dir, "encoder_model.onnx"), opts)
dec = ort.InferenceSession(os.path.join(int8_dir, "decoder_model_merged.onnx"), opts)


def translate_sentence(text):
    ids = np.array([encode(text)], dtype=np.int64)
    mask = np.ones_like(ids)
    hidden = enc.run(None, {"input_ids": ids, "attention_mask": mask})[0]
    empty = np.zeros((1, HEADS, 0, HEAD_DIM), dtype=np.float32)
    past = {f"past_key_values.{l}.{k}.{t}": empty for l in range(LAYERS) for k in ("decoder", "encoder") for t in ("key", "value")}
    token, out = PAD, []
    for step in range(128):
        feeds = {"encoder_attention_mask": mask, "input_ids": np.array([[token]], dtype=np.int64),
                 "encoder_hidden_states": hidden, "use_cache_branch": np.array([step > 0]), **past}
        results = dec.run(None, feeds)
        logits = results[0][0, -1]
        logits[PAD] = -np.inf
        token = int(np.argmax(logits))
        if token == EOS:
            break
        out.append(token)
        names = [o.name for o in dec.get_outputs()][1:]
        for n, v in zip(names, results[1:]):
            # cache branch returns dummy encoder presents: keep step-0 encoder K/V
            if step == 0 or ".decoder." in n:
                past[n.replace("present", "past_key_values")] = v
    return decode(out)


SPLIT = re.compile(r"(?<=[.!?])\s+")


def translate(text):
    return " ".join(translate_sentence(s) for s in SPLIT.split(text.strip()) if s)


translate("Hello.")
for text in [
    "Fuck, homie. I don't want no unnecessary bullshit here, alright?",
    "Man, step up, nigga! Of course this dude real. Who else gonna have the required surplus of paper, and deficiency of brain cells?",
    "In your case, I ain't so sure about that.",
    "Twenty Gs?",
    "We need to get out of here before the cops show up.",
]:
    t0 = time.perf_counter()
    tr = translate(text)
    print(f"[{(time.perf_counter() - t0) * 1000:4.0f} ms] {text}\n           {tr}")
