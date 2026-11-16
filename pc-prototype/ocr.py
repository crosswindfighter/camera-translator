# Ekran alanini okur: PP-OCR (PaddleOCR) modelleri, ONNX uzerinde.
#
# Windows'un kendi OCR motoru yerine bu kullaniliyor. Kullanicinin gercek oyun
# karelerinde olculdu: gercek kelime orani %76 -> %83, hic okunamayan kare 40 -> 20,
# ve en onemlisi ayni altyaziyi kareden kareye AYNI okuyor (Windows motoru
# "Dont / Dohrt / d6n't" diye her karede farkli okuyordu).
#
# Kenardaki HUD yazilari, mobildeki SubtitleRegionFilter mantigiyla atilir: PP-OCR
# zaten metin bloklarini ayri ayri veriyor, biz de ortadakileri tutuyoruz.

import os

_ENGINE = None
# Ekran karti kullanildi mi: cagiran taraf kayda yazsin diye
GPU = False

# Olculen en iyi denge: %85 dogruluk, 269 ms (1600'de %84/331 ms, 1024'te %84/255 ms)
DET_SIDE = int(os.environ.get('CT_OCR_SIDE', '1280'))
# Orta noktasi alanin ortasindan bu oran kadar uzaktaki bloklar atilir
CENTER_TOLERANCE = 0.22
# Guven esigi: bunun altindaki okumalar gurultu sayilir
MIN_SCORE = 0.55


_ORIG_INIT = None

# Ekran karti yolu her makinede hizli degil. Intel tumlesik grafikte DirectML
# olculdu: kare basi 6-28 saniye, islemci yolunun yuzlerce kati. O yuzden
# acilista bir olcum yapiliyor ve yavassa islemciye geciliyor.
GPU_ESIK_MS = 1200
SECIM_MS = 0



def _use_directml():
    """RapidOCR 1.2.3 yalnizca CUDA biliyor. Oturum kurulumunu degistirip ayni
    modelleri DirectML uzerinde calistiriyoruz: on isleme ve modeller ayni
    kaldigi icin okuma da ayni cikiyor (125 gercek karede 125'i birebir ayni),
    sadece hizlaniyor -- kare basi 298 ms -> 81 ms, p90 439 ms -> 93 ms."""
    import onnxruntime as ort
    if 'DmlExecutionProvider' not in ort.get_available_providers():
        return False
    import rapidocr_onnxruntime.utils as u
    global _ORIG_INIT
    if _ORIG_INIT is None:
        _ORIG_INIT = u.OrtInferSession.__init__

    def _init(self, config):
        opt = ort.SessionOptions()
        opt.log_severity_level = 4
        opt.enable_cpu_mem_arena = False
        # DirectML bellek deseni ve paralel yurutmeyle calismiyor
        opt.enable_mem_pattern = False
        opt.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        opt.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self._verify_model(config['model_path'])
        self.session = ort.InferenceSession(
            config['model_path'], sess_options=opt,
            providers=[('DmlExecutionProvider', {'device_id': 0}),
                       'CPUExecutionProvider'])

    u.OrtInferSession.__init__ = _init
    return True


def _cpu_ya_don():
    """DirectML yamasini geri alir."""
    global GPU
    GPU = False
    if _ORIG_INIT is not None:
        import rapidocr_onnxruntime.utils as u
        u.OrtInferSession.__init__ = _ORIG_INIT


def _kur():
    from rapidocr_onnxruntime import RapidOCR
    # width_height_ratio=-1 sart: kutuphane en/boy orani 8'i gecen goruntulerde
    # algilamayi tamamen atlayip seridi tek satir saniyor, altyazi alani 9:1
    motor = RapidOCR(width_height_ratio=-1, det_model_path=None,
                     det_limit_type='max', det_limit_side_len=DET_SIDE)
    # Aci siniflandiricisi kapali. Isi, yan yatmis ya da ters taranmis
    # belgeleri duzeltmek; oyun altyazisi hicbir zaman donuk degil. Buna
    # karsilik ara sira duzgun duran satiri "ters" sanip 180 derece
    # ceviriyordu: "I just saw a fucking ghost..." -> "...isoue buiyonj e
    # mes jsn! I". O cop okuma bos kare sayilip kutuyu kapatiyor, altyazi
    # yeniden onaylanip ikinci kez ciziliyordu. 125 gercek karede kapatmak
    # tek bir okumayi degistirmedi, kare basi 81 ms -> 47 ms.
    motor.use_angle_cls = False
    return motor


