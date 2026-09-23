# Beat This! beat/downbeat model

`android/app/src/main/assets/beat_this_int8.onnx` ships the official **final0**
checkpoint as a fixed 1500-frame, dynamically quantized INT8 graph (21,068,518
bytes). Production `BeatTracker` explicitly uses CPU with four threads; the
open-unmix vocal separator also explicitly uses CPU. Stock ONNX Runtime Android 1.29.0 supplies the runtime. QNN dependencies, startup
probing and NPU device tests have been removed.

The asset SHA-256 is
`33920bdfe3342cabe0f17350f0e9b3fe1dca6aedca2831a4ad18b45407a41d0d`.
It is the `final0_int8.onnx` artifact from the September 2026 benchmark,
exported from the official checkpoint with upstream CPJKU code and prepared
using `tools/prepare_beat_quant.py`. The versioned extracted filename prevents
older installed copies of small0 from being reused after an app update.

APK compression is retained. Both trackers stream the asset to an extracted
file and pass its path to ONNX Runtime; they do not read the complete compressed
and decompressed models into Java byte arrays. Compression affects package size
and first-use extraction, not the model's inference working-set requirement.

## Licensing

Both the Beat This! **code and trained weights are MIT-licensed**
(<https://github.com/CPJKU/beat_this>), which is the reason this model was
chosen: most published MIR model weights (including Essentia's) are
CC BY-NC-SA and cannot ship in a distributed application. This export uses the official CPJKU implementation; earlier mobile exports
used the MIT-licensed C++ port (<https://github.com/mosynthkey/beat_this_cpp>).

## Why a model at all

Tempo and *meter* are different problems. Autocorrelation reads tempo well, but
nothing in an autocorrelation says which of four beats is beat one, and a mix
that enters on beat three of the bar sounds wrong even when every beat lines up.

## Contract

- Input `input_spectrogram`: `[1, 1500, 128]` log-mel spectrogram, 22,050 Hz
  audio, n_fft 1024, hop 441 (50 fps), Slaney mel 30–11,000 Hz,
  `log1p(1000·mag)` — produced by Earmark's shared Rust model frontend.
- Outputs `beat`, `downbeat`: `[1, 1500]` logits, peak-picked by
  `BeatTracker.pickPeaks`.
- Chunked at 1500 frames with a 6-frame border discarded from each edge, which
  discards unreliable boundary predictions. Short inputs and the final partial
  chunk are zero-padded to 1500 frames; padded outputs are ignored.

For the September 2026 CPU/NPU quantization experiment on both official checkpoints,
see [BEAT_QUANT_BENCHMARK.md](BEAT_QUANT_BENCHMARK.md) and the subsequent
[100-track accuracy evaluation](BEAT_MODEL_ACCURACY.md). The measurements below
are the earlier experiment and use a different model/build configuration.

A [direct checkpoint-to-LiteRT FP32 GPU export](BEAT_LITERT_GPU.md) is available
as an experimental candidate. It is not yet selected by the Android app.

## Quantization: why int8 and not fp16

fp16 was built and measured rather than assumed, because the usual reasoning —
ARMv8.2 has native fp16, so it should beat dynamically-quantized int8 on a phone
— turns out to be wrong for this stack.

Measured on a Snapdragon 7 Gen 1 (motorola razr 2023), 1500-frame chunk = 30 s
of audio, median of 7 runs after 2 warmups:

| model | threads | opt level | median | ×realtime | load |
|---|---|---|---|---|---|
| int8 | 4 | ALL | **2287 ms** | 0.076× | 1162 ms |
| int8 | 4 | BASIC *(control)* | 2706 ms | 0.090× | 1357 ms |
| fp16 | 4 | BASIC | 3951 ms | 0.132× | 3832 ms |
| int8 | 1 | ALL | 4553 ms | 0.152× | — |
| fp16 | 1 | BASIC | 8180 ms | 0.273× | — |

fp16 **cannot load at `ORT_ENABLE_ALL` on Android at all.** The graph contains
no `Gelu` node — it has 16 `Erf`s — but ORT's extended optimizations fuse those
into `com.microsoft.Gelu` at session creation, and while desktop ORT ships an
fp16 kernel for the fused op, `onnxruntime-android` does not. Loading fails with
`ORT_NOT_IMPLEMENTED`. fp16 therefore only runs with the fusions off.

That made the headline comparison unfair, so the control row holds int8 down to
`BASIC_OPT` too. Losing the fusions costs int8 18%. Like-for-like at
`BASIC_OPT`, fp16 is still **1.46× slower** than int8 — so it loses on merit,
not merely on the handicap. It is also 3× slower to load and 42 MB rather than
23 MB.

The conclusion generalizes: ORT's MLAS has well-optimized int8 paths on ARM and
thin fp16 coverage, so much of an "fp16" graph converts to fp32 to compute
anyway. Static QDQ quantization was not pursued either, for a simpler reason —
speed is not the constraint. At 13× realtime, a seam analysis costs ~2.3 s.

`tools/convert_beat_this_fp16.py` reproduces the fp16 model if this is ever
worth revisiting. It works around three separate converter defects: the
`Range` block-list gap, fp32 islands reaching `Einsum`, and stale `value_info`
at the output boundary. It verifies the result against fp32 on a synthetic
spectrogram shaped like program material, and reports peak agreement rather than
logit distance — fp16 reproduces every fp32 peak (64/64 beats, 16/16 downbeats,
none missed or spurious) despite a max logit difference of 1.9 on a range of 20.

## Measured accuracy on device

End-to-end through resampler → mel front end → model → peak picking, on
synthetic percussion (`BeatTrackerTest`):

- 120 BPM → 119.44, confidence 0.95
- 140 BPM → 140.22, confidence 0.95
- 57 beats across 28 s (~56 expected)
- 0 of 14 downbeat gaps off-bar

## Regenerating

The fp32 source is a build input and is not committed. The mobile asset was
converted from CPJKU's official `small0.ckpt`; the desktop model remains on its
separate `final0` build path. Orchard desktop's
`scripts/fetch-beat-this-model.mjs` downloads it, pinned to commit `07ab790a` of
`mosynthkey/beat_this_cpp` and verified against sha256
`c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd`. The int8
file is derived with ONNX Runtime's dynamic quantization:

```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic('beat_this.onnx', 'beat_this_int8.onnx', weight_type=QuantType.QInt8)
```
