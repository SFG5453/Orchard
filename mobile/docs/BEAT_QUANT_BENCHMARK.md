# Beat This quantization on Motorola razr 2023

> Historical experiment: production now uses stock ONNX Runtime CPU. QNN dependencies and NPU harness code have been removed; the NPU results below describe the earlier experimental build.

Measured September 9, 2026 on SM7450. Both official checkpoints were exported through the same pipeline. Exactly one Beat This model ran on the phone at a time. Phone CPU benchmarks were restricted to INT8 and lower weight precision.

## CPU: 30-second chunks

Median inference time in milliseconds, fixed 1500 frames, four threads.

| Model | Dynamic INT8 | INT4 weights, block 32 | Static A8W8 QDQ |
|---|---:|---:|---:|
| small0 | 2307.6 | 2260.6 | 2400.5 |
| final0 | 3380.9 | 4009.1 | 3721.3 |

For small0, the tested INT4 variant was only about 2% faster than dynamic INT8. For final0, it was about 19% slower. INT4 is weight-only MatMul/Gemm quantization; other operations retain supported precisions. It is not a fully integer four-bit model.

## CPU versus NPU: identical six-second A8W8 graphs

Both providers received the same fixed 300-frame QDQ model and deterministic input. NPU timings use a precompiled embedded QNN context. All measurements in this table began and ended at Android thermal status 0.

| Model | CPU A8W8, ms | NPU A8W8, ms | CPU/NPU ratio | Cached NPU load, ms |
|---|---:|---:|---:|---:|
| small0 | 210.3 | 68.6 | 3.06× | 248.5 |
| final0 | 316.0 | 79.3 | 3.99× | 377.4 |

For reference, six-second dynamic-INT8 CPU medians were small0: 223.5 ms, final0: 315.9 ms. These replace the earlier small0 short-window measurement that started hot.

Creating the embedded contexts took small0: 60.4 s, final0: 62.8 s. This preparation cost is separate from cached loading and inference. Without serialization, the first direct QDQ runs spent roughly 51 seconds creating a session.

Do not compare the six-second NPU times directly with the 30-second CPU table. Shortening the window changes model context. The subsequent [100-track accuracy evaluation](BEAT_MODEL_ACCURACY.md) measures the context and quantization losses.

## Thirty-second final0 NPU follow-up

A longer retry succeeded: the original 1500-frame `final0` A8W8 graph created its session in **529.7 seconds** in mode 1. The previous 300-second cutoff was too short. The cached context contains one EPContext node with `[1,1500,128]` input and occupies 54,571,850 bytes.

Reloading the context took **1,082.0 ms**. Median inference was **1,191.1 ms** over seven runs after two warmups (range 1,187.1–1,253.4 ms), with thermal status 0 at both ends. This is roughly 25× audio real time. The historical same-window CPU A8W8 median was 3,721.3 ms, about 3.12× slower, but those CPU measurements had different thermal conditions and are not a fresh controlled speedup comparison.

