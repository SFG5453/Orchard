# Nirovi beat/downbeat model

Nirovi is Orchard's beat and downbeat tracker: a dilated TCN (width 384, seven 128-wide
global rhythm layers) trained with 8-bit quantization-aware training and distilled from
Beat This! `final0` (Foscarin, Schlüter & Widmer, ISMIR 2024).

| File | Runtime | Size | SHA-256 |
|---|---|---:|---|
| `nirovi_fp16.onnx` | ORT WebGPU (desktop worker) | 15,252,717 | `f7491e4598d6ac3d65c8aa4af4621fe80378aba12bafab38ebc2af27853af202` |
| `nirovi_int8.onnx` | CPU: desktop fallback, Apple Music page (onnxruntime-web) | 7,802,064 | `47d2fc9901cf44443a64f0b15997bf1d678d9047ea88d6e0795c625d34ac45ce` |

`nirovi_int8.onnx` packs its weights as int8 (weight-only) and computes in fp32. The fp16
graph is for GPUs; CPU runtimes load the int8 graph.

## Source

Both exports come from checkpoint `scratch16_tcn384_gl7_qat8_win` epoch 14 (SHA-256
`27889e7b35df336aae6521c9a074592fe9f7f4185b8d02491adeb30442d105cb`). The int8 export's
probabilities match the checkpoint within `5e-8` mean absolute error, with identical
beat and downbeat events at 25 ms.

## Training data

- Log-mel spectrograms from Schlüter and Foscarin, *Beat This! Spectrograms for Beat and
  Downbeat Tracking*, Zenodo [10.5281/zenodo.13922116](https://zenodo.org/records/13922116),
  CC BY 4.0.
- Beat and downbeat annotations from
  [`CPJKU/beat_this_annotations`](https://github.com/CPJKU/beat_this_annotations) v1.0,
  MIT License.
- Teacher logits from Beat This! `final0`, whose code and weights are MIT-licensed
  (<https://github.com/CPJKU/beat_this>).

GTZAN was held out as test data. No spectrograms, annotations or audio ship with Orchard.

## Contract

The interface is Beat This's, so the frontend and peak picking are shared.

- Input `input_spectrogram`: `[1, 1500, 128]` log-mel spectrogram, 22,050 Hz audio,
  n_fft 1024, hop 441 (50 fps), Slaney mel 30–11,000 Hz, `log1p(1000·mag)`, produced by
  Earmark's frontend.
- Outputs `beat`, `downbeat`: `[1, 1500]` logits, peak-picked by
  `crates/orchard-adaptive-mix/src/beats.rs`.

## Runtime

The desktop worker loads `nirovi_fp16.onnx` on ONNX Runtime's WebGPU provider with
`session.disable_cpu_ep_fallback=1`. Without a usable WebGPU device it loads
`nirovi_int8.onnx` on two non-spinning CPU threads.

Measured on 14 song windows (Radeon RX 9060 XT, Ryzen CPU): fp16/WebGPU 42 ms and
int8/CPU 166 ms per 1500-frame window, against 123 ms for Beat This fp32/WebGPU. The two
Nirovi graphs agreed on beats (F 0.94–1.00 at 70 ms). In the Apple Music page's
single-threaded onnxruntime-web, `nirovi_int8.onnx` takes about 670 ms per window and
adds about 160 MB while loaded.
