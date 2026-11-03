# Syvenu

Syvenu (`syvenu.ort`) is Orchard's vocal presence model. It predicts UMX-HQ's vocal share
(the clamped vocals/mix magnitude ratio averaged over 200 Hz to 4 kHz) from the beat
spectrogram Nirovi already reads. Adaptive mix uses it for the planner's vocal curves and the
outgoing filter sweep. Desktop and Android ship this one file; the Android build copies it
into its assets.

## Model

- Conv2d stem (16/32/64 channels, frequency pooling 4/4/2), 1x1 projection to 96 channels,
  14 residual dilated Conv1d blocks (dilations 1 to 64, twice), 1x1 head, sigmoid.
- 569,761 parameters, FP32. Non-causal; each output frame sees about 5 s on each side.
- Trained 40k steps (batch 64 x 15.4 s, dropout 0.1, weight decay 0.05) with BCE against
  UMX-HQ vocal share curves, with random gain (+-12 dB) and spectral tilt (+-3 dB/octave).
- Training code is kept outside this repository (`train.py`, `export_onnx.py`, run `w96_reg`).

## Data

- Training: 89,070 tracks, three 20 s excerpts each: MTG-Jamendo (53,343), a Jamendo crawl
  (10,784) and FMA-medium (24,943). Labels are UMX-HQ (MIT) vocal share curves.
- Validation: 2,503 tracks from 5% of Jamendo artists, disjoint from training artists.
- Test: jam-alt (79 tracks; its artists are excluded from training) and MUSDB18-HQ (150 tracks).

The training audio is Creative Commons, mostly with NonCommercial terms, and some tracks are
NoDerivatives. `TRAINING_CREDITS.tsv.gz` credits every training track: artist, title and license
for FMA, and the jamendo.com track page (artist and license) for Jamendo tracks.

## Accuracy

Frame F1 against human labels at the best threshold (0.2 for the student):

| Set | Student | UMX-HQ |
|---|---|---|
| jam-alt lines (79) | 0.877 | 0.860 |
| MUSDB18-HQ test vocal stems (50) | 0.824 | 0.831 |
| MUSDB18-HQ train vocal stems (100) | 0.832 | 0.863 |

Against UMX-HQ's curves on whole test tracks: MAE 0.068 (jam-alt) to 0.079 (MUSDB train),
92 to 94% agreement at the 0.6 activity threshold. Orchard's 63-tap decimator moves the output
by 0.003 on average against the ffmpeg resampling used in training.

## Tensor contract

- Input `mel`: float32 `[1, T, 128]`, Earmark's beat spectrogram (22,050 Hz mono, FFT 1024,
  hop 441, reflect-centered, 128 Slaney mel bands 30 Hz to 11 kHz, `log1p(1000 * mel)`).
  Mono is `(L + R) / sqrt(2)` decimated by `downmix::beat_mono`.
- Output `presence`: float32 `[1, T]`, vocal share in 0..1 per frame (50 frames per second).

## Reproduce

1. `python export_onnx.py runs/w96_reg/best.pt` writes `best.onnx` (opset 17) and checks
   ONNX Runtime against PyTorch (max difference 4e-7).
2. `python -m onnxruntime.tools.convert_onnx_models_to_ort DIR --optimization_style Fixed --target_platform arm --enable_type_reduction`
   (onnxruntime 1.31.0) writes the ORT format file. Desktop matches the ONNX graph to 2e-7.
3. `python credits.py TRAINING_CREDITS.tsv.gz` writes the credits from the training cache index.

A 60 s window takes about 120 ms on two CPU threads, including the spectrogram.