def _olcum_karesi():
    """Olcum karesi, KULLANICININ cizdigi alanla ayni olculerde olmali.

    DirectML girdi sekli degisince yeniden derliyor. Rastgele bir kareyle
    olcup gecmek yetmiyordu: olcum 73 ms cikip gercek karede 30 saniye
    surdugu goruldu, cunku gercek kare baska olcudeydi ve derleme bir daha
    bastan yapildi. Alan olculeri CT_WARM_W/CT_WARM_H ile geliyor.
    """
    import cv2
    import numpy as np
    w = int(os.environ.get('CT_WARM_W', '900') or 900)
    h = int(os.environ.get('CT_WARM_H', '80') or 80)
    w = max(120, min(w, 4000))
    h = max(30, min(h, 600))
    img = np.full((h, w, 3), 255, dtype=np.uint8)
    olcek = max(0.6, min(h / 50.0, 2.2))
    cv2.putText(img, 'warm up test line', (10, int(h * 0.7)),
                cv2.FONT_HERSHEY_SIMPLEX, olcek, (0, 0, 0), 2)
    return img


def _olc(motor):
    """Ilk cagri modelleri yukleyip gol derliyor; olcum ikinci cagriyla yapilir."""
    import time
    kare = _olcum_karesi()
    motor(kare)
    t0 = time.time()
    motor(kare)
    return (time.time() - t0) * 1000.0


def get():
    global _ENGINE, GPU, SECIM_MS
    if _ENGINE is None:
        if os.environ.get('CT_OCR_CPU') != '1':
            try:
                GPU = _use_directml()
            except Exception:
                GPU = False
        try:
            _ENGINE = _kur()
            try:
                # Olcum her iki yolda da yapiliyor: kayitta kare basi sure
                # gorunsun diye. Ekran karti yolunda ayrica karar veriyor.
                SECIM_MS = _olc(_ENGINE)
                if GPU and SECIM_MS > GPU_ESIK_MS:
                    _cpu_ya_don()
                    _ENGINE = _kur()
                    SECIM_MS = _olc(_ENGINE)
            except Exception:
                pass
        except Exception:
            # Saglayici listede gorunuyor olsa bile DirectX 12 destekleyen bir
            # ekran karti yoksa oturum burada patliyor. Baska bir makinede
            # programin hic okuyamamasi yerine islemciyle devam ediyoruz.
            if not GPU:
                raise
            _cpu_ya_don()
            _ENGINE = _kur()
    return _ENGINE


def _box_bounds(box):
    xs = [p[0] for p in box]
    ys = [p[1] for p in box]
    return min(xs), min(ys), max(xs), max(ys)


def read(path, width=None, center=True):
    """Kareyi okur. Doner: (metin, satir sayisi, (x, y, w, h)) ya da None.
    Dikdortgen kare pikselleriyle; cagiran taraf ekran koordinatina cevirir.

    center=False: ortaya denk gelme sarti aranmaz. Kullanicinin elle cizdigi
    ek alanlarda cerceve zaten dar, filtre orada sola yaslanmis kisa satirlari
    (gorev yazisinin son kelimesi gibi) atiyordu."""
    res, _ = get()(path)
    if not res:
        return None

    # Elle cizilen dar cercevede guven esigi dusuk tutulur: tek harflik tus
    # istemleri ("W", "E", "Q") kisa oldugu icin dusuk skor aliyor ve normal
    # esikte eleniyordu. Genis altyazi seridinde esik yuksek kalir, orada
    # dusurmek kenardaki gurultuyu iceri alirdi.
    esik = MIN_SCORE if center else 0.35

    blocks = []
    for item in res:
        box, text = item[0], item[1]
        # Skor metin olarak geliyor ('0.8425...'), sayiya cevrilmeli
        try:
            score = float(item[2]) if len(item) > 2 else 1.0
        except (TypeError, ValueError):
            score = 1.0
        if score < esik or not text.strip():
            continue
        x0, y0, x1, y1 = _box_bounds(box)
        blocks.append({'text': text.strip(), 'x0': x0, 'y0': y0, 'x1': x1, 'y1': y1})
    if not blocks:
        return None

    if center:
        if width is None:
            width = max(b['x1'] for b in blocks)
        orta = width / 2.0
        tolerance = width * CENTER_TOLERANCE
        kept = []
        for b in blocks:
            covers = b['x0'] <= orta <= b['x1']
            mid_near = abs((b['x0'] + b['x1']) / 2.0 - orta) <= tolerance
            if covers or mid_near:
                kept.append(b)
    else:
        kept = blocks
    if not kept:
        return None

    kept.sort(key=lambda b: (b['y0'], b['x0']))
    text = ' '.join(b['text'] for b in kept)
    x0 = min(b['x0'] for b in kept)
    y0 = min(b['y0'] for b in kept)
    x1 = max(b['x1'] for b in kept)
    y1 = max(b['y1'] for b in kept)
    return text, len(kept), (int(x0), int(y0), int(x1 - x0), int(y1 - y0))
