# Gercek oyun karelerinde iki OCR motorunu kiyaslar.
#
# Dogru metin elimizde olmadigi icin olcu: okunan kelimelerin kaci GERCEK Ingilizce
# kelime. Bozuk okuma ("Dontt", "Johnnr") sozlukte yok; iyi okuma var.
#
#   python bench_real.py kareler_TARIH [adet]

import glob
import io
import os
import re
import sys
import time

import corrector

WORD = re.compile(r"[A-Za-z][A-Za-z']*")


def word_quality(text):
    """(gercek kelime sayisi, toplam kelime sayisi)"""
    c = corrector.get()
    good = total = 0
    for w in WORD.findall(text):
        core = w.strip("'").lower()
        if len(core) < 3:
            continue
        total += 1
        if c.is_known(core):
            good += 1
    return good, total


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else ''
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 150
    here = os.path.dirname(os.path.abspath(__file__))
    frames = sorted(glob.glob(os.path.join(folder, '*.jpg')))
    step = max(1, len(frames) // count)
    picked = frames[::step]

    win = {}
    tsv = os.path.join(here, 'win_sonuc.tsv')
    if os.path.isfile(tsv):
        for line in io.open(tsv, encoding='utf-8-sig').read().splitlines():
            if '\t' in line:
                k, v = line.split('\t', 1)
                win[k] = v
            elif line.strip():
                win[line.strip()] = ''

    from rapidocr_onnxruntime import RapidOCR
    engine = RapidOCR(width_height_ratio=-1, det_model_path=None,
                      det_limit_type='max', det_limit_side_len=1600)

    wg = wt = rg = rt = 0
    wempty = rempty = 0
    ms = 0.0
    samples = []
    for f in picked:
        name = os.path.basename(f)
        w = win.get(name, '')
        t0 = time.time()
        res, _ = engine(f)
        ms += (time.time() - t0) * 1000
        r = ' '.join(x[1] for x in res) if res else ''

        g, t = word_quality(w); wg += g; wt += t
        if not w.strip():
            wempty += 1
        g2, t2 = word_quality(r); rg += g2; rt += t2
        if not r.strip():
            rempty += 1
        if len(samples) < 14 and (w.strip() or r.strip()):
            samples.append((name, w, r))

    n = len(picked)
    print('kare: %d' % n)
    print()
    print('%-22s %10s %10s' % ('', 'Windows', 'PaddleOCR'))
    print('%-22s %9d%% %9d%%' % ('gercek kelime orani',
                                 (wg / wt * 100) if wt else 0, (rg / rt * 100) if rt else 0))
    print('%-22s %10d %10d' % ('okunan kelime', wt, rt))
    print('%-22s %10d %10d' % ('bos kare', wempty, rempty))
    print('%-22s %9d ms %7d ms' % ('kare basi sure', 102, ms / n))
    print()
    for name, w, r in samples:
        print(name)
        print('   win   : %s' % w[:95])
        print('   paddle: %s' % r[:95])


if __name__ == '__main__':
    main()
