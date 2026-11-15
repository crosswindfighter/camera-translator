# OcrWordCorrector.kt'nin Python karsiligi: OCR'in bozdugu Ingilizce kelimeleri
# ceviriden once duzeltir. Yapay zeka degil, OPUS sozlugune dayali kural sistemi.
#
# Sozlukler mobil uygulamanin kendi varlik dosyalarindan okunur (kopya tutulmuyor):
#   app/src/main/assets/ocr_vocab.tsv   kelime + siklik puani (kucuk harf) / isimler (buyuk harf)
#   app/src/main/assets/en_words.txt    OPUS sozlugunde olmayan gercek kelimeler
#
# Baglam (bigram) turu burada yok; Kotlin'deki correctInContext tasinmadi.

import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
def _find_assets():
    env = os.environ.get('CT_ASSETS')
    if env:
        return env
    # Depodaki mobil uygulamanin varlik klasoru; tek basina dagitilirsa yanindaki kopya
    for path in (os.path.join(_HERE, '..', 'app', 'src', 'main', 'assets'),
                 os.path.join(_HERE, 'assets')):
        if os.path.isfile(os.path.join(path, 'ocr_vocab.tsv')):
            return path
    return os.path.join(_HERE, '..', 'app', 'src', 'main', 'assets')


ASSETS = _find_assets()

SPACES = re.compile(r'\s+')
TOKEN = re.compile(r"^([^A-Za-z0-9']*)([A-Za-z0-9']+)([^A-Za-z0-9']*)$")

COMMON_RANK = 12000
SHORT_WORD_RANK = 3500
SPLIT_PART_RANK = 3000

SUFFIXES = [
    ("ies", "y"), ("ied", "y"), ("ier", "y"), ("iest", "y"), ("ing", ""), ("ing", "e"),
    ("ed", ""), ("ed", "e"), ("er", ""), ("er", "e"), ("ers", ""), ("ers", "e"),
    ("est", ""), ("ly", ""), ("es", ""), ("s", ""), ("d", ""), ("r", ""),
]

EXTRA_WORDS = set("""rapel ballas pube mentorship recommence carat reformat lockup ifruit
uppity deify squeals squeal ringworm plinking plink
azerbaijani azerbaijanis raspberry raspberries
oh ohh uh uhh um hmm ah ahh huh whoa wow ok okay
nah yep yup hey shh ugh yo ya homie homies dawg bro cuz fam
argh aargh ew eww yikes nope gee jeez damn dammit goddamn puke
kinda sorta gotcha wassup sup lol anus rappel doke okey oof sho""".split())

FUNCTION_WORDS = set("""a i of to in on at is it be we he me my do so
no up by or an as if us go you the and for""".split())

CONTRACTIONS = {
    "dont": "don't", "didnt": "didn't", "doesnt": "doesn't", "isnt": "isn't", "wasnt": "wasn't",
    "cant": "can't", "wont": "won't", "couldnt": "couldn't", "wouldnt": "wouldn't",
    "shouldnt": "shouldn't", "youre": "you're", "theyre": "they're", "im": "I'm", "ive": "I've",
    "youve": "you've", "weve": "we've", "hes": "he's", "shes": "she's", "thats": "that's",
    "whats": "what's", "theres": "there's", "therell": "there'll", "youll": "you'll",
    "itll": "it'll", "aint": "ain't", "havent": "haven't",
    "hadnt": "hadn't", "hasnt": "hasn't", "arent": "aren't", "werent": "weren't", "theyll": "they'll",
    "theyve": "they've", "wouldve": "would've", "couldve": "could've", "shouldve": "should've",
    "whos": "who's", "whatll": "what'll", "thatll": "that'll", "wheres": "where's", "hows": "how's",
    "itd": "it'd", "youd": "you'd", "youl": "you'll", "theyl": "they'll",
    "howd": "how'd", "whyd": "why'd", "whered": "where'd",
}
CONTRACTION_FORMS = set(v.lower() for v in CONTRACTIONS.values())
STEM_SUFFIXES = {"'s", "'ll", "'re", "'ve", "'d"}

