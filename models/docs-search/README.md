# Docs search model

`model_quantized.onnx` is **bge-micro-v2** (TaylorAI), a 3-layer, 384-dimension
sentence embedding model distilled from `BAAI/bge-small-en-v1.5`, in the
author's dynamically quantized (int8) ONNX export. The docs window uses it to
match a plain-language question such as "how do I open the queue" to the docs
sections that answer it. It runs on the CPU, inside an `orchard-adaptive-mix`
worker started for the purpose, and never needs a GPU.

## Licensing

MIT, copyright 2023 Benjamin Anderson (`LICENSE`). The teacher model
`BAAI/bge-small-en-v1.5` is MIT licensed as well.

## Provenance

Both files are committed unchanged from
<https://huggingface.co/TaylorAI/bge-micro-v2> (repository commit
`3edf6d7de0faa426b09780416fe61009f26ae589`):

| File | Source path | Bytes | SHA-256 |
| --- | --- | --- | --- |
| `model_quantized.onnx` | `onnx/model_quantized.onnx` | 17,409,774 | `ed65e36025aa94cb74207dab863c85452919ec0ab7df3512092932aa22c9a33a` |
| `vocab.txt` | `vocab.txt` | 231,508 | `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3` |

`vocab.txt` is the `bert-base-uncased` vocabulary. It is staged beside the ONNX
model under `models/docs-search/`, rather than embedded in the app.

## Contract

- Inputs `input_ids`, `attention_mask`, `token_type_ids`: int64 `[1, tokens]`.
  Output `last_hidden_state`: `[1, tokens, 384]`.
- Tokens come from `app/src/docs/bert_tokenizer.cpp`: BERT uncased WordPiece
  (controls dropped, CJK spaced out, accents stripped, lowercase), `[CLS]` ...
  `[SEP]`, at most 256 tokens. `tests/data/bert_tokenizer_golden.json` holds
  Hugging Face `tokenizers` output for 32 texts that the C++ tokenizer must
  reproduce exactly.
- `crates/orchard-adaptive-mix/src/text_embed.rs` mean-pools the token
  vectors and scales the result to unit length. The worker takes
  `{"kind":"embed","ids":[[...], ...]}` and answers `{"vectors":[[...], ...]}`.
- The question needs no instruction prefix. In a prototype, bge's "Represent this
  sentence for searching relevant passages:" lowered top-3 accuracy on the docs
  from 94% to 82%.
- The worker runs one sequence per call on one non-spinning thread. Dynamic
  quantization scales activations per tensor, so batching moves a vector by up
  to 0.4% cosine similarity. The whole docs index takes under a second.

## Ranking

`app/src/docs/docs_index.cpp` splits the docs into one chunk per `##` section
plus one per page (title, summary and keywords), embeds each chunk's text and
its title, and scores a question as `0.7 * body + 0.3 * title` cosine, plus
`0.1` times the share of the question's content words found in the chunk. A
page-level chunk loses `0.06`, so a section that says the same thing ranks
first. Scores below `0.62`, or more than `0.08` under the best one, are
dropped. Adjacent questions about things the docs do not cover can still
clear the floor; the model has no notion of "not documented".

## Measured on the docs

`tests/data/docs_questions.json` holds 63 hand-written questions over
`docs/app` (142 sections) and 12 unrelated ones. `rankingQualityOnTheDocs` in
`tests/docs_search_test.cpp` runs the shipped worker and prints the result: the
right section ranks first for 56 questions (89%) and in the top 3 for 60 (95%),
and all 12 unrelated questions get no answer. The misses are leaps of meaning
("make the bass louder" for the equalizer).

A Python prototype with the same scoring compared the alternatives: the
unquantized `onnx/model.onnx` (69 MB) scored 89% / 95% and `all-MiniLM-L6-v2`
(`model_O4.onnx`, fp16, 45 MB) 86% / 97%, so neither earns its size here.
