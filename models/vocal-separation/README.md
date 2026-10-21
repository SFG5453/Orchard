# UMX-HQ vocals, presence cut, INT8

`vocals_umxhq_presence_int8.ort` is the Open-Unmix UMX-HQ vocals checkpoint,
specialized to 960 frames, cut to the bins vocal presence reads, and dynamically
quantized to INT8, in ONNX Runtime's ORT format. Adaptive mix uses it to measure vocal presence for Earmark’s
outgoing filter sweep. Desktop and Android ship this one file; the Android build
copies it into its assets.

## Source and license

- Code: <https://github.com/sigsep/open-unmix-pytorch> (`openunmix==1.3.0`).
- Original FP32 weights: <https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth>.
- Checkpoint SHA-256: `b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083`.
- UMX-HQ code and weights are MIT licensed; the full code license is in `LICENSE`.
  This is the UMX-HQ vocals target, not the separately licensed UMXL checkpoint.

## Reproduce

1. `scripts/convert-umx-vocals-to-onnx.py CHECKPOINT vocals_umxhq_fp32.onnx` exports
   the full FP32 model (SHA-256 `1ff89359f6d93507fd05d95159efd56d05b8e566bcef3c3ff11fb5893ff6503d`).
2. `scripts/prune-umx-vocals.py vocals_umxhq_fp32.onnx pruned_fp32.onnx --int8 DIR/vocals_umxhq_presence_int8.onnx`
   keeps the input bins the model reads and the output bins presence averages, then
   quantizes: per-channel dynamic INT8 LSTMs, weight-only INT8 fully connected layers.
3. `python -m onnxruntime.tools.convert_onnx_models_to_ort DIR --optimization_style Fixed --target_platform arm`
   (onnxruntime 1.31.0) writes the ORT format file, which Android's minimal runtime needs.
   Desktop's ONNX Runtime 1.28 gives bit-identical presence curves from it.

Python, PyTorch and ONNX are build-time tools only. The architecture is unchanged:
three-layer bidirectional LSTM with its full 960-frame context, no retraining.
Pruning leaves the kept bins bit-for-bit identical to the FP32 model. Against it, the INT8
presence curve differs by 0.0044 on average (p99 0.017) on 24 songs, and 0.9% of frames
cross the 0.6 activity threshold. The file is 7.7 MB (SHA-256
`ab3f92846a88ce07b5135a5129e11cfa2a72cc26549f3a2366e198c28a9963fc`), against 35.6 MB
for the full FP32 export; a 960-frame slice takes about 110 ms on two CPU threads.

Playback runs it on ONNX Runtime's CPU provider with two non-spinning threads. The
LSTM steps serially, so WebGPU spends 13+ s on tiny dispatches and starves the
compositor for the whole run. Its operators are in Android's reduced ONNX Runtime
build (`mobile/android/app/native/onnxruntime/ops.config`).

## Tensor contract

- Input `mix_magnitude`: float32 `[1, 2, 1487, 960]`, the first 1487 bins of a
  magnitude STFT: 44,100 Hz stereo, FFT 4096, hop 1024, periodic Hann, reflect-centered.
- Output `target_magnitude`: float32 `[1, 2, 355, 960]`, STFT bins 18..=372
  (200 Hz to 4 kHz).
- Vocal presence: mean of clamped `target / mix` across channels and those bins,
  excluding effectively silent input bins.
- Short windows are zero-padded. Search windows longer than 960 frames are
  processed in bounded chunks.