LONE_I = {"l", "J", "|"}
I_FORMS = {"ll": "I'll", "'ll": "I'll", "m": "I'm", "'m": "I'm", "d": "I'd", "'d": "I'd",
           "ve": "I've", "'ve": "I've"}
I_LOOKALIKES = "IlTJ|1f"

T_AS_I_VERBS = set("""want just know don't didn't think need gotta have had was am can
can't will won't told said mean guess swear never really thought wanna""".split())

I_VERBS = set("""know don't didn't did do have had got think thought was said can
can't could will won't just need want mean guess wonder hate love
like told saw am never ever really swear promise bet gotta gonna
wanna should would ain't""".split())

I_LL = {"Il", "Ill", "IIl", "lll", "rll", "Rll"}
I_MA = {"Ima", "lma", "I'ma", "l'ma"}
I_MA_VERBS = set("""go get be do kill tell make take show see have give put let find
try call come say need bust beat shoot speak talk drop head hit pull
wait bring ride handle meet stay start leave hold check grab""".split())

AUX_BEFORE_I = set("""did do does can could would should will am was have had
if and but so when then because""".split())

MID_SENTENCE_CAPS = {"It", "If", "In", "Is", "It's"}
EXCLAIMED_SHORT_WORDS = {"me", "no", "us", "up", "go"}
TRAILING_WORDS = ["you", "the", "them", "him", "her", "me", "it", "to"]
ACRONYM_LL = re.compile(r"^([A-Z]{2,4})(?:Il|ll|IIl)$")

SHORT_FIXES = {
    "ls": "is", "lt": "it", "lf": "if", "ln": "in", "lts": "its", "Lts": "Its",
    "ifl": "if I", "whatl": "what I", "thatl": "that I", "whenl": "when I", "andl": "and I", "butl": "but I",
    "iot": "not", "yean": "yeah", "wel": "well",
    "horrnie": "homie", "hormie": "homie", "hormje": "homie", "homjie": "homie", "iomie": "homie",
    "Yall": "Y'all", "yall": "y'all", "Yknow": "Y'know", "yknow": "y'know", "vknow": "y'know",
    "tearn": "team", "payrol": "payroll", "Runl": "Run!",
    "wari": "want", "rrakes": "makes", "I'rrn": "I'm", "arnidiot": "an idiot", "thathpuse": "that house",
    "Iook": "look", "qot": "got", "qo": "go", "Sr": "Sir", "mne": "me", "ypdr": "your", "withiher": "with her",
    "Bhit": "shit", "hOUBe": "house", "agp": "go", "fuly": "fully", "dolknow": "do I know", "VWaar": "What",
    "thatshit": "that shit", "Thatshit": "That shit", "fuckoff": "fuck off", "Fuckoff": "Fuck off",
}

# (dogru, OCR'in okudugu)
CONFUSIONS = [
    ("m", "rn"), ("m", "rm"), ("m", "ri"), ("m", "rri"), ("n", "ri"), ("h", "li"), ("d", "cl"),
    ("d", "dl"), ("w", "vv"), ("u", "ii"), ("n", "rn"), ("d", "c"),
    ("tt", "t"), ("ll", "l"), ("ss", "s"), ("ee", "e"), ("oo", "o"), ("pp", "p"), ("ff", "f"), ("rr", "r"),
    ("v", "y"), ("y", "v"), ("i", "l"), ("l", "i"), ("o", "0"), ("g", "9"),
    ("e", "c"), ("c", "e"), ("h", "b"), ("b", "h"), ("n", "h"), ("i", "j"), ("t", "f"),
    ("h", "n"),
]

LETTERS = "abcdefghijklmnopqrstuvwxyz"

