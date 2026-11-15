# Yerel LLM ile ceviri (Ollama uzerinden, GPU'da).
# OPUS'un zayif oldugu yerler icin: deyim, argo, uslup ve ONCEKI SATIRLARIN BAGLAMI.
#
# Ollama yereldeki 127.0.0.1:11434 adresinde calisir; disari hicbir veri gitmez.

import json
import os
import time
import urllib.error
import urllib.request

HOST = os.environ.get('OLLAMA_HOST', 'http://127.0.0.1:11434')
MODEL = os.environ.get('CT_LLM_MODEL', 'qwen2.5:7b-instruct-q4_K_M')
# Model VRAM'de kalsin; her cumlede yeniden yuklemek saniyeler aliyor
KEEP_ALIVE = '30m'
TIMEOUT = 60

SYSTEM = """Sen bir oyun altyazısı çevirmenisin. İngilizce altyazıyı doğal, akıcı Türkçeye çevirirsin.

Kurallar:
- SADECE çeviriyi yaz. Açıklama, not, tırnak işareti, alternatif çeviri yazma.
- Özel isimleri (kişi, yer, marka, görev adı) olduğu gibi bırak.
- İngilizce dışındaki sözleri (İspanyolca vb.) olduğu gibi bırak, çevirme.
- Küfrü aynı sertlikte çevir, yumuşatma; argoyu Türkçedeki karşılığıyla ver.
- Konuşma dilini koru, resmî yazı diline çevirme.
- Kaynakta olmayan kelime ekleme; olan bir öğeyi de atlama. Özellikle kime
  seslenildiğini ve kimin ne yaptığını koru.
- Cümle yarım kaldıysa yarım çevir, kendin tamamlama.
- Çıktı tek satır olsun."""


EDIT_SYSTEM = """Sen bir oyun altyazısı editörüsün. Sana İngilizce altyazı ve onun
makine çevirisi verilir. Çeviriyi DÜZELTİP tek satır halinde yazarsın.

Kurallar:
- SADECE düzeltilmiş Türkçe çeviriyi yaz. Açıklama, not, tırnak yazma.
- Taslak doğruysa olduğu gibi bırak; gereksiz yere değiştirme.
- Anlam hatası, devrik ifade, yanlış deyim varsa düzelt.
- Kaynakta olan hiçbir öğeyi atlama, olmayan bir şey ekleme.
- Özel isimleri ve İngilizce dışındaki sözleri olduğu gibi bırak.
- Küfrü aynı sertlikte koru, yumuşatma.
- Konuşma dilini koru."""


def post_edit(english, draft, history=None, model=MODEL):
    """OPUS taslagini LLM'e duzelttirir: sadakat OPUS'tan, akicilik LLM'den."""
    messages = [{'role': 'system', 'content': EDIT_SYSTEM}]
    for en, tr in (history or [])[-2:]:
        messages.append({'role': 'user', 'content': 'İngilizce: %s\nTaslak: %s' % (en, tr)})
        messages.append({'role': 'assistant', 'content': tr})
    messages.append({'role': 'user', 'content': 'İngilizce: %s\nTaslak: %s' % (english, draft)})
    out = _post('/api/chat', {
        'model': model,
        'messages': messages,
        'stream': False,
        'keep_alive': KEEP_ALIVE,
        'options': {'temperature': 0.1, 'top_p': 0.9, 'num_predict': 120, 'repeat_penalty': 1.05},
    })
    return _clean((out.get('message') or {}).get('content', '')) or draft


def _post(path, payload):
    data = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(HOST + path, data=data,
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read().decode('utf-8'))


def available():
    try:
        with urllib.request.urlopen(HOST + '/api/tags', timeout=3) as resp:
            tags = json.loads(resp.read().decode('utf-8'))
        return [m.get('name', '') for m in tags.get('models', [])]
    except Exception:
        return None


def warm(model=MODEL):
    """Modeli VRAM'e yukler; ilk cumlenin yavas gelmemesi icin."""
    t0 = time.time()
    _post('/api/chat', {
        'model': model,
        'messages': [{'role': 'user', 'content': 'merhaba'}],
        'stream': False,
        'keep_alive': KEEP_ALIVE,
        'options': {'num_predict': 1},
    })
    return int((time.time() - t0) * 1000)


def translate(english, history=None, model=MODEL):
    """history: [(ingilizce, turkce), ...] son birkac satir — konu takibi icin."""
    messages = [{'role': 'system', 'content': SYSTEM}]
    for en, tr in (history or [])[-3:]:
        messages.append({'role': 'user', 'content': en})
        messages.append({'role': 'assistant', 'content': tr})
    messages.append({'role': 'user', 'content': english})
    out = _post('/api/chat', {
        'model': model,
        'messages': messages,
        'stream': False,
        'keep_alive': KEEP_ALIVE,
        'options': {
            'temperature': 0.2,
            'top_p': 0.9,
            'num_predict': 120,
            'repeat_penalty': 1.05,
        },
    })
    text = (out.get('message') or {}).get('content', '')
    return _clean(text)


def chat(system, user, model=None, temperature=0.2, num_predict=120):
    """Tek seferlik istek; ham cevabi dondurur (temizleme cagirana ait)."""
    out = _post('/api/chat', {
        'model': model or MODEL,
        'messages': [{'role': 'system', 'content': system},
                     {'role': 'user', 'content': user}],
        'stream': False,
        'keep_alive': KEEP_ALIVE,
        'options': {'temperature': temperature, 'top_k': 1, 'top_p': 1.0,
                    'num_predict': num_predict, 'repeat_penalty': 1.0},
    })
    return (out.get('message') or {}).get('content', '')


def _clean(text):
    """Model bazen tirnak, on ek ya da ikinci satir ekliyor; sadece ceviriyi birak."""
    t = (text or '').strip()
    if not t:
        return ''
    t = t.split('\n')[0].strip()
    for prefix in ('Çeviri:', 'Ceviri:', 'Türkçe:', 'Turkce:'):
        if t.lower().startswith(prefix.lower()):
            t = t[len(prefix):].strip()
    if len(t) >= 2 and t[0] in '"“”' and t[-1] in '"“”':
        t = t[1:-1].strip()
    return t
