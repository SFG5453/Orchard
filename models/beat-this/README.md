# Beat This! beat/downbeat model

`beat_this_int8.onnx` is the committed, shipping model: the published
**Beat This!** weights (Foscarin, Schlüter & Widmer, ISMIR 2024 — the
`final0` checkpoint) converted to ONNX and dynamically quantized to int8.

Measured against the fp32 original on Orchard's synthetic harness the int8
predictions are identical to within peak-picking noise (beat counts exact,
median beat error 2.7–10 ms), at roughly half the inference time and 23 MB
instead of 83.

## Licensing

Both the Beat This! **code and trained weights are MIT-licensed**
(<https://github.com/CPJKU/beat_this>), which is the reason this model was
chosen: most published MIR model weights (including Essentia's) are
CC BY-NC-SA and cannot ship in a distributed application. The ONNX conversion
comes from the MIT-licensed C++ port
(<https://github.com/mosynthkey/beat_this_cpp>).

## Provenance

1. `scripts/fetch-beat-this-model.mjs` downloads the fp32 ONNX conversion,
   pinned to commit `07ab790a` of `mosynthkey/beat_this_cpp` and verified
   against sha256
   `c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd`.
   That file (`beat_this.onnx`, 83 MB) is a build input and is gitignored.
2. The committed int8 file was derived from it with ONNX Runtime's dynamic
   quantization:

   ```python
   from onnxruntime.quantization import quantize_dynamic, QuantType
   quantize_dynamic('beat_this.onnx', 'beat_this_int8.onnx', weight_type=QuantType.QInt8)
   ```

   sha256 `fffa976489337c7b7fb01db01fb0513eb7fae2960f451c814b6127b43f969a58`.

## Contract

- Input `input_spectrogram`: `[1, frames, 128]` log-mel spectrogram,
  22,050 Hz audio, n_fft 1024, hop 441 (50 fps), Slaney mel 30–11,000 Hz,
  `log1p(1000·mag)` — produced by Earmark's Rust Beat This frontend.
- Outputs `beat`, `downbeat`: `[1, frames]` logits, peak-picked by
  `electron/audio/beatThisTracker.js`.

The small checkpoints (`small0` etc.) were evaluated and rejected: through the
identical pipeline, `small0` mistook the metrical level or lost the grid
entirely on material the full model tracked at 2–10 ms. See the session notes
in the repo history before re-litigating.