# ---- fixPipes: sozluk gerektirmez ----
# Java \p{L}/\p{Ll} yerine ASCII siniflari: metin Ingilizce OCR ciktisi.
PIPE_I_LL = re.compile(r"(?<![A-Za-z0-9'])(?:[lI1]\|{1,2}|\|\|)(?=\s+[A-Za-z])")
PIPE_CONTRACTED_LL = re.compile(r"(?<=[A-Za-z])'(?:\|\||l\||\|l)(?![A-Za-z0-9])")
PIPE_PRONOUN_LL = re.compile(r"\b(there|they|you|she|it|that|this|who)(?:\|\||l\||\|l)(?![A-Za-z0-9])", re.I)
PIPE_APOSTROPHE = re.compile(r"(?<![A-Za-z0-9])\|(?=['’-])|(?<=-)\|(?![A-Za-z0-9])")
GLUED_COMMA = re.compile(r"(?<=[A-Za-z]),(?=[A-Za-z])")
LOOKALIKE_CAPS = re.compile(r"\b(?=[a-z])(?=[a-zCOSUVWXZ]{4,}\b)[a-z]*[COSUVWXZ][a-zCOSUVWXZ]*\b")
PIPE_IN_WORD = re.compile(r"(?<=[a-z])\|(?=[a-z])")
PIPE_WORD = re.compile(r"(?<![A-Za-z0-9])\|([a-z]+)")
STANDALONE_PIPE = re.compile(r"(?<!\S)\|(?!\S)")


def fix_pipes(text):
    """TV fontunda "I" sik sik "|" okunur. Sozluk gerektirmez."""
    lowered = LOOKALIKE_CAPS.sub(lambda m: m.group(0).lower(), text)
    lowered = GLUED_COMMA.sub(", ", lowered)
    if "|" not in lowered:
        return lowered
    out = PIPE_I_LL.sub("I'll", lowered)
    out = PIPE_CONTRACTED_LL.sub("'ll", out)
    out = PIPE_PRONOUN_LL.sub(lambda m: m.group(1) + "'ll", out)
    out = PIPE_IN_WORD.sub("l", out)
    out = PIPE_APOSTROPHE.sub("I", out)
    out = PIPE_WORD.sub(lambda m: "I " + m.group(1), out)
    out = STANDALONE_PIPE.sub("I", out)
    return out


def _cap_first(s):
    return s[:1].upper() + s[1:] if s else s


def _has_triple(word):
    return any(word[i] == word[i - 1] == word[i - 2] for i in range(2, len(word)))


