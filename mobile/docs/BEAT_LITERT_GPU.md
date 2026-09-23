# Beat This checkpoint to FP16 LiteRT GPU

Playback analysis uses the official Beat This **final0** checkpoint as a fixed
1500-frame LiteRT model. The app ships
[`beat_this_fp16_gpu.tflite`](../android/app/src/main/assets/beat_this_fp16_gpu.tflite)
and runs it with LiteRT 2.2.0, OpenCL, explicit FP16 GPU precision, and CPU
fallback for unsupported operators. If the GPU path cannot compile or infer,
`BeatTracker` uses the shipped dynamic INT8 ONNX CPU model.

The model file is 41,408,084 bytes (SHA-256
`ca2250d78fabed6cd87d8067032fea462b3c98ccbfe8d6ee569d3e271acc26f9`).
Its input, output, and serialized operation boundaries are FP32; weights are
stored in FP16. Selected GPU operations compute with FP16 precision.

## Rebuild the model

Use the official [Beat This source](https://github.com/CPJKU/beat_this) at
commit `b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c` and the official
[`final0.ckpt`](https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt)
(SHA-256 `8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331`).
These commands use temporary intermediates and place the result in the app:

```bash
uv run --no-project --python 3.13 \
  --with 'torch==2.11.0' --with 'litert-torch==0.9.4' \
  --with einops --with rotary-embedding-torch \
  python mobile/tools/export_beat_litert.py \
  --checkpoint /path/to/final0.ckpt \
  --upstream /path/to/beat_this \
  --output /tmp/beat_this_fp32.tflite

uv run --no-project --python 3.13 \
  --with ai-edge-litert --with flatbuffers --with numpy \
  python mobile/tools/pack_beat_fp16_weights.py \
  /tmp/beat_this_fp32.tflite /tmp/beat_this_fp16_weights.tflite

uv run --no-project --python 3.13 \
  --with ai-edge-litert --with flatbuffers --with numpy \
  python mobile/tools/stabilize_beat_fp16_gpu.py \
  /tmp/beat_this_fp16_weights.tflite \
  mobile/android/app/src/main/assets/beat_this_fp16_gpu.tflite
```

The [exporter](../tools/export_beat_litert.py) removes an output-head
autocast-device test, uses four-dimensional rotary attention, and disables a
mutable rotary cache so the fixed graph can run on the phone GPU. The initial
FP32 export was 81,477,192 bytes. The
[weight packer](../tools/pack_beat_fp16_weights.py) keeps the large rotary
phase table in FP32: rounding angles as large as 1499 radians to FP16
corrupted beat predictions.

The [FP16 stabilizer](../tools/stabilize_beat_fp16_gpu.py) addresses two
arithmetic errors. It changes 13 RMS normalization reductions from
`sum(x²)` with a `sqrt(512)` output scale to the equivalent `mean(x²)`
form, avoiding FP16 overflow. It also precomputes four rotary sine/cosine
pairs from the original high-precision phase tables. Without these changes,
OpenCL FP16 returned nearly constant logits on the tested phone.

## Accuracy and phone measurements

The rebuilt model's LiteRT CPU outputs differed from the packed FP32 source
by 0.00000169 beat and 0.00000156 downbeat mean absolute logit error across
100 GTZAN excerpts. All 5,785 beat peaks and 1,802 downbeat peaks matched
within four frames. The tracked
[comparison tool](../tools/compare_beat_fp16_weights.py) can repeat the host
comparison.

The following isolated 1500-frame model probes ran on a Motorola razr 2023
(SM7450) with explicit OpenCL FP16. MAE and peak agreement compare with the
packed model's host FP32 output; peak timing tolerance is four frames.

| Input | Beat MAE | Downbeat MAE | Beat peaks | Downbeat peaks | Inference |
|---|---:|---:|---:|---:|---:|
| GTZAN blues 00000 | 0.040 | 0.047 | — | — | 298 ms |
| `Illegal` tail | 0.107 | 0.119 | 71/71 | 24/25 | 242–255 ms |
| `Girl Like Me` head | 0.087 | 0.064 | 69/69 | 18/18 | 238 ms |

The same repaired model with OpenCL FP32 took 276 ms on the `Illegal` tail
and matched all 71 beats and 25 downbeats. GPU compilation took about 11
seconds in these probes. These are model-only timings, not playback or battery
measurements. The opt-in
[device probe](../android/app/src/androidTest/java/dev/sfg/orchard/mobile/playback/smart/BeatFp16GpuProbeDeviceTest.kt)
exercises the precision modes.

The Android 60-second
[planner input test](../android/app/src/androidTest/java/dev/sfg/orchard/mobile/playback/smart/V3PlannerInputDeviceTest.kt)
selected a 6.998512-second `bass_swap` from `Illegal` to `Girl Like Me`.
It started the outgoing song at 132.741010 s and cued the incoming song at
44.577700 s. The prior FP32 phone plan started at 132.749169 s and cued at
44.578440 s. This verifies the planner choice on the tested phone; the
listening preview was rendered on the host from that phone plan, rather than
captured from the Android mixer.

The integrated planner test also passed with 180 MiB of retained Java heap
pressure while both song windows used the FP16 GPU runner.

### Memory on the Motorola razr 2023

Two fresh instrumentation processes per precision ran the same repaired
41.4 MB model and `Illegal` input. Android `Debug.MemoryInfo` measured
process PSS before compilation and immediately after inference.
`summary.graphics` is included in PSS.

| OpenCL precision | PSS after inference | Increase from precompile PSS | Graphics PSS | Peak process RSS |
|---|---:|---:|---:|---:|
| FP32 | 512–519 MiB | 355–363 MiB | 128 MiB | 690–704 MiB |
| FP16 | 421–422 MiB | 262–264 MiB | 87 MiB | 606–609 MiB |

FP16 saved 92–99 MiB of model-attributable PSS in these paired runs.
Native heap allocation after inference was about 136 MiB in both modes.
The measurements cover isolated model inference, not full playback.

## Limits

The model was validated on one phone. Other GPUs may have different LiteRT
precision behavior. The app catches GPU initialization and inference failure
and falls back to INT8 CPU. The FP16 model's peak agreement is strong in the
tested transition, but its logits differ more from FP32 than the
weights-only graph did; broader labeled accuracy evaluation remains useful.
