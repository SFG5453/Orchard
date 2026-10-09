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

# Export a HF Marian model to LiteRT with the Helsinki opus-mt_tiny signature layout:
# encode / prefill / decode, static length L, KV caches [1, L, heads, head_dim], additive pad_mask.
import sys, torch, torch.nn.functional as F
from transformers import MarianMTModel

NAME, OUT = sys.argv[1], sys.argv[2]
L = int(sys.argv[3]) if len(sys.argv) > 3 else 256
QUANT = sys.argv[4] if len(sys.argv) > 4 else "dyn"  # dyn | w8 | fp32
NEG = -1e9

# "repo@commit" pins the source revision.
REPO, _, REVISION = NAME.partition("@")
hf = MarianMTModel.from_pretrained(REPO, revision=REVISION or None, dtype=torch.float32).eval()
# Some older checkpoints only store model.shared; newer transformers leaves the copies random.
if not torch.equal(hf.model.encoder.embed_tokens.weight, hf.model.shared.weight):
    hf.model.encoder.embed_tokens.weight = hf.model.shared.weight
    hf.model.decoder.embed_tokens.weight = hf.model.shared.weight
    hf.lm_head.weight = hf.model.shared.weight
cfg = hf.config
D, H = cfg.d_model, cfg.decoder_attention_heads
DH = D // H
enc_m, dec_m = hf.model.encoder, hf.model.decoder
EMB = hf.model.shared
POS = enc_m.embed_positions.weight.detach()
SCALE = enc_m.embed_scale if hasattr(enc_m, "embed_scale") else D ** 0.5
ACT = {"swish": F.silu, "silu": F.silu, "relu": F.relu, "gelu": F.gelu}[cfg.activation_function]
NDEC = len(dec_m.layers)


def heads(x):
    return x.view(1, -1, H, DH)


def attend(q, k, v, mask):
    # q [1,T,H,DH], k/v [1,S,H,DH], mask broadcastable to [1,H,T,S]
    s = torch.einsum("bthd,bshd->bhts", q, k) * DH ** -0.5 + mask
    return torch.einsum("bhts,bshd->bthd", s.softmax(-1), v).reshape(1, -1, D)


def ffn(layer, x):
    return layer.final_layer_norm(x + layer.fc2(ACT(layer.fc1(x))))


class Encode(torch.nn.Module):
    def forward(self, input_ids, input_pos, pad_mask):
        x = EMB(input_ids) * SCALE + POS[input_pos][None]
        mask = pad_mask.view(1, 1, 1, L)
        for ly in enc_m.layers:
            a = ly.self_attn
            x = ly.self_attn_layer_norm(x + a.out_proj(attend(heads(a.q_proj(x)), heads(a.k_proj(x)), heads(a.v_proj(x)), mask)))
            x = ffn(ly, x)
        return {"output_0": x}


class Step(torch.nn.Module):
    # prefill computes cross-attention KV from encoder states; decode reuses the cache it is given.
    def __init__(self, prefill):
        super().__init__()
        self.prefill = prefill

    def forward(self, decoder_input_ids, decoder_input_pos, encoder_hidden_states, pad_mask, **kv):
        x = EMB(decoder_input_ids) * SCALE + POS[decoder_input_pos][None]
        causal = torch.where(torch.arange(L) <= decoder_input_pos, 0.0, NEG).view(1, 1, 1, L)
        cross_mask = pad_mask.view(1, 1, 1, L)
        out = {}
        for i, ly in enumerate(dec_m.layers):
            a = ly.self_attn
            sk = kv[f"self_attn_kv_cache_k_{i}"].index_copy(1, decoder_input_pos.long(), heads(a.k_proj(x)))
            sv = kv[f"self_attn_kv_cache_v_{i}"].index_copy(1, decoder_input_pos.long(), heads(a.v_proj(x)))
            out[f"self_attn_kv_cache_k_{i}"], out[f"self_attn_kv_cache_v_{i}"] = sk, sv
            x = ly.self_attn_layer_norm(x + a.out_proj(attend(heads(a.q_proj(x)), sk, sv, causal)))
            c = ly.encoder_attn
            if self.prefill:
                ck, cv = heads(c.k_proj(encoder_hidden_states)), heads(c.v_proj(encoder_hidden_states))
            else:
                ck, cv = kv[f"cross_attn_kv_cache_k_{i}"], kv[f"cross_attn_kv_cache_v_{i}"]
            out[f"cross_attn_kv_cache_k_{i}"], out[f"cross_attn_kv_cache_v_{i}"] = ck, cv
            x = ly.encoder_attn_layer_norm(x + c.out_proj(attend(heads(c.q_proj(x)), ck, cv, cross_mask)))
            x = ffn(ly, x)
        out["logits"] = hf.lm_head(x) + hf.final_logits_bias
        return out


def step_args():
    # Distinct tensors: shared sample objects get aliased into a single graph input.
    a = {f"{s}_attn_kv_cache_{t}_{i}": torch.zeros(1, L, H, DH) for s in ("self", "cross") for t in "kv" for i in range(NDEC)}
    a.update(decoder_input_ids=torch.zeros(1, 1, dtype=torch.int32), decoder_input_pos=torch.zeros(1, dtype=torch.int32),
             encoder_hidden_states=torch.zeros(1, L, D), pad_mask=torch.zeros(L))
    return a


enc_args = dict(input_ids=torch.zeros(1, L, dtype=torch.int32), input_pos=torch.arange(L, dtype=torch.int32), pad_mask=torch.zeros(L))

if __name__ == "__main__":
    import litert_torch
    from litert_torch.generative.quantize import quant_recipes
    qc = {"dyn": quant_recipes.full_dynamic_recipe, "w8": quant_recipes.full_weight_only_recipe, "fp32": lambda: None}[QUANT]()
    with torch.no_grad():
        m = (litert_torch.signature("encode", Encode().eval(), sample_kwargs=enc_args)
             .signature("prefill", Step(True).eval(), sample_kwargs=step_args())
             .signature("decode", Step(False).eval(), sample_kwargs=step_args())
             .convert(quant_config=qc))
    m.export(OUT)
    print("wrote", OUT)
