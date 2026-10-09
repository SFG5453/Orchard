# Lyric translation packs

`convert_marian.py` exports a Hugging Face Marian model to int8 LiteRT with the same
`encode` / `prefill` / `decode` signatures as Helsinki-NLP's `opus-mt_tiny_*` releases, so one
runner (`core/native/translation/marian_model.cpp`) handles every pack.

```sh
uv venv conv && uv pip install --python conv/bin/python litert-torch
conv/bin/python convert_marian.py Mitsua/elan-mt-tiny-ja-en ja/model.tflite 256 dyn
```

A pack folder holds `model.tflite`, `source.spm` and `vocab.json` from the source repo.
`check_pack.py <pack-dir> <lines.txt>` decodes with the Python LiteRT runtime; its output should
match `MarianMTModel.generate(num_beams=1)`.

Gotchas the runner depends on:

- `pad_mask` is additive: 0 for tokens, -1e9 for padding. A 1/0 mask is silently ignored.
- Every sample cache tensor must be a separate object; shared ones export as a single graph input.
- Cross-attention caches pass through `decode` unchanged, so they are one tensor in and out.
