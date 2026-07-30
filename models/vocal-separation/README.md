# Vocal-separation model (open-unmix, "umxhq" vocals target)

`vocals_umxhq_int8.onnx` is the committed, shipping model: the "vocals"
target of **open-unmix's umxhq** checkpoint (Stöter & Liutkus, Inria/SigSep),
converted to ONNX at a fixed frame count and dynamically quantized to int8.

Used to duck the *outgoing* track's vocal specifically through a WSOLA
transition, instead of the flat mid-band EQ kill that shipped before it — see
`electron/audio/vocalMaskTracker.js` and `mid_duck`/`vocal_duck_curve` in
`native/transition/transition_render.cpp`.

## Licensing

Both the open-unmix **code and the umxhq/umx pretrained weights are
MIT-licensed** — confirmed directly on the weights' own Zenodo deposit
page (<https://zenodo.org/record/3370489>, "License: MIT License"), not just
inferred from the code repository. This is the reason umxhq was picked over
Meta's htdemucs: htdemucs's code is MIT but its *pretrained weights* are
Meta's own CC-BY-NC-4.0 release — the same non-commercial trap Essentia's
models hit — and htdemucs's ONNX export additionally has real unresolved
blockers (complex-valued STFT ops, a custom multi-head-attention op) that
every serious attempt so far has had to hand-patch around. umxhq's simpler
BiLSTM-on-magnitude-spectrogram architecture exports cleanly with the
standard TorchScript-based exporter and needs no patching.

Only the **vocals** target is used. open-unmix trains vocals/drums/bass/other
as four independent checkpoints; Orchard only needs to know how much of the
outgoing track's *vocal* content is present at a given instant, not full
4-stem reconstruction, so the other three targets were never downloaded.

## Provenance

1. Downloaded directly from the weights' own Zenodo record:
   `https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth`, sha256
   `b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083`. That
   file (35.6 MB, fp32 PyTorch checkpoint) is a build input and is not
   committed.
2. Converted with `scripts/convert-umx-vocals-to-onnx.py` (fixed 960-frame
   input, opset 17, legacy TorchScript exporter — see that script for why).
   Verified against the original PyTorch checkpoint on the same input:
   max absolute difference 2.7e-6 on an output range of [0, 1.2] — the ONNX
   graph reproduces the checkpoint exactly, no export-induced drift.
3. Dynamically quantized to int8 with ONNX Runtime
   (`quantize_dynamic(..., weight_type=QuantType.QInt8)`; 9.05 MB, a quarter
   of the fp32 size). Measured against fp32 on a synthetic spectrogram shaped
   like real program material (spectral tilt, ramping harmonics, per-channel
   noise floor) rather than uniform noise: mean absolute difference in the
   vocal-band (200 Hz-4 kHz) duck curve derived from the mask was 0.018 on a
   0-1 scale, well under anything audible as a gain change. sha256
   `a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb`.

## Contract

- Input `mix_magnitude`: `[1, 2, 2049, 960]` float32 — linear-frequency STFT
  magnitude (not mel), n_fft 4096, hop 1024, Hann window, `center=True`,
  44,100 Hz audio, stereo. Produced by
  `native/analyzer/vocal_spectrogram.cpp`. The 2049 bins are the model's
  full output range; internally it only reads the first 1487 (16 kHz
  bandwidth) via a checkpoint-supplied `max_bin`, exactly as trained --
  everything above that passes through unmodeled, which is fine since
  vocals do not live there.
- Output `target_magnitude`: `[1, 2, 2049, 960]` -- the model's estimate of
  the vocal magnitude spectrogram, not a normalized mask. A per-bin mask is
  `target_magnitude / (mix_magnitude + eps)`, clamped to `[0, 1]`.
- Frame count is fixed at 960 (~22.8 s at this hop/rate), chosen to cover
  `MAX_OVERLAP_SECONDS` (16, in `wsolaPlanner.js`) plus slice padding and
  margin. Shorter input is zero-padded by the caller; there is no chunking
  logic here because a transition overlap never needs more than one window.