class Corrector:
    def __init__(self, scores, cased_words, real_words):
        self.scores = scores
        self.cased = cased_words
        self.real = real_words
        ordered = sorted(scores.items(), key=lambda kv: -kv[1])
        self.rank = {w: i for i, (w, _) in enumerate(ordered)}

    # ---------- yukleme ----------
    @staticmethod
    def load(assets=ASSETS):
        scores, cased = {}, set()
        with open(os.path.join(assets, 'ocr_vocab.tsv'), 'r', encoding='utf-8') as fh:
            for line in fh:
                tab = line.find('\t')
                if tab <= 0:
                    continue
                word = line[:tab]
                if word[0].isupper():
                    cased.add(word)
                else:
                    try:
                        scores[word] = float(line[tab + 1:].strip())
                    except ValueError:
                        continue
        real = set()
        with open(os.path.join(assets, 'en_words.txt'), 'r', encoding='utf-8') as fh:
            for line in fh:
                w = line.strip()
                if w:
                    real.add(w)
        return Corrector(scores, cased, real)

    # ---------- yardimcilar ----------
    def rank_of(self, word):
        return self.rank.get(word, 1 << 30)

    def is_known(self, word):
        if word in self.scores or word in EXTRA_WORDS or word in self.real:
            return True
        if word.endswith("in") and (word + "g") in self.scores:
            return True
        for suffix, repl in SUFFIXES:
            if not word.endswith(suffix) or len(word) - len(suffix) < 2:
                continue
            stem = word[:len(word) - len(suffix)] + repl
            if stem in self.scores or stem in self.real:
                return True
            if repl == "" and len(stem) >= 3 and stem[-1] == stem[-2] and stem[:-1] in self.scores:
                return True
        return False

    def best(self, candidates):
        pick, pick_score = None, None
        for c in candidates:
            s = self.scores.get(c)
            if s is not None and (pick_score is None or s > pick_score):
                pick, pick_score = c, s
        return pick

    def confusion_variants(self, word):
        out = set()
        for right, read in CONFUSIONS:
            idx = word.find(read)
            while idx >= 0:
                out.add(word[:idx] + right + word[idx + len(read):])
                idx = word.find(read, idx + 1)
        return out

    def edits1(self, word):
        out = set()
        for i in range(len(word) + 1):
            a, b = word[:i], word[i:]
            if b:
                out.add(a + b[1:])
                for c in LETTERS:
                    out.add(a + c + b[1:])
            if len(b) > 1:
                out.add(a + b[1] + b[0] + b[2:])
            for c in LETTERS:
                out.add(a + c + b)
        return out

    def best_split(self, word):
        result, best_score = None, None
        for i in range(1, len(word)):
            left, right = word[:i], word[i:]
            ls, rs = self.scores.get(left), self.scores.get(right)
            if ls is None or rs is None:
                continue
            if (len(left) < 3 and left not in FUNCTION_WORDS) or (len(right) < 3 and right not in FUNCTION_WORDS):
                continue
            if best_score is None or ls + rs > best_score:
                best_score, result = ls + rs, [left, right]
        return result

    def is_common_split(self, parts):
        return all(p in FUNCTION_WORDS or self.rank_of(p) < SPLIT_PART_RANK for p in parts)

    def correct_name(self, word):
        lower = word.lower()
        names = set()
        for v in (self.confusion_variants(lower) | self.edits1(lower)):
            c = _cap_first(v)
            if c in self.cased:
                names.add(c)
        if len(names) > 1:
            names = {n for n in names if len(n) == len(word) + 1}
        return next(iter(names)) if len(names) == 1 else None

    # ---------- "I" onarimlari ----------
    def fix_i_glyph(self, word, sentence_start, next_token):
        next_is_word = bool(next_token) and next_token[0].isalpha()
        if word in LONE_I and next_is_word:
            return "I"
        if word == "Nol" and next_token and next_token.lower().rstrip(',.') in I_VERBS:
            return "No I"
        if word in SHORT_FIXES:
            return SHORT_FIXES[word]
        if word in I_LL and next_is_word:
            return "I'll"
        if word in I_MA and next_is_word:
            nt = next_token.lower().rstrip(',.!?') if next_token else ''
            return "I'm gonna" if nt in I_MA_VERBS else "I'm a"
        if word in I_FORMS:
            if not next_is_word:
                return None
            safe_anywhere = word.startswith("'") or word == "ll"
            return I_FORMS[word] if (safe_anywhere or sentence_start) else None
        if word == "ld":
            return "I'd" if next_is_word else None
        if len(word) < 2 or word[0] not in I_LOOKALIKES:
            return None
        rest = word[1:]
        for form in (rest, rest.lstrip('Il')):
            fixed = I_FORMS.get(form)
            if fixed is None:
                continue
            if form == "d" or (word[0] == 'f' and form != "m"):
                continue
            return fixed
        if word in ("Ive", "lve"):
            return "I've"
        if word == "Id":
            return "I'd" if next_is_word else None
        if word in self.cased or self.is_known(word.lower()):
            return None
        if word[0] in "TJl|1I" and rest in I_VERBS:
            return "I " + rest
        if word[0] == 'J' and rest.islower():
            candidate = "l" + rest
            if candidate in self.scores and self.rank_of(candidate) < SHORT_WORD_RANK:
                return candidate
        return None

    def fix_exclamation_l(self, word, post, next_token):
        if post or len(word) < 3 or word[-1] != 'l' or word in self.cased:
            return None
        if next_token and not next_token[0].isupper():
            return None
        stem = word[:-1]
        if not stem.isalpha() or any(c.isupper() for c in stem[1:]):
            return None
        lower = stem.lower()
        exclaimed = lower in EXCLAIMED_SHORT_WORDS or (
            len(lower) >= 3 and self.is_known(lower) and self.rank_of(lower) < COMMON_RANK
            and not self.is_known(lower + "l") and not self.is_known(lower + "ll")
            and not self.is_known(lower + "ly"))
        return stem + "!" if exclaimed else None

    def split_contraction(self, word):
        lower = word.lower()
        for k in range(2, len(lower) - 1):
            fixed = CONTRACTIONS.get(lower[:k])
            if fixed is None:
                continue
            rest = lower[k:]
            if not self.is_known(rest) or self.rank_of(rest) >= COMMON_RANK:
                continue
            return (_cap_first(fixed) if word[0].isupper() else fixed) + " " + rest
        return None

    def split_before_contraction(self, word):
        ap = word.find("'")
        if ap < 4:
            return None
        lower = word.lower()
        for k in range(2, ap - 1):
            left = lower[:k]
            if lower[k:] not in CONTRACTION_FORMS:
                continue
            if left not in FUNCTION_WORDS and (not self.is_known(left) or self.rank_of(left) >= SPLIT_PART_RANK):
                continue
            return word[:k] + " " + word[k:]
        return None

    # ---------- kelime duzeltme ----------
    def correct_word(self, word, common_only, no_shortening):
        def acceptable(candidate):
            if candidate is None:
                return False
            if common_only and self.rank_of(candidate.split(' ')[0]) >= COMMON_RANK:
                return False
            return (not no_shortening) or len(candidate) >= len(word)

        if word in CONTRACTIONS:
            return CONTRACTIONS[word]

        if len(word) <= 3:
            candidate = self.best(self.confusion_variants(word))
            if candidate is not None and self.rank_of(candidate) < SHORT_WORD_RANK:
                return candidate
            return None

        split = None if (common_only or word.endswith("in")) else self.best_split(word)

        c = self.best(self.confusion_variants(word))
        if acceptable(c):
            return c

        if not common_only and len(word) >= 5:
            reals = [v for v in self.confusion_variants(word) if v in self.real]
            if len(reals) == 1:
                return reals[0]

        if split is None or not self.is_common_split(split):
            forms = [v for v in (self.confusion_variants(word) | self.edits1(word)) if v in CONTRACTIONS]
            if forms:
                forms.sort(key=lambda s: (-len(s), s))
                return CONTRACTIONS[forms[0]]

        candidate = self.best(self.edits1(word))
        if acceptable(candidate):
            shortens = (len(candidate) < len(word) and split is not None
                        and all(len(p) >= 2 or p == "a" for p in split))
            if split is not None and self.is_common_split(split) and (
                    self.rank_of(candidate) >= SPLIT_PART_RANK or shortens):
                return " ".join(split)
            return candidate

        if split is not None:
            return " ".join(split)

        if len(word) >= 6:
            cands = set()
            for e in self.edits1(word):
                cands |= self.confusion_variants(e)
            for v in self.confusion_variants(word):
                cands |= self.edits1(v)
            c2 = self.best(cands)
            if acceptable(c2):
                return c2

        for tail in TRAILING_WORDS:
            if word.endswith(tail):
                head = word[:len(word) - len(tail)]
                if len(head) >= 4 and self.is_known(head):
                    return head + " " + tail
        return None

    def correct_token(self, token, sentence_start, next_token):
        m = TOKEN.match(token)
        if not m:
            return token
        pre, raw_word, post = m.group(1), m.group(2), m.group(3)
        word = raw_word

        if (word == "T" and sentence_start and not post and next_token
                and next_token.lower().rstrip(',.!?') in T_AS_I_VERBS):
            return pre + "I"

        fixed = self.fix_i_glyph(word, sentence_start, next_token)
        if fixed is not None:
            return pre + fixed + post

        if "'" not in word and len(word) >= 3 and word not in self.cased:
            c = CONTRACTIONS.get(word.lower())
            if c is not None:
                return pre + (_cap_first(c) if word[0].isupper() else c) + post

        ex = self.fix_exclamation_l(word, post, next_token)
        if ex is not None:
            return pre + ex + post

        stem = word.split("'")[0]
        letters = [c for c in word if c.isalpha()]
        all_caps = bool(letters) and all(c.isupper() for c in letters)
        capitalized = word[0].isupper() and word[1:].islower() if len(word) > 1 else word[0].isupper()

        if all_caps and len(word) >= 4 and "'" not in word and word.lower() in self.scores:
            lower = word.lower()
            return pre + (_cap_first(lower) if sentence_start else lower) + post

        if not stem or self.is_known(stem.lower()) or len(stem) <= 2 or all_caps:
            return token

        s = self.split_before_contraction(word)
        if s is not None:
            return pre + s + post

        if ("'" not in word and len(word) >= 4 and word[0].isupper() and word[1].isupper()
                and word[2:].islower()):
            lower = word.lower()
            dropped = lower[1:]
            fix = None
            if self.is_known(lower) and self.rank_of(lower) < COMMON_RANK:
                fix = lower
            elif self.is_known(dropped) and self.rank_of(dropped) < COMMON_RANK:
                fix = dropped
            if fix is not None:
                return pre + (_cap_first(fix) if sentence_start else fix) + post

        s = self.split_contraction(word)
        if s is not None:
            return pre + s + post

        am = ACRONYM_LL.match(word)
        if am:
            return pre + am.group(1) + "'ll" + post

        if (len(word) >= 4 and word[0] == 'I' and word[1:].islower() and word not in self.cased
                and self.is_known(word[1:]) and self.rank_of(word[1:]) < COMMON_RANK):
            return pre + "I " + word[1:] + post

        if capitalized and len(word) >= 5 and word not in self.cased:
            n = self.correct_name(word)
            if n is not None:
                return pre + n + post

        if (capitalized and not sentence_start) or word in self.cased:
            return token
        if _has_triple(word.lower()):
            return token

        vocative = capitalized and post.startswith(",")

        if any(c.isupper() for c in word[1:]):
            for k in range(1, len(word)):
                if not word[k].isupper():
                    continue
                left, right = word[:k], word[k:]
                left_ok = ((len(left) >= 2 or left.lower() in FUNCTION_WORDS)
                           and (left.lower() in self.scores or left.lower() in FUNCTION_WORDS))
                if left_ok and (right == "I" or right.lower() in self.scores):
                    return pre + left + " " + (right if right == "I" else right.lower()) + post
            if word[0].isupper() or sum(1 for c in word if c.isupper()) > 1:
                return token
            word = word.lower()

        if "'" in word:
            suffix = word[len(stem):]
            if suffix in STEM_SUFFIXES:
                fixed2 = self.correct_token(stem, sentence_start, None)
                if fixed2 != stem and ' ' not in fixed2 and self.is_known(fixed2.lower()):
                    return pre + fixed2 + suffix + post
            return token

        lower = word.lower()
        if not all(('a' <= c <= 'z') or c.isdigit() for c in lower):
            return token
        normalized = lower.replace('0', 'o').replace('9', 'g').replace('1', 'l')
        fixed3 = self.correct_word(normalized, common_only=capitalized, no_shortening=vocative)
        if fixed3 is None:
            return token
        if word[0].isupper():
            fixed3 = _cap_first(fixed3)
        return pre + fixed3 + post

    # ---------- giris ----------
    def _lower_mid_sentence_it(self, tokens):
        out = list(tokens)
        for i in range(1, len(out)):
            core = out[i].rstrip('.,!?;:')
            prev = out[i - 1][-1:] if out[i - 1] else ''
            if core in MID_SENTENCE_CAPS and prev.isalpha() and prev.islower():
                out[i] = core.lower() + out[i][len(core):]
        return out

    def _fix_digit_i(self, tokens):
        out = list(tokens)
        for i in range(len(out)):
            core = out[i].rstrip(',.?!')
            if core != "1":
                continue
            prev = out[i - 1].lower().strip(',.?!') if i > 0 else ''
            nxt = out[i + 1].lower().strip(',.?!') if i + 1 < len(out) else ''
            if prev in AUX_BEFORE_I or nxt in I_VERBS or nxt == "lose":
                out[i] = "I" + out[i][len(core):]
        return out

    def correct(self, text):
        tokens = [t for t in SPACES.split(text) if t]
        tokens = self._fix_digit_i(self._lower_mid_sentence_it(tokens))
        out = []
        sentence_start = True
        for i, token in enumerate(tokens):
            nxt = tokens[i + 1] if i + 1 < len(tokens) else None
            out.append(self.correct_token(token, sentence_start, nxt))
            trimmed = token.rstrip('"\'')
            trimmed = trimmed.rstrip(')')
            sentence_start = bool(trimmed) and trimmed[-1] in ".!?"
        return " ".join(out)


