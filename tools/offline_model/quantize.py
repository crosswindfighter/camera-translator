"""encoder + merged decoder -> int8 (dynamic). Prints graph I/O and config needed by Kotlin."""
import json
import os
import sys

import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)

for name in ("encoder_model.onnx", "decoder_model_merged.onnx"):
    out = os.path.join(dst, name)
    # EnableSubgraph: birleşik decoder If dalları içeriyor; yoksa sıkıştırılmadan ~876 MB kalıyor
    quantize_dynamic(os.path.join(src, name), out, weight_type=QuantType.QInt8,
                     extra_options={"EnableSubgraph": True})
    m = onnx.load(out, load_external_data=False)
    print(f"== {name}: {os.path.getsize(out) / 1e6:.0f} MB  ir={m.ir_version} opset={[(o.domain, o.version) for o in m.opset_import]}")
    for i in m.graph.input:
        dims = [d.dim_param or d.dim_value for d in i.type.tensor_type.shape.dim]
        print("  in ", i.name, i.type.tensor_type.elem_type, dims)
    outs = [o.name for o in m.graph.output]
    print("  out", outs[:3], "...", len(outs), "outputs")

cfg = json.load(open(os.path.join(src, "config.json")))
keys = ("d_model", "encoder_layers", "decoder_layers", "decoder_attention_heads", "vocab_size",
        "pad_token_id", "eos_token_id", "decoder_start_token_id", "bos_token_id")
print({k: cfg.get(k) for k in keys})
print(json.load(open(os.path.join(src, "tokenizer_config.json"))))
