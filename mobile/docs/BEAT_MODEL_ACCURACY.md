# Beat This accuracy on GTZAN mini

> Historical experiment: production now uses stock ONNX Runtime CPU. QNN dependencies and NPU harness code have been removed; the NPU results below describe the earlier experimental build.

Measured September 9, 2026 using the same six-second `small0` and `final0` models as the [phone speed benchmark](BEAT_QUANT_BENCHMARK.md). The phone was a Motorola razr 2023 (SM7450). Phone inference was sequential; CPU variants were A8W8 and dynamic INT8 only. FP32 references ran on the host.

## Accuracy

F1 percentages, macro-average across all 100 annotated tracks (ten per genre, roughly 50 minutes total). Higher is better. These are label-based beat/downbeat scores, not agreement with another model.

| Model | Execution / precision | Context | Beat F1 | Downbeat F1 |
|---|---|---:|---:|---:|
| small0 | Host FP32 | 30 s | 86.71 | 70.37 |
| small0 | Host FP32 | 6 s | 81.61 | 58.75 |
| small0 | CPU dynamic INT8 | 6 s | 81.67 | 58.38 |
| small0 | CPU A8W8 QDQ | 6 s | 79.75 | 56.36 |
| small0 | NPU cached A8W8 | 6 s | 78.35 | 52.03 |
| final0 | Host FP32 | 30 s | 88.58 | 72.36 |
| final0 | Host FP32 | 6 s | 84.33 | 62.41 |
| final0 | CPU dynamic INT8 | 6 s | 84.28 | 62.24 |
| final0 | CPU A8W8 QDQ | 6 s | 84.29 | 62.12 |
| final0 | NPU cached A8W8 | 6 s | 83.13 | 56.74 |

## Thirty-second final0 NPU follow-up

The longer mode-1 preparation attempt succeeded after **529.7 seconds** of session creation, producing a cached 1500-frame A8W8 context. Cached inference median: **1,191.1 ms** per 30 seconds; cached load: **1,082.0 ms** in the timing run (776.5 ms in the accuracy run). CPU fallback remained disabled.

However, the original 30-second A8W8 artifact scored only **56.59% beat F1 / 32.73% downbeat F1** on the identical 100 tracks, versus **83.13% / 56.74%** for six-second NPU A8W8 and **88.58% / 72.36%** for 30-second host FP32. Do not use this 30-second NPU artifact as a production replacement.

All 200 input windows and all 100 output aggregations were checked against upstream `split_piece` / `aggregate_prediction` and matched exactly; every output was finite. The 30-second and six-second A8W8 graphs were separately calibrated, so this comparison does not isolate context length. The result establishes poor accuracy for this particular quantized NPU artifact, not that longer context inherently reduces accuracy. The earlier host numerical check already showed substantial drift for the full-length A8W8 graph. Further work must separate quantization/calibration error from provider arithmetic; this run did not score the 30-second CPU QDQ or dynamic-INT8 variants on these labels.

[Follow-up setup and raw artifacts](../../artifacts/beat-npu30/README.md), [accuracy summary](../../artifacts/beat-npu30/summary.json), [per-track scores](../../artifacts/beat-npu30/scores.json), and [cached context](../../artifacts/beat-npu30/final0_a8w8_cached.onnx). Matching QAIRT 2.45.0 desktop tools were downloaded and compiler startup verified during this experiment; the measured context was generated on the phone.

## Interpretation

- **small0:** NPU A8W8 is 3.32 beat-F1 points and 6.35 downbeat-F1 points below six-second CPU dynamic INT8. Shortening the FP32 context from 30 to 6 seconds loses 5.10 beat and 11.62 downbeat points.
- **final0:** NPU A8W8 is 1.15 beat-F1 points and 5.51 downbeat-F1 points below six-second CPU dynamic INT8. Shortening the FP32 context from 30 to 6 seconds loses 4.24 beat and 9.95 downbeat points.

The timing advantage of the cached NPU artifacts comes with an accuracy cost. CPU dynamic INT8 preserves the six-second FP32 baseline closely for both models. These results do not support treating the current six-second A8W8 contexts as accuracy-equivalent replacements for the longer-context model. No model was recalibrated or tuned on these evaluation tracks. Better calibration, quantization choices and longer NPU context remain separate experiments.

CPU and NPU runs of the same QDQ source are also not numerically equivalent. Reassembled logit MAE (beat / downbeat) between the phone providers:

- small0: 1.7522 / 1.6886.
- final0: 1.4777 / 1.7482.

This isolates observed provider differences but does not identify which QNN operation or optimization causes them.

## Method and limits

- Audio is the complete [ISMIR 2021 GTZAN mini collection](https://github.com/TempoBeatDownbeat/gtzan_mini), linked by [mirdata's GTZAN loader](https://github.com/mir-dataset-loaders/mirdata/blob/master/mirdata/datasets/gtzan_genre.py). Beat/downbeat labels are from [CPJKU/beat_this_annotations](https://github.com/CPJKU/beat_this_annotations); exact commit and audio/model hashes are recorded locally.
- Both checkpoint families exclude GTZAN from training, according to the [upstream model README](https://github.com/CPJKU/beat_this#available-models). This 100-track subset is not a full 1,000-track GTZAN evaluation, and results should not be generalized to all music.
- Upstream `LogMelSpect`, 22,050 Hz mono audio, 50 frames/s and 128 bands. Six-second input is fixed `[1,300,128]`. Six-frame borders, overlapping windows, keep-first aggregation match upstream inference; there are 600 windows per phone pass. The final window shifts to cover the track end.
- Same upstream minimal postprocessor for every variant: seven-frame local maxima, logit threshold above zero, adjacent-peak deduplication and downbeat snapping to the nearest beat. No threshold tuning or DBN. Score after the first five seconds, matching upstream evaluation, using `mir_eval` 0.8.2 F-measure with 70 ms one-to-one matching.
- Strict QNN HTP with CPU fallback disabled; cached models are the unchanged embedded contexts from the speed test. CPU sessions use four threads. Every output was finite and each phone result file had the expected 600 × 2 × 300 floats.
- Measures model/backend quality with upstream host feature extraction and postprocessing. It does not validate Android audio decoding, the production frontend, or its sub-frame peak interpolation. Accuracy inference times are not new speed benchmark results.

## Reproduction and raw results

Use [evaluate_beat_accuracy.py](../tools/evaluate_beat_accuracy.py) with `--stage prepare`, `host`, `phone`, then `score`, following its prerequisites and the local [experiment README](../../artifacts/beat-accuracy/README.md). The device harness is [BeatQuantBenchmark.kt](../android/app/src/androidTest/java/dev/sfg/orchard/mobile/playback/smart/BeatQuantBenchmark.kt), selected explicitly with `-e accuracy true`.

Local artifacts: [summary and precision/recall](../../artifacts/beat-accuracy/summary.json), [per-track scores](../../artifacts/beat-accuracy/scores.json), [ordered tracks and audio hashes](../../artifacts/beat-accuracy/manifest.json), and [model/source provenance](../../artifacts/beat-accuracy/provenance.json). Raw device logits, host logits and execution logs are retained in `artifacts/beat-accuracy/` (gitignored). Production model assets and playback code were not changed.
