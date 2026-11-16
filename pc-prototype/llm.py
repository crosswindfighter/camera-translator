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

