# Beat This! beat/downbeat model

`beat_this_webgpu.onnx` is the desktop model, derived from the published **Beat This!**
weights (Foscarin, Schlüter & Widmer, ISMIR 2024 — the `final0` checkpoint)
converted to an fp32 ONNX graph. Supported desktop targets run it with ONNX
Runtime's WebGPU execution provider so analysis does not occupy the CPU cores
needed by playback and other foreground applications.

On a Radeon RX 5700, a 1500-frame (30-second) chunk measured 105 ms through
fp32/WebGPU and consumed 14 ms of process CPU time. The former int8/CPU path
measured 1536 ms and 3030 ms respectively. The fp32 WebGPU output matched the
fp32 CPU reference; an experimental fp16 conversion did not produce valid
logits through that WebGPU implementation and was rejected.

## Licensing

Both the Beat This! **code and trained weights are MIT-licensed**
(<https://github.com/CPJKU/beat_this>), which is the reason this model was
chosen: most published MIR model weights (including Essentia's) are
CC BY-NC-SA and cannot ship in a distributed application. The ONNX conversion
comes from the MIT-licensed C++ port
(<https://github.com/mosynthkey/beat_this_cpp>).

## Provenance

The fp32 source `beat_this.onnx` is not committed.
`scripts/prepare-adaptive-models.py` downloads it into `.cache/beat-this/` from
`mosynthkey/beat_this_cpp` (`onnx/beat_this.onnx`), pinned to commit `07ab790a`
and verified against sha256
`c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd`.
The file is 83,077,778 bytes.

## Contract

- Input `input_spectrogram`: `[1, frames, 128]` log-mel spectrogram,
  22,050 Hz audio, n_fft 1024, hop 441 (50 fps), Slaney mel 30–11,000 Hz,
  `log1p(1000·mag)` — produced by Earmark's Rust Beat This frontend.
- Outputs `beat`, `downbeat`: `[1, frames]` logits, peak-picked by
  `crates/orchard-adaptive-mix/src/beats.rs` (a port of V2's `beatThisTracker.js`).

The small checkpoints (`small0` etc.) were evaluated and rejected: through the
identical pipeline, `small0` mistook the metrical level or lost the grid
entirely on material the full model tracked at 2–10 ms. See the session notes
in the repo history before re-litigating.

## OrchardV3 GPU-only specialization

The desktop worker loads `beat_this_webgpu.onnx`, a fixed `[1, 1500, 128]`
FP32 specialization of the upstream `beat_this.onnx`. The original weights
are unchanged. `scripts/prepare-adaptive-models.py` reproduces it with ONNX
Simplifier: constant shape expressions are folded at build time so ONNX
Runtime does not need CPU shape kernels. All chunks are padded to 1500 frames;
only valid interior predictions are retained, with V2’s overlap policy.

The worker requires the WebGPU execution provider and sets
`session.disable_cpu_ep_fallback=1`. Unsupported graphs or missing hardware
make adaptive mix unavailable; they do not enable CPU model inference.
