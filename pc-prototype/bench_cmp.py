# Kiyas: Windows OCR (bench_win.ps1 yazdi) vs RapidOCR (PP-OCR, ONNX).
# Her kare icin dogru metne benzerlik ve sure olculur.
#
#   python bench_cmp.py            CPU
#   python bench_cmp.py gpu        onnxruntime-gpu varsa GPU

import glob
import io
import os
import sys
import time
import difflib

DIR = os.path.join(os.environ.get('TEMP', '.'), 'ocrbench')


def norm(s):
    return ' '.join(''.join(c.lower() if (c.isalnum() or c in " '") else ' ' for c in s).split())


def score(truth, got):
    return difflib.SequenceMatcher(None, norm(truth), norm(got)).ratio()


def main():
    use_gpu = len(sys.argv) > 1 and sys.argv[1] == 'gpu'
    from rapidocr_onnxruntime import RapidOCR
    engine = RapidOCR() if not use_gpu else RapidOCR(det_use_cuda=True, rec_use_cuda=True, cls_use_cuda=True)

    frames = sorted(glob.glob(os.path.join(DIR, '*.jpg')))
    print('%-14s %7s %7s %7s   %s' % ('kare', 'Windows', 'Rapid', 'Rapid+', 'sure (ham/esikli)'))
    tw = tr = tp = 0.0
    times_raw = []
    times_pre = []
    for f in frames:
        name = os.path.splitext(os.path.basename(f))[0]
        truth = io.open(os.path.join(DIR, name + '.txt'), encoding='utf-8-sig').read().strip()
        win_path = os.path.join(DIR, 'win_' + name + '.txt')
        win = io.open(win_path, encoding='utf-8-sig').read().strip() if os.path.isfile(win_path) else ''

        t0 = time.time()
        res, _ = engine(f)
        times_raw.append((time.time() - t0) * 1000)
        raw_text = ' '.join(r[1] for r in res) if res else ''

        pre = os.path.join(DIR, 'pre_' + name + '.bmp')
        pre_text = ''
        if os.path.isfile(pre):
            t0 = time.time()
            res2, _ = engine(pre)
            times_pre.append((time.time() - t0) * 1000)
            pre_text = ' '.join(r[1] for r in res2) if res2 else ''

        sw, sr, sp = score(truth, win), score(truth, raw_text), score(truth, pre_text)
        tw += sw; tr += sr; tp += sp
        print('%-14s %6.0f%% %6.0f%% %6.0f%%   %4.0f / %4.0f ms' % (
            name, sw * 100, sr * 100, sp * 100, times_raw[-1], times_pre[-1] if times_pre else 0))
        print('    dogru : %s' % truth[:92])
        print('    win   : %s' % win[:92])
        print('    rapid : %s' % raw_text[:92])
        print('    rapid+: %s' % pre_text[:92])
        print()

    n = len(frames)
    print('-' * 72)
    print('ORTALAMA     Windows %.0f%%   Rapid(ham) %.0f%%   Rapid(esikli) %.0f%%' % (
        tw / n * 100, tr / n * 100, tp / n * 100))
    print('SURE         Rapid ham %.0f ms   esikli %.0f ms' % (
        sum(times_raw) / max(1, len(times_raw)), sum(times_pre) / max(1, len(times_pre))))


if __name__ == '__main__':
    main()
