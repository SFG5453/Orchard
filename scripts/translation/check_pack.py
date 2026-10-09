#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

# Greedy-decodes lines with a pack folder. Usage: check_pack.py <dir> <lines-file> [threads]
import json, sys, time, numpy as np, sentencepiece as spm
from ai_edge_litert.interpreter import Interpreter
d = sys.argv[1]; lines = open(sys.argv[2]).read().split("\n"); lines = [l for l in lines if l]
it = Interpreter(model_path=f"{d}/model.tflite", num_threads=int(sys.argv[3]) if len(sys.argv) > 3 else 1)
enc, pre, dec = (it.get_signature_runner(s) for s in ("encode", "prefill", "decode"))
ins = pre.get_input_details()
L = int(enc.get_input_details()["input_ids"]["shape"][1])
kvz = {k: np.zeros(v["shape"], np.float32) for k, v in ins.items() if "kv_cache" in k}
sp = spm.SentencePieceProcessor(model_file=f"{d}/source.spm"); vocab = json.load(open(f"{d}/vocab.json")); inv = {v: k for k, v in vocab.items()}
PAD, EOS, UNK = 32000, 0, vocab["<unk>"]
def translate(text):
    ids = [vocab.get(p, UNK) for p in sp.encode(text, out_type=str)] + [EOS]; n = len(ids)
    x = np.full((1, L), PAD, np.int32); x[0, :n] = ids
    pm = np.zeros(L, np.float32); pm[n:] = -1e9
    h = enc(input_ids=x, input_pos=np.arange(L, dtype=np.int32), pad_mask=pm)["output_0"]
    kv, tok, out, step = kvz, PAD, [], pre
    for pos in range(min(L - 1, 2 * n + 16)):
        r = step(decoder_input_ids=np.array([[tok]], np.int32), decoder_input_pos=np.array([pos], np.int32), encoder_hidden_states=h, pad_mask=pm, **kv)
        kv = {k: r[k] for k in kv}; step = dec
        lg = r["logits"][0, -1]; lg[PAD] = -1e9
        tok = int(lg.argmax())
        if tok == EOS: break
        out.append(inv.get(tok, ""))
    return "".join(out).replace("▁", " ").strip()
tot = 0
for l in lines:
    t = time.perf_counter(); s = translate(l); ms = (time.perf_counter() - t) * 1000; tot += ms
    print(f"{ms:6.0f} ms  {l} -> {s}")
print(f"total {tot:.0f} ms for {len(lines)} lines")
