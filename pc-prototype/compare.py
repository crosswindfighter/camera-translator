# Eski kayitlardaki (run*.txt) Ingilizce satirlari hem duzeltme katmani ACIK hem KAPALI
# cevirir, sadece FARK EDENLERI yazar. Amac: tasinan kurallar bir sey bozuyor mu?

import glob
import os
import sys

from translate import Engine
import slang


def read_lines(folder):
    seen = set()
    out = []
    for path in sorted(glob.glob(os.path.join(folder, "run*.txt"))):
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line.startswith("EN "):
                    continue
                text = line[3:].strip()
                if len(text) < 3 or text in seen:
                    continue
                seen.add(text)
                out.append(text)
    return out


def main():
    folder = os.path.dirname(os.path.abspath(__file__))
    lines = read_lines(folder)
    print("%d benzersiz altyazi bulundu." % len(lines))

    # Once kural metnin kendisini degistiriyor mu diye bak: cogu satir hic etkilenmiyor,
    # onlari modele iki kez sokmaya gerek yok
    touched = [t for t in lines if slang.normalize(t) != t]
    print("%d tanesinde kural devreye giriyor.\n" % len(touched))
    if not touched:
        return

    plain = Engine(use_slang=False)
    smart = Engine(use_slang=True)
    smart.tok = plain.tok
    smart.encoder = plain.encoder
    smart.decoder = plain.decoder
    smart.decoder_outputs = plain.decoder_outputs

    changed = 0
    for text in touched:
        before = plain.translate(text)
        after = smart.translate(text)
        if before == after:
            continue
        changed += 1
        print("EN     %s" % text)
        print("  ara  %s" % slang.normalize(text))
        print("  once %s" % before)
        print("  simdi %s" % after)
        print()

    print("Ceviri ciktisi degisen: %d / %d" % (changed, len(touched)))


if __name__ == "__main__":
    main()
