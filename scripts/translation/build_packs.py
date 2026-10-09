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

# Builds the packs Orchard hosts itself: converts each source model at a pinned commit,
# checks LiteRT against PyTorch greedy decoding, and writes <out>/<pack>/ plus manifest.json.
# Usage: build_packs.py <out-dir> [pack ...]
import hashlib, json, os, subprocess, sys, warnings
warnings.filterwarnings("ignore")

import numpy as np, sentencepiece as spm, torch
from ai_edge_litert.interpreter import Interpreter
from huggingface_hub import HfApi, hf_hub_download
from transformers import MarianMTModel, MarianTokenizer

HERE = os.path.dirname(os.path.abspath(__file__))

# pack directory -> (source repo, quality, credit)
PACKS = {
    "jpn-eng": ("Mitsua/elan-mt-tiny-ja-en", "standard", "ElanMT tiny by the ELAN MITSUA Project (CC BY-SA 4.0)"),
    "kor-eng-high": ("Helsinki-NLP/opus-mt-ko-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "jpn-eng-high": ("Helsinki-NLP/opus-mt-ja-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "zho-eng-high": ("Helsinki-NLP/opus-mt-zh-en", "high", "OPUS-MT by Helsinki-NLP (CC BY 4.0)"),
    "rus-eng-high": ("Helsinki-NLP/opus-mt-ru-en", "high", "OPUS-MT by Helsinki-NLP (CC BY 4.0)"),
    "ara-eng-high": ("Helsinki-NLP/opus-mt-ar-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "ell-eng-high": ("Helsinki-NLP/opus-mt-grk-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "tur-eng-high": ("Helsinki-NLP/opus-mt-tr-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "fra-eng-high": ("Helsinki-NLP/opus-mt-fr-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "deu-eng-high": ("Helsinki-NLP/opus-mt-de-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "spa-eng-high": ("Helsinki-NLP/opus-mt-es-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "ita-eng-high": ("Helsinki-NLP/opus-mt-it-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "nld-eng-high": ("Helsinki-NLP/opus-mt-nl-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
    "cat-eng-high": ("Helsinki-NLP/opus-mt-ca-en", "high", "OPUS-MT by Helsinki-NLP (Apache-2.0)"),
}

# Lyric-shaped lines per source language, for the parity check.
SAMPLES = {
    "kor": ["너를 생각하면 잠이 안 와", "오늘 밤 너와 춤추고 싶어", "사랑해 baby 영원히", "눈물이 멈추지 않아", "답답해서 그래"],
    "jpn": ["君のことを考えると眠れない", "今夜は君と踊りたい", "愛してる baby ずっと", "涙が止まらない", "さよならは言わないで"],
    "zho": ["我想你想得睡不着", "今晚我想和你跳舞", "我爱你 baby 永远", "眼泪停不下来", "不要说再见"],
    "rus": ["Я не могу спать, думая о тебе", "Сегодня ночью я хочу танцевать с тобой", "Я люблю тебя, малыш, навсегда", "Слёзы не останавливаются", "Не говори прощай"],
    "ara": ["لا أستطيع النوم وأنا أفكر فيك", "أريد أن أرقص معك الليلة", "أحبك يا حبيبي إلى الأبد", "دموعي لا تتوقف", "لا تقل وداعا"],
    "ell": ["Δεν μπορώ να κοιμηθώ όταν σε σκέφτομαι", "Απόψε θέλω να χορέψω μαζί σου", "Σ' αγαπώ για πάντα", "Τα δάκρυα δεν σταματούν", "Μην πεις αντίο"],
    "tur": ["Seni düşününce uyuyamıyorum", "Bu gece seninle dans etmek istiyorum", "Seni seviyorum bebeğim sonsuza dek", "Gözyaşlarım durmuyor", "Elveda deme"],
    "fra": ["Je ne peux pas dormir quand je pense à toi", "Ce soir je veux danser avec toi", "Je t'aime pour toujours, bébé", "Mes larmes ne s'arrêtent pas", "Ne dis pas adieu"],
    "deu": ["Ich kann nicht schlafen, wenn ich an dich denke", "Heute Nacht will ich mit dir tanzen", "Ich liebe dich für immer, Baby", "Meine Tränen hören nicht auf", "Sag nicht auf Wiedersehen"],
    "spa": ["No puedo dormir pensando en ti", "Esta noche quiero bailar contigo", "Te quiero para siempre, bebé", "Mis lágrimas no paran", "No digas adiós"],
    "ita": ["Non riesco a dormire pensando a te", "Stanotte voglio ballare con te", "Ti amo per sempre, baby", "Le mie lacrime non si fermano", "Non dire addio"],
    "nld": ["Ik kan niet slapen als ik aan je denk", "Vannacht wil ik met je dansen", "Ik hou voor altijd van je, schat", "Mijn tranen stoppen niet", "Zeg geen vaarwel"],
    "cat": ["No puc dormir pensant en tu", "Aquesta nit vull ballar amb tu", "T'estimo per sempre, nena", "Les llàgrimes no s'aturen", "No diguis adéu"],
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def litert_translate(folder, lines):
    it = Interpreter(model_path=f"{folder}/model.tflite", num_threads=2)
    enc, pre, dec = (it.get_signature_runner(s) for s in ("encode", "prefill", "decode"))
    L = int(enc.get_input_details()["input_ids"]["shape"][1])
    kv0 = {k: np.zeros(v["shape"], np.float32) for k, v in pre.get_input_details().items() if "kv_cache" in k}
    sp = spm.SentencePieceProcessor(model_file=f"{folder}/source.spm")
    vocab = json.load(open(f"{folder}/vocab.json"))
    inv = {v: k for k, v in vocab.items()}
    pad, eos = vocab["<pad>"], vocab["</s>"]
    out = []
    for text in lines:
        ids = [vocab.get(p, vocab["<unk>"]) for p in sp.encode(text, out_type=str)] + [eos]
        n = len(ids)
        x = np.full((1, L), pad, np.int32); x[0, :n] = ids
        pm = np.zeros(L, np.float32); pm[n:] = -1e9
        h = enc(input_ids=x, input_pos=np.arange(L, dtype=np.int32), pad_mask=pm)["output_0"]
        kv, tok, pieces, step = kv0, pad, [], pre
        for pos in range(min(L - 1, 3 * n + 8)):
            r = step(decoder_input_ids=np.array([[tok]], np.int32), decoder_input_pos=np.array([pos], np.int32),
                     encoder_hidden_states=h, pad_mask=pm, **kv)
            kv = {k: r[k] for k in kv}; step = dec
            lg = r["logits"][0, -1]; lg[pad] = -1e9
            tok = int(lg.argmax())
            if tok == eos:
                break
            pieces.append(inv.get(tok, ""))
        out.append(" ".join("".join(pieces).replace("▁", " ").split()))
    return out


def torch_translate(repo, lines):
    tok = MarianTokenizer.from_pretrained(repo)
    m = MarianMTModel.from_pretrained(repo).eval()
    with torch.no_grad():
        return [tok.decode(m.generate(**tok(l, return_tensors="pt"), num_beams=1, max_new_tokens=64)[0],
                           skip_special_tokens=True) for l in lines]


def build(out_dir, name):
    repo, quality, credit = PACKS[name]
    revision = HfApi().model_info(repo).sha
    folder = os.path.join(out_dir, name)
    os.makedirs(folder, exist_ok=True)
    for f in ("source.spm", "vocab.json"):
        src = hf_hub_download(repo, f, revision=revision)
        with open(src, "rb") as a, open(os.path.join(folder, f), "wb") as b:
            b.write(a.read())
    model = os.path.join(folder, "model.tflite")
    if not os.path.exists(model):
        subprocess.run([sys.executable, os.path.join(HERE, "convert_marian.py"), f"{repo}@{revision}", model, "256", "dyn"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    lines = SAMPLES[name[:3]]
    ours, ref = litert_translate(folder, lines), torch_translate(repo, lines)
    same = sum(a == b for a, b in zip(ours, ref))
    print(f"{name}: {same}/{len(lines)} lines match PyTorch")
    for l, a, b in zip(lines, ours, ref):
        print(f"   {l} -> {a}" + ("" if a == b else f"   [torch: {b}]"))
    return {
        "source": repo, "revision": revision, "quality": quality, "credit": credit, "parity": f"{same}/{len(lines)}",
        "files": {f: {"size": os.path.getsize(os.path.join(folder, f)), "sha256": sha256(os.path.join(folder, f))}
                  for f in ("model.tflite", "source.spm", "vocab.json")},
    }


if __name__ == "__main__":
    out_dir = sys.argv[1]
    names = sys.argv[2:] or list(PACKS)
    path = os.path.join(out_dir, "manifest.json")
    manifest = json.load(open(path)) if os.path.exists(path) else {}
    for name in names:
        manifest[name] = build(out_dir, name)
        json.dump(manifest, open(path, "w"), indent=1, ensure_ascii=False)
