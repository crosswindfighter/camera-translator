# OPUS ile yerel LLM'i ayni cumlelerde yan yana cevirir, sureleri olcer.
#
#   python compare_llm.py            -> bilinen zor cumleler + kayittan ornek
#   python compare_llm.py 40         -> kayittan 40 rastgele cumle
#   python compare_llm.py baglam     -> ardisik satirlarda konu takibi testi

import glob
import io
import os
import random
import re
import sys
import time

import llm
from translate import Engine

# OPUS'un bu oturumlarda takildigi, elle secilmis cumleler
HARD = [
    "Much as I love dodging the law and sleeping in the dirt with you derelicts, I do have other business to attend to.",
    "I'll go around and create a distraction, then you two sneak across and do the dirty on them.",
    "More coming in on horses!",
    "Maybe we should turn him in ourselves.",
    "A pleasure as always, gentlemen. I think you have it from here.",
    "Wouldn't go that far.",
    "Well, you can only imagine the shit he's been giving them.",
    "Goddamn army of these bastards. How much is Sean's bounty?",
    "He hasn't stopped talking since we left you in Valentine.",
    "Let's take these hijos de putas!",
    "Keep your eyes open for Pinkertons. They've got patrols out all over this area.",
    "On top of the tower, who's got a shot on him?",
]

# Konu takibi: tek tek cevrilince ozne kayiyor
CONTEXT = [
    "So who are these bounty hunters?",
    "Don't know too much about Ike Skelding's boys,",
    "but I hear they're a big crew and wild.",
    "Built some reputation in the last year or two.",
    "Still time.",
]


def from_log(n):
    rows = []
    for f in sorted(glob.glob(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kayit_*.txt'))):
        for line in io.open(f, encoding='utf-8-sig').read().splitlines():
            m = re.match(r'^\d\d:\d\d:\d\d\.\d\d\d\s+\| EN=(.*)$', line)
            if m:
                t = m.group(1).strip()
                if len(t) > 15:
                    rows.append(t)
    rows = list(dict.fromkeys(rows))
    random.seed(7)
    return random.sample(rows, min(n, len(rows)))


def bench(models, rows):
    """Ayni cumleleri birden cok modelde cevirip yan yana yazar."""
    engine = Engine()
    times = {m: [] for m in models}
    opus_ms = []
    for m in models:
        print('%s isiniyor: %d ms' % (m, llm.warm(m)))
    print()
    for en in rows:
        t0 = time.time(); opus = engine.translate(en); opus_ms.append((time.time() - t0) * 1000)
        print('EN   : %s' % en)
        print('OPUS : %s' % opus)
        for m in models:
            t0 = time.time()
            try:
                out = llm.translate(en, model=m)
            except Exception as exc:
                out = '<hata: %s>' % exc
            times[m].append((time.time() - t0) * 1000)
            print('%-5s: %s' % (m.split(':')[0][:5], out))
        print()

    def med(a):
        s = sorted(a)
        return s[len(s) // 2] if s else 0
    print('-' * 70)
    print('%-28s medyan %4d ms' % ('OPUS', med(opus_ms)))
    for m in models:
        print('%-28s medyan %4d ms' % (m, med(times[m])))


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else ''
    if arg == 'bench':
        models = sys.argv[2].split(',')
        n = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        rows = HARD + (from_log(n) if n else [])
        bench(models, rows)
        return
    models = llm.available()
    if models is None:
        print('Ollama calismiyor (127.0.0.1:11434). Once "ollama serve".')
        return
    if not any(m.startswith(llm.MODEL.split(':')[0]) for m in models):
        print('Model yok: %s\nKurulu olanlar: %s' % (llm.MODEL, ', '.join(models) or '(hic)'))
        return

    print('model isiniyor...')
    print('  VRAM yukleme: %d ms\n' % llm.warm())

    engine = Engine()

    if arg == 'baglam':
        print('=== BAGLAM TESTI: ardisik satirlar ===\n')
        history = []
        for en in CONTEXT:
            t0 = time.time(); opus = engine.translate(en); to = (time.time() - t0) * 1000
            t0 = time.time(); out = llm.translate(en, history); tl = (time.time() - t0) * 1000
            history.append((en, out))
            print('EN   : %s' % en)
            print('OPUS : %s   [%d ms]' % (opus, to))
            print('LLM  : %s   [%d ms]' % (out, tl))
            print()
        return

    rows = HARD if not arg.isdigit() else from_log(int(arg))
    opus_ms, llm_ms = [], []
    for en in rows:
        t0 = time.time(); opus = engine.translate(en); opus_ms.append((time.time() - t0) * 1000)
        t0 = time.time(); out = llm.translate(en); llm_ms.append((time.time() - t0) * 1000)
        print('EN   : %s' % en)
        print('OPUS : %s' % opus)
        print('LLM  : %s' % out)
        print()

    def med(a):
        s = sorted(a)
        return s[len(s) // 2] if s else 0
    print('-' * 70)
    print('cumle: %d' % len(rows))
    print('OPUS medyan: %4d ms   en yuksek %4d ms' % (med(opus_ms), max(opus_ms or [0])))
    print('LLM  medyan: %4d ms   en yuksek %4d ms' % (med(llm_ms), max(llm_ms or [0])))


if __name__ == '__main__':
    main()
