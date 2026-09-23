# Beat This checkpoint to FP32 LiteRT GPU

Direct checkpoint export now works. The experiment uses the official `final0`
checkpoint and Beat This source, [LiteRT Torch's PyTorch converter](https://developers.google.com/edge/litert/conversion/pytorch/overview), and the fixed `[1,1500,128]` input used by mobile. The reproducible exporter is
[`../tools/export_beat_litert.py`](../tools/export_beat_litert.py). The resulting
model is now the FP32 GPU path for playback analysis. Dynamic INT8 ONNX remains
the CPU fallback.

## Export

```bash
uv run --no-project --python 3.13 \
  --with 'torch==2.11.0' --with 'litert-torch==0.9.4' \
  --with einops --with rotary-embedding-torch \
  python mobile/tools/export_beat_litert.py \
  --checkpoint artifacts/beat-quant/final0.ckpt \
  --upstream artifacts/beat-quant/upstream \
  --validation-mel artifacts/beat-accuracy/gtzan_blues_00000.npy \
  --output artifacts/litert-checkpoint/beat_this_exported.tflite
```

The experiment used checkpoint SHA-256
`8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331`
and upstream commit `b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c`.
The first successful GPU candidate is 81,477,192 bytes with SHA-256
`05a70f6bd5672bf0f2fb9f1829fa12ead80001d736c8bd136f05113d668de586`.
The candidate and full logs are local, ignored files under
`artifacts/litert-checkpoint/`.

The exporter makes three inference-equivalent changes before strict Torch
export:

- Removes the output head's runtime autocast-device test, which TorchDynamo
  cannot trace. FP32 beat and downbeat logits are unchanged.
- Splits Q, K, and V directly into four-dimensional tensors and performs rotary
  pair rotation with a fixed matrix. This removes five-dimensional operations
  rejected by the phone's GPU delegate.
- Disables RotaryEmbedding's mutable 8192-position cache during export. With
  a fixed input size, its cache generated unsupported `PAD` and `BROADCAST_TO`
  operations. The uncached position values are the same.

The exporter checks the modified PyTorch model against the original, then
checks LiteRT CPU output against the original. On three real GTZAN excerpts,
beat and downbeat mean absolute logit error was approximately
`4–6e-6`; the output-head-only change was exactly equal in FP32.

## Motorola razr 2023 (SM7450)

LiteRT's Android `benchmark_model` binary, OpenCL GPU delegate, one warmup and
three timed 1500-frame runs on September 22, 2026. Times are per 30 seconds of
audio. These are model-only timings, not end-to-end Smart Crossfade timings.

| FP32 LiteRT export | GPU ops / total | Average inference | Overall benchmark footprint |
|---|---:|---:|---:|
| Supplied `beat_this_fp32_1500.tflite` | 54 / 937 | 7.39 s | 630 MB |
| Direct strict checkpoint export | 63 / 944 | 5.88 s | 646 MB |
| Four-dimensional attention | 74 / 892 | 5.27 s | 624 MB |
| Four-dimensional attention, uncached rotary | **785 / 823** | **1.36 s** | **373 MB** |

The final candidate has two GPU partitions; 38 operations still run on CPU
because the delegate does not support `BROADCAST_TO`. Its model initialization
was 7.06 s in this run. The shipping dynamic INT8 ONNX CPU path was measured
earlier at about 2.3 s per chunk on this phone, so this candidate is faster
at inference but much larger than the 21 MB shipping asset.

The phone benchmark uses generated input and reports no output tensors.
GPU-device beat and downbeat grids were subsequently checked against the v3
planner result on the Motorola razr 2023 with the real `Illegal` and
`Girl Like Me` 60-second planner windows. LiteRT 2.2.0's `CompiledModel` needs
both GPU and CPU accelerators enabled because `BROADCAST_TO` stays on CPU. Its
default GPU precision returned constant logits on this phone; explicit OpenCL
FP32 precision produced usable grids (173/50 outgoing beats/downbeats and
138/39 incoming) and the shared planner selected a 6.998-second bass swap.
The device-selected plan starts `Illegal` at 132.749 s and cues `Girl Like Me`
at 44.578 s, versus orchardv3's 132.701 s and 44.553 s. The phone-derived
listening render is in `artifacts/transition-pinkpantheress/`.