Accuracy on the same 100-track GTZAN mini set was only **56.59% beat / 32.73% downbeat F1**. This specific 30-second A8W8 artifact is not suitable for production; see the [accuracy follow-up](BEAT_MODEL_ACCURACY.md#thirty-second-final0-npu-follow-up).

[Raw follow-up artifacts and matching desktop SDK setup](../../artifacts/beat-npu30/README.md). This context was compiled on the phone. Matching QAIRT 2.45.0 host tools were downloaded and their native context compiler startup verified; a complete host conversion workflow has not yet been validated.

## NPU compatibility and preparation limits

| Precision | Six-second graph | Thirty-second graph |
|---|---|---|
| A8W8 | Both checkpoints compile in fast mode and run from cached contexts | Default mode crashed; mode 1 initially exceeded 300 seconds for both. A longer final0 retry succeeded in 529.7 seconds; small0 was not retried. |
| A16W8 | Fast-mode preparation exceeded a 120-second cutoff for both | Default mode crashed during native preparation; full-size fast mode was not retested |
| A16W16 | QNN rejected BatchNormalization during layout/provider selection | Same BatchNormalization failure |

A timeout is a censored preparation result, not proof that a graph is unsupported or could never finish. There are no accepted inference timings for these timed-out or rejected variants. Default-mode crash stacks include allocation/exception frames in libQnnHtpPrepare.so; the observed phone process reached roughly 3 GB during one failed compile.

The initial export contained zero-length rotary slices feeding Concat. Removing empty inputs and pruning dead nodes fixed QNN StridedSlice validation failures. Source-versus-cleaned synthetic checks differed by less than 0.000002 logits. All final QDQ variants were prepared after this cleanup.

## Numerical quality

Calibration used three excerpts of the upstream bundled music fixture, beginning at 0, 30 and 60 seconds. The 1500-frame graphs used 30-second excerpts; the 300-frame graphs used six-second excerpts. Host-only numerical checks compared the 1500-frame quantized graphs with their original FP32 source on the held-out 90–120-second excerpt.

| Model | INT8 beat-logit MAE | INT4 beat-logit MAE | A8W8 beat-logit MAE | A16W8 beat-logit MAE |
|---|---:|---:|---:|---:|
| small0 | 0.3982 | 0.7840 | 4.1318 | 0.0797 |
| final0 | 0.2633 | 0.4850 | 2.3189 | 0.4683 |

Static A8W8 showed substantial output drift with this small calibration set. These are logit errors, not labeled beat/downbeat accuracy scores. During this timing experiment, device outputs were checked for finiteness. The subsequent [GTZAN mini accuracy evaluation](BEAT_MODEL_ACCURACY.md) scores both six-second checkpoints on the phone against beat/downbeat annotations. The working cached NPU graphs are performance/compatibility artifacts, not validated replacements for the shipping model. Full per-head errors are in [ACCURACY.md](../../artifacts/beat-quant/ACCURACY.md).

## Measurement setup

- Two warmups, then seven timed inferences; tensor creation, file copying, output checking and mel extraction are excluded. Session creation is reported separately.
- CPU: four threads, ALL optimization, CPU arena and memory-pattern optimization disabled, matching production allocator settings.
- NPU: libQnnHtp.so, sustained_high_performance, and session.disable_cpu_ep_fallback=1. Successful cached models contain a single com.microsoft EPContext node.
- Default finalization mode 0 was tested first; mode 1 was used for fast preparation and context generation. Contexts are embedded in single ONNX files and were reloaded on this phone before timing.
- Runtime: app native ONNX Runtime QNN 1.27.1, Java bindings 1.27.0, QNN 2.45.0. The newer desktop compiler wheel inspected during troubleshooting was not used to generate device contexts or alter the app runtime.
- Cooldown gates were added after initial exploratory runs. Several 30-second CPU runs began at thermal status 0 and ended at 3; these are practical sustained phone measurements, not a claim of unthrottled peak speed. The final six-second comparison and cached loads stayed at status 0.
- The app may perform its tiny startup NPU capability probe; no two Beat This variants ran concurrently. Host preparation and numerical checks could run alongside phone work.

## Sources, tools and artifacts

Sources were followed from [BEAT_MODEL.md](BEAT_MODEL.md) and [the desktop model README](../../models/beat-this/README.md) to [CPJKU/beat_this](https://github.com/CPJKU/beat_this). Both checkpoints came from the official cloud source:

- [small0.ckpt](https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/small0.ckpt)
- [final0.ckpt](https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt)

Preparation code: [prepare_beat_quant.py](../tools/prepare_beat_quant.py). Device harness: [BeatQuantBenchmark.kt](../android/app/src/androidTest/java/dev/sfg/orchard/mobile/playback/smart/BeatQuantBenchmark.kt). It requires explicit variant/backend arguments, rejects CPU precision labels above INT8, and skips ordinary test-suite runs without selectors.

Working precompiled six-second A8W8 contexts, tested on this phone/runtime:

- [small0 QNN context](../../artifacts/beat-quant/small0f300_a8w8_cached.onnx)
- [final0 QNN context](../../artifacts/beat-quant/final0f300_a8w8_cached.onnx)

The local [experiment directory](../../artifacts/beat-quant/README.md) contains source exports, QDQ variants, commands, raw outcomes, native crash logs, calibration input and numerical checks. provenance.json records source/model hashes and upstream commit; host-versions.json records preparation dependencies; [RESULTS.md](../../artifacts/beat-quant/RESULTS.md) gives per-run ranges and thermal states. Production playback code and shipped model assets were not changed.
