# Nirovi beat/downbeat model

Nirovi is Orchard's beat and downbeat tracker: a dilated TCN (width 384, seven 128-wide
global rhythm layers) trained with 8-bit quantization-aware training and distilled from
Beat This! `final0` (Foscarin, Schlüter & Widmer, ISMIR 2024).

| File | Runtime | Size | SHA-256 |
|---|---|---:|---|
| `nirovi_fp16.onnx` | ORT WebGPU (desktop worker) | 15,252,717 | `f7491e4598d6ac3d65c8aa4af4621fe80378aba12bafab38ebc2af27853af202` |
| `nirovi_int8.ort` | CPU: desktop fallback, Android | 7,948,784 | `7454d98ab6a88c0b7fe5e91c556b4766ebfabfe3d31ebd61187a033fed489387` |

`nirovi_int8.ort` is a static QDQ graph: per-channel int8 weights and uint8 activations, except
the normalized input (`/Div_output_0`) and the stem output (`/stem/stem.1/Relu_output_0`), which
stay uint16. Quantizing the input spectrogram to 8 bits causes nearly all of the full int8 loss.
The fp16 graph is for GPUs; CPU runtimes load the int8 graph. It is in ONNX Runtime's ORT format
so Android's minimal runtime can load it.

It was made from the fp32-weight export of the checkpoint (SHA-256
`ad62f3339ae32b3cd9ff31fadad2591a52c560b69d16e4a199479f0018c1c2f7`) with onnxruntime 1.31.0's
`get_qnn_qdq_config` (MinMax over 203 random non-GTZAN 1500-frame windows,
`CalibMaxIntermediateOutputs` 8, `add_qtype_converts`), then converted with
`python -m onnxruntime.tools.convert_onnx_models_to_ort DIR --optimization_style Fixed
--target_platform arm --enable_type_reduction`.

## Accuracy

- 40 GTZAN windows on an AVX2 CPU without VNNI: logits differ from the fp32 graph by 0.11 on
  average and keep the same sign on 99.7% (beat) and 99.9% (downbeat) of frames.
- 1,000 GTZAN tracks: 0.8787 beat / 0.7494 downbeat F at 70 ms, against 0.8799 / 0.7514 for an
  int8 weight-only graph of the same checkpoint.
- 19 windows of 10 songs through the desktop worker's CPU session: the same tempo and downbeats
  as the weight-only graph on 18, beats 0.5 ms apart at the median and 3.8 ms at p95.

## Source

Both exports come from checkpoint `scratch16_tcn384_gl7_qat8_win` epoch 14 (SHA-256
`27889e7b35df336aae6521c9a074592fe9f7f4185b8d02491adeb30442d105cb`).

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
`nirovi_int8.ort` on two non-spinning CPU threads. Android loads the same file on two
threads: a 1500-frame window takes about 1.3 s on a Cortex-A53 (2.4 s weight-only) and
262 ms on a Snapdragon 7 Gen 1 (1,121 ms weight-only).

Measured on 14 song windows (Radeon RX 9060 XT): fp16/WebGPU 42 ms per 1500-frame window,
against 123 ms for Beat This fp32/WebGPU.
