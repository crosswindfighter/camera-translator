# GemmaOcrFixer.kt'nin PC karsiligi: OCR'in bozdugu INGILIZCE altyaziyi
# ("wejuSrask fim") yerel LLM ile duzeltir ("we just ask him"). Ceviriyi yine OPUS yapar.
#
# Mobilde bu tasarim iyi sonuc vermisti; kaldirilma sebebi kalite degil, telefonun
# kamera surucusunu kilitlemesi ve cumle basina 0,5-1 sn idi. PC'de ikisi de yok.
#
# Gecikmeyi dusuk tutan uc onlem (mobildekinin aynisi):
#   1. Yalnizca supheli kelime iceren cumleler modele gider.
#   2. Model zamaninda cevap vermezse orijinal cumle kullanilir.
#   3. Cevap orijinalden cok farkliysa (uydurma riski) kullanilmaz.

import re
import time

import corrector
import llm

TIMEOUT = 2.5
MIXED_DIGIT = re.compile(r"[^\W\d_]\d|\d[^\W\d_]")
SPACES = re.compile(r"\s+")

# Mobilde gercek OCR kayitlariyla en iyi sonucu veren talimat ve ornekler (birebir)
SYSTEM = (
    "This English video game subtitle was read from a TV by OCR and may contain recognition errors "
    "(wrong letters, merged or split words, digits instead of letters, rn read as m). "
    "Rewrite it with the errors fixed. Every word must be a real English word or a name; fix words that are not. "
    "Change as little as possible: keep slang, swearing, names and punctuation. "
    "If it is already correct, return it unchanged. Output ONLY the corrected English subtitle.\n\n"
    "Examples:\n"
    "Wh0 the fuck are you speakirnigj to? -> Who the fuck are you speaking to?\n"
    "Gimme mne a hug yeah. -> Gimme a hug, yeah.\n"
    "Thar is-irs part ofthe grieving process -> That is first part of the grieving process\n"
    "We gota firnidl thye rest -> We gotta find the rest\n"
    "Sounds ike he's living in LosSantos. -> Sounds like he's living in Los Santos."
)

EXTRA_WORDS = set("""oh ohh uh uhh um umm hmm ah ahh huh whoa wow ok okay
nah yep yup yeah hey heh ha haha mm mhm shh ugh ow oops
gonna gotta wanna gimme lemme dunno ain c'mon homie bro dude yo ya cuz fam
didn wasn isn doesn couldn wouldn shouldn hasn haven hadn weren aren won can""".split())


def _is_known(word):
    if word in EXTRA_WORDS:
        return True
    inst = corrector.get()
    if inst.is_known(word):
        return True
    # Bilesik kelime (wetsuit, payroll, bedroom): sozlukte yok ama gercek.
    # Korumazsak duzeltici bunlari baska kelimeye ceviriyordu (wetsuit -> sutyen).
    if len(word) >= 6:
        for i in range(3, len(word) - 2):
            if inst.is_known(word[:i]) and inst.is_known(word[i:]):
                return True
    return False


def _suspicious(word, sentence_start):
    if MIXED_DIGIT.search(word):          # b9, cowD9y
        return True
    if word.isdigit():
        return False
    stem = word.split("'")[0]
    if not stem:
        return False
    if not stem.isalpha():
        return True
    if len(stem) == 1:
        return stem.lower() not in ('a', 'i', 'o')
    capitalized = stem[0].isupper() and stem[1:].islower()
    # Cumle ortasinda buyuk harfle baslayan kelime buyuk ihtimalle isim
    if capitalized and not sentence_start:
        return False
    # Kelime icinde rastgele buyuk harf: "wejuSrask" (FBI gibi tamami buyuk olanlar haric)
    if any(c.isupper() for c in stem[1:]) and not stem.isupper():
        return True
    if stem.isupper():
        return False
    return not _is_known(stem.lower())


def needs_fix(text):
    """Cumlede sozlukte olmayan ya da OCR bozuklugu gibi gorunen kelime var mi?"""
    # Tek kelimelik girdide (ozel isim vb.) model duzeltmek yerine aciklama yaziyor
    if len(SPACES.split(text.strip())) < 2:
        return False
    sentence_start = True
    for token in SPACES.split(text):
        core = token.strip("\"'()[]{}.,!?;:-").strip("'")
        if core and _suspicious(core, sentence_start):
            return True
        if token.strip():
            trimmed = token.rstrip('"\')')
            sentence_start = bool(trimmed) and trimmed[-1] in '.!?'
    return False


def _sanitize(raw):
    """Modelin cevabindan yalnizca duzeltilmis cumleyi alir."""
    line = ''
    for candidate in (raw or '').splitlines():
        if candidate.strip():
            line = candidate.strip()
            break
    if not line:
        return ''
    if '->' in line:
        line = line.rsplit('->', 1)[1].strip()
    return line.strip('"“”`').strip()


def _levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        curr = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            curr[j] = min(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        prev = curr
    return prev[len(b)]


def is_trustworthy(original, fixed):
    """Uydurma korumasi: cevap orijinal OCR metnine yeterince benzemeli."""
    if not fixed.strip():
        return False
    a, b = original.lower(), fixed.lower()
    ratio = len(b) / max(1, len(a))
    if ratio < 0.6 or ratio > 1.6:
        return False
    similarity = 1.0 - _levenshtein(a, b) / max(len(a), len(b), 1)
    return similarity >= 0.55


_CACHE = {}


def fix(text, model=None, stats=None):
    """Supheliyse modele gonderir; guvenilmezse ya da gecikirse orijinali dondurur."""
    if not needs_fix(text):
        return text
    # OCR ayni cumleyi kareden kareye biraz farkli okuyor; birebir ayni okuma
    # tekrar gelirse modeli yeniden calistirma
    cached = _CACHE.get(text)
    if cached is not None:
        return cached
    started = time.time()
    try:
        out = llm.chat(SYSTEM, text, model=model, temperature=0.0, num_predict=96)
    except Exception:
        return text
    elapsed = int((time.time() - started) * 1000)
    fixed = _sanitize(out)
    ok = is_trustworthy(text, fixed)
    if stats is not None:
        stats.append((text, fixed, ok, elapsed))
    result = fixed if ok else text
    if len(_CACHE) > 400:
        _CACHE.clear()
    _CACHE[text] = result
    return result