# ---- Cok bozuk okumayi ayiklama ----
# Altyazi ekrana yazilirken yarim yakalaninca "th@VY0LAiked", "cla•ms" gibi parcalar
# cikiyor. Bunlari cevirmek bos is: birkac yuz ms sonra temiz okuma zaten geliyor.
_STUTTER = re.compile(r"^([A-Za-z])(-\1)+-?", re.I)
_NO_LETTER = re.compile(r"^[^A-Za-z]*$")
# Harf ile sembol/rakam yan yana: kelimenin icinde olmamasi gereken sey
_MIXED = re.compile(r"[A-Za-z][^A-Za-z\s'\-.,!?;:\"()]|[^A-Za-z\s'\-.,!?;:\"()][A-Za-z]")


def strip_junk(text):
    """Harf+sembol karisimi ve tamamen sembolden olusan parcalari atar."""
    out = []
    for token in text.split():
        core = token.strip('.,!?;:"()')
        if core and _MIXED.search(core):
            continue
        if core and len(core) > 1 and _NO_LETTER.match(core):
            continue
        out.append(token)
    return ' '.join(out)


def _word_stats(text):
    good = bad = 0
    longest_bad = 0
    inst = get()
    for w in re.findall(r"[A-Za-z][A-Za-z']*", text):
        core = w.strip("'")
        if len(core) < 3 or _STUTTER.match(w):
            continue
        if core[0].isupper():        # ozel isim sayilir
            good += 1
            continue
        if inst.is_known(core.lower()):
            good += 1
        else:
            bad += 1
            longest_bad = max(longest_bad, len(core))
    return good, bad, longest_bad


def is_garbage(text):
    """Budamadan sonra bile anlamli bir sey kalmadiysa True."""
    if not re.search(r"[A-Za-z]", text):
        return True
    good, bad, longest_bad = _word_stats(text)
    total = good + bad
    if bad >= 2 and bad == total:
        return True
    if total >= 3 and bad >= 3 and bad / total > 0.5:
        return True
    if total == 1 and bad == 1 and longest_bad >= 7:
        return True
    return False


_INSTANCE = None


def get():
    """Sozlukler bir kez yuklenir (~25 bin kelime)."""
    global _INSTANCE
    if _INSTANCE is None:
        _INSTANCE = Corrector.load()
    return _INSTANCE


def fix(text):
    """fixPipes + kelime duzeltici. Kotlin'deki OcrTextFixer.fix'in karsiligi (baglam turu haric)."""
    piped = fix_pipes(text)
    try:
        return get().correct(piped)
    except Exception:
        return piped
