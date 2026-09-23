#!/usr/bin/env python3
"""Isolate Beat This's FP16 rotary phase error on a real mel excerpt.

Run with `uv run --no-project --python 3.13 --with torch==2.11.0
--with einops --with rotary-embedding-torch --with numpy`.
"""

import argparse
import inspect
import sys
from pathlib import Path

import numpy as np
import torch


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--mel", required=True, type=Path)
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream.resolve()))

    from beat_this.model.beat_tracker import BeatThis
    from rotary_embedding_torch.rotary_embedding_torch import RotaryEmbedding

    torch.set_num_threads(4)
    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    params = {name: value for name, value in checkpoint["hyper_parameters"].items()
              if name in inspect.signature(BeatThis).parameters}
    model = BeatThis(**params).eval()
    model.load_state_dict({name.removeprefix("model."): value
                           for name, value in checkpoint["state_dict"].items()})
    sample = torch.from_numpy(np.load(args.mel)[:1500][None].astype(np.float32))
    if sample.shape != (1, 1500, 128):
        parser.error("Mel must contain at least 1500 frames and 128 bands")

    with torch.inference_mode():
        reference = model(sample)
        rotary = {id(module): module.freqs.float().clone()
                  for module in model.modules() if isinstance(module, RotaryEmbedding)}
        model.half()
        naive = model(sample.half())

        # Preserve the original frequency coefficients. The upstream rotary
        # module multiplies frame positions by self.freqs; if freqs is FP16,
        # large resulting phases are rounded before sine and cosine.
        for module in model.modules():
            if isinstance(module, RotaryEmbedding):
                module.freqs.data = rotary[id(module)]
                module.cache_if_possible = False
        stable = model(sample.half())

    for name in ("beat", "downbeat"):
        for label, output in (("naive_fp16", naive), ("fp32_rotary", stable)):
            delta = (reference[name] - output[name].float()).abs()
            print(f"{name} {label}: MAE={delta.mean().item():.6f} "
                  f"max={delta.max().item():.6f}")


if __name__ == "__main__":
    main()
