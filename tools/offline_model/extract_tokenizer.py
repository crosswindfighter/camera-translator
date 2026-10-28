"""Extract source.spm pieces into a plain TSV for Kotlin, and verify that a simple
unigram Viterbi (the exact algorithm we will port to Kotlin) reproduces the official
MarianTokenizer ids on real subtitle text."""
import json
import os
import sys
import unicodedata
sys.stdout.reconfigure(encoding="utf-8")

import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2 as pb
from transformers import MarianTokenizer

model_dir, out_dir = sys.argv[1], sys.argv[2]

proto = pb.ModelProto()
proto.ParseFromString(open(os.path.join(model_dir, "source.spm"), "rb").read())
print("normalizer:", proto.normalizer_spec.name, "add_dummy_prefix:", proto.normalizer_spec.add_dummy_prefix,
      "remove_extra_ws:", proto.normalizer_spec.remove_extra_whitespaces, "model_type:", proto.trainer_spec.model_type)

# type: 1=NORMAL 2=UNKNOWN 3=CONTROL 4=USER_DEFINED 6=BYTE
pieces = [(p.piece, p.score, p.type) for p in proto.pieces]
with open(os.path.join(out_dir, "source_pieces.tsv"), "w", encoding="utf-8") as f:
    for piece, score, typ in pieces:
        if typ in (1, 4):
            f.write(f"{piece}\t{score}\n")
vocab = json.load(open(os.path.join(model_dir, "vocab.json"), encoding="utf-8"))
print("pieces:", len(pieces), "vocab:", len(vocab))

usable = {p: s for p, s, t in pieces if t in (1, 4)}
max_len = max(len(p) for p in usable)
min_score = min(usable.values())
UNK, EOS = vocab["<unk>"], vocab["</s>"]


def normalize(text):
    text = unicodedata.normalize("NFKC", text)
    text = " ".join(text.split())
    return "▁" + text.replace(" ", "▁")


def viterbi(text):
    s = normalize(text)
    n = len(s)
    best = [float("-inf")] * (n + 1)
    back = [None] * (n + 1)
    best[0] = 0.0
    for i in range(n):
        if best[i] == float("-inf"):
            continue
        matched = False
        for L in range(1, min(max_len, n - i) + 1):
            piece = s[i:i + L]
            score = usable.get(piece)
            if score is not None:
                matched = True
                if best[i] + score > best[i + L]:
                    best[i + L] = best[i] + score
                    back[i + L] = (i, piece)
        if not matched or best[i + 1] == float("-inf"):
            cand = best[i] + min_score - 10.0
            if cand > best[i + 1]:
                best[i + 1] = cand
                back[i + 1] = (i, s[i])  # unknown char: kept as-is, looked up in shared vocab
    out, j = [], n
    while j > 0:
        i, piece = back[j]
        out.append(piece)
        j = i
    out.reverse()
    ids = []
    for p in out:
        ids.append(vocab.get(p, UNK) if p is not None else UNK)
    return ids + [EOS]


tok = MarianTokenizer.from_pretrained(model_dir)
tests = [
    "Go to Vespucci Beach.",
    "Hey, wasn't that motherfucker who took the bike that ese - Vagos dude?",
    "With the tattoo on his face and shit?",
    "Fuck, homie.", "I don't want no unnecessary bullshit here, alright?",
    "Nigga, I don't give a shit.",
    "There's a reason Simeon pays a couple of mean looking motherfuckers to come repo this shit.",
    "\"Should be\" is the story of your life, asshole.",
    "Twenty Gs?", "In and out, no fuckin' drama.", "Café — naïve résumé 100% … ½",
    "  multiple   spaces\there  ", "Ünlü Çağrı İstanbul'da!",
]
bad = 0
for t in tests:
    ref = tok(t)["input_ids"]
    mine = viterbi(t)
    if ref != mine:
        bad += 1
        print("MISMATCH:", t, "\n  ref ", tok.convert_ids_to_tokens(ref), "\n  mine", tok.convert_ids_to_tokens(mine))
print(f"{len(tests) - bad}/{len(tests)} match")
