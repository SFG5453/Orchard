# UMX-HQ vocals, FP32

`vocals_umxhq_fp32.onnx` is the original Open-Unmix UMX-HQ vocals checkpoint,
exported without quantization and specialized to 960 frames. Adaptive mix uses
it to measure vocal presence for Earmark’s outgoing filter sweep.

## Source and license

- Code: <https://github.com/sigsep/open-unmix-pytorch> (`openunmix==1.3.0`).
- Original FP32 weights: <https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth>.
- Checkpoint SHA-256: `b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083`.
- UMX-HQ code and weights are MIT licensed; the full code license is in `LICENSE`.
  This is the UMX-HQ vocals target, not the separately licensed UMXL checkpoint.

## Reproduce

Run `scripts/convert-umx-vocals-to-onnx.py CHECKPOINT OUTPUT.onnx` in an environment
with the versions documented in that script. It checks the source hash, loads
weights safely, exports the core spectrogram model, and folds constant shape
arithmetic. Python and PyTorch are build-time tools only.

V2 used a dynamically quantized INT8 export. This FP32 export keeps the original
three-layer bidirectional LSTM and its full context; no retraining or architecture change.

Playback runs it on ONNX Runtime's CPU provider with two non-spinning threads,
about 0.15 s per 960-frame slice. The LSTM steps serially, so WebGPU spends
13+ s on tiny dispatches and starves the compositor for the whole run.

## Validation

The FP32 WebGPU output was compared with the original PyTorch checkpoint on
stereo music from the supplied transition reference:

- Maximum magnitude error: `4.941225e-5` (output maximum `70.9773`).
- Relative RMS error: `7.899375e-7`.
- Vocal-band curve mean/max absolute error: `1.768956e-7` / `9.456151e-7`.

The CPU provider output passes the same check on real music: relative RMS error
`2.44e-7`, vocal-band curve max error `8.6e-7`.

The `model_probe` Rust example uses the same CPU session settings as playback.
`scripts/validate-vocal-model.py` reproduces parity checks on silence, a
spectral stress signal, and optional real audio against the original checkpoint.

## Tensor contract

- Input `mix_magnitude`: float32 `[1, 2, 2049, 960]`, magnitude STFT,
  44,100 Hz stereo, FFT 4096, hop 1024, periodic Hann, reflect-centered.
- Output `target_magnitude`: float32 with the same shape.
- Vocal presence: mean of clamped `target / mix` across channels and bins
  covering 200 Hz–4 kHz, excluding effectively silent input bins.
- Short windows are zero-padded. Search windows longer than 960 frames are
  processed in bounded chunks.
