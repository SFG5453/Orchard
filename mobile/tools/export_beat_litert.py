#!/usr/bin/env python3
"""Export Beat This final0 directly from its checkpoint to GPU-friendly FP32 LiteRT.

Requires torch==2.11.0, litert-torch==0.9.4, einops, and
rotary-embedding-torch. The source directory is an upstream Beat This checkout.
The export fixes the 1500-frame input shape used by mobile BeatTracker.
"""

import argparse
import inspect
import sys
from pathlib import Path

import numpy as np
import torch
from ai_edge_litert.interpreter import Interpreter
import litert_torch


def install_export_forwards():
    from beat_this.model.beat_tracker import SumHead, roformer
    import rotary_embedding_torch.rotary_embedding_torch as rotary_impl

    def inference_head(self, x):
        logits = self.beat_downbeat_lin(x)
        beat, downbeat = logits[:, :, 0], logits[:, :, 1]
        return {"beat": beat.float() + downbeat.float(), "downbeat": downbeat}

    def attention_rank4(self, x):
        x = self.norm(x)
        batch, length, _ = x.shape
        q, k, v = self.to_qkv(x).chunk(3, dim=-1)
        q = q.reshape(batch, length, self.heads, -1).transpose(1, 2)
        k = k.reshape(batch, length, self.heads, -1).transpose(1, 2)
        v = v.reshape(batch, length, self.heads, -1).transpose(1, 2)
        if self.rotary_embed is not None:
            q = self.rotary_embed.rotate_queries_or_keys(q)
            k = self.rotary_embed.rotate_queries_or_keys(k)
        out = self.attend(q, k, v)
        if self.to_gates is not None:
            out = out * self.to_gates(x).transpose(1, 2).unsqueeze(-1).sigmoid()
        return self.to_out(out.transpose(1, 2).reshape(batch, length, -1))

    def rotate_half_rank4(x):
        width = x.shape[-1]
        assert width % 2 == 0
        matrix = [[0.0] * width for _ in range(width)]
        for position in range(width):
            matrix[position ^ 1][position] = -1.0 if position % 2 == 0 else 1.0
        return x @ torch.tensor(matrix, device=x.device, dtype=x.dtype)

    SumHead.forward = inference_head
    roformer.Attention.forward = attention_rank4
    rotary_impl.rotate_half = rotate_half_rank4
    return rotary_impl.RotaryEmbedding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--validation-mel", type=Path, help="Real [frames,128] .npy mel for verification")
    args = parser.parse_args()

    sys.path.insert(0, str(args.upstream.resolve()))
    from beat_this.model.beat_tracker import BeatThis

    torch.set_num_threads(4)
    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    hp = {
        name: value
        for name, value in checkpoint["hyper_parameters"].items()
        if name in inspect.signature(BeatThis).parameters
    }
    model = BeatThis(**hp).eval()
    model.load_state_dict(
        {name.removeprefix("model."): value for name, value in checkpoint["state_dict"].items()}
    )

    if args.validation_mel:
        mel = np.load(args.validation_mel).astype(np.float32)
        if mel.ndim != 2 or mel.shape[1] != 128 or mel.shape[0] < 1500:
            parser.error("--validation-mel must have at least 1500 frames and 128 bands")
        sample = torch.from_numpy(mel[:1500][None])
    else:
        generator = torch.Generator().manual_seed(0)
        sample = 8 * torch.rand((1, 1500, 128), generator=generator)

    with torch.inference_mode():
        reference = {name: value.numpy() for name, value in model(sample).items()}

    rotary_class = install_export_forwards()
    for module in model.modules():
        if isinstance(module, rotary_class):
            # Exporting the mutable 8192-position cache produces PAD and
            # BROADCAST_TO operators that strand attention on the CPU.
            module.cache_if_possible = False

    with torch.inference_mode():
        equivalent = model(sample)
    for name in ("beat", "downbeat"):
        np.testing.assert_allclose(equivalent[name].numpy(), reference[name], rtol=1e-5, atol=1e-4)

    converted = litert_torch.convert(model, (sample,), strict_export=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    converted.export(str(args.output))

    interpreter = Interpreter(model_path=str(args.output), num_threads=4)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], sample.numpy())
    interpreter.invoke()
    outputs = {
        name: interpreter.get_tensor(details["index"])
        for details in interpreter.get_output_details()
        for name in ("beat", "downbeat")
        if f"_{name}_output" in details["name"]
    }
    if set(outputs) != {"beat", "downbeat"}:
        raise RuntimeError(f"Unexpected LiteRT outputs: {interpreter.get_output_details()}")
    for name in ("beat", "downbeat"):
        delta = np.abs(reference[name] - outputs[name])
        print(f"{name}: MAE={delta.mean():.8f}, max={delta.max():.8f}", flush=True)
        np.testing.assert_allclose(outputs[name], reference[name], rtol=5e-4, atol=5e-4)
    print(f"Exported {args.output} ({args.output.stat().st_size} bytes)", flush=True)


if __name__ == "__main__":
    main()
