# Adaptive mix

Local desktop smart-crossfade preparation, ported from OrchardV2’s Beat This
tracker and shared Earmark transition engine. The Qt host exposes it as the
**Adaptive mix** crossfade mode and keeps ordinary crossfade available.

The worker decodes through Orchard’s existing authenticated loopback stream
proxies: each whole song at 44.1 kHz for analysis, then the render windows
(the outgoing song’s last and the incoming song’s first 60 seconds) at 48 kHz,
the rate Qt plays at. FFmpeg decodes each window from the top of the stream
and discards up to its start, so the windows share the analysis timeline and
match playback sample for sample; an input seek lands tens of milliseconds off
on Opus/WebM. Stereo fold-down and anti-aliased 44.1 → 22.05 kHz conversion
run on the CPU. `ort` 2.0.0-rc.13 with the `webgpu`
feature runs the FP32 Beat This graph; the FP32 UMX-HQ vocal graph runs on
ORT's CPU provider with two non-spinning threads. The shared planner
selects beat/bar-aligned overlap positions and a pitch-preserving tempo glide
for pairs up to 12% apart: one shared tempo moves geometrically from the
outgoing BPM to the incoming BPM, so the outgoing song starts and the incoming
song ends at native speed. Earmark matches loudness, renders the selected
fade/filter/bass handoff, limits only the peaks that summing the two songs
adds, fades open filters back to the dry signal at both joins, and splices the
incoming side back onto unstretched source at a phase-aligned handoff. Each
join therefore meets native playback at unity gain with no filter phase. The
worker reports `incomingCue`, one overlap before that handoff, and the Qt host
starts the incoming player there so it reaches the handoff as the rendered PCM
runs out. The host matches both joins on the audio itself, since decoder
timestamps disagree by milliseconds (Qt stamps Opus played from the start 7 ms
early), and fades the render into native playback over its last 5 ms.

Before planning, the worker measures each song's bass (35-140 Hz, every 0.25 s,
relative to the song's usual level), its sub-bass weight (25-60 Hz against
60-250 Hz over the body), and UMX vocal activity across both render windows
(about 1 s of CPU per pair). The planner uses them to keep a beat going through
the overlap, drop a beatless intro's first beat on the bass swap, avoid fading in
on a vocal line, and keep the natural boundary between clashing productions,
which the worker reports as the planner's natural-boundary refusal. The outgoing
vocal curve also shapes the filter sweep. Set `ORCHARD_MIX_DUMP` to a directory
to save each pair's planner input and decisions for offline replay. Set
`ORCHARD_MIX_BEATS=earmark` to plan from Earmark's own beat grid without the Beat
This pass, for A/B listening.

When the outgoing beat stops before the song does, the planner may loop its last
clean bars (`outgoingLoop`); outgoing times past the loop end are loop time. The
worker rebuilds the outgoing window with those bars repeating, crossfading each
seam 8 ms over a point 20 ms before the downbeat, and reports `loopBeats`. After
planning, the worker locks the kicks: it folds both songs' 35-140 Hz onsets over
one shared beat phase across the overlap and its context, and moves the incoming
cue by up to an eighth of a beat when the two patterns clearly match at an
offset. It reports the move in milliseconds as `kickLock`.

## Best Mix queue order

The queue's **Best Mix** action sorts up to 50 upcoming songs with the beta
branch's directional tempo/key/energy prefilter and three shared-planner
finalists per choice. Each song also carries its whole-song sub-bass weight
(`subBassRatio`): the prefilter ranks a pair the planner would refuse as a style
clash behind every pair that can blend, and the finalists' plans apply the same
style gate as playback. Finalists are planned the way playback plans them: with
the tempo glide on and each grid treated as Beat This-confirmed (confidence
0.95). Earmark's own calibrated confidence clears the planner's 0.55 beatmatch
floor for about 80-90% of songs on labelled sets; the assumption keeps the rest
from reading as plain crossfades when playback would confirm them. Three
background download workers resolve authenticated
**saver** audio, fetch each complete encoded song in 1 MiB ranges, and cache
the media (up to 128 MiB). Measured features live as CBOR blobs in a bounded
SQLite cache with a 32 MiB feature payload limit, versioned by
`PRAGMA user_version`; a version bump re-analyzes songs from their cached audio. Qt SQL loads full analyses
only for shortlisted pairs. FFmpeg decodes the first and last 60 seconds of
each song for Earmark analysis. Missing analyses keep their position. Playback
and Adaptive Mix use the selected playback quality; the saver file is never
rendered to the listener. Queue edits during analysis cancel the sort. The
original order can be restored while the sorted tracks remain in sequence.

`ort`’s upstream WebGPU provider uses **Dawn**, the only GPU runtime the
worker loads.

The Beat This session requires WebGPU registration and sets
`session.disable_cpu_ep_fallback=1`; it has no CPU fallback. The UMX-HQ LSTM
steps serially, so on WebGPU it issues thousands of tiny dispatches and starves
the compositor; its CPU session finishes a 960-frame slice in about 0.15 s.
There is no Supabase/cloud analysis. Resampling, decoding, spectrogram
construction, planning, and audio DSP are ordinary CPU work. Unavailable beat
inference leaves the natural track boundary intact. An unavailable optional
vocal model leaves the frame-based vocal estimate in charge of planning and the
planned filter sweep unchanged.
Settings show preparation/availability; worker crashes cannot stop playback.

## Docs search embeddings

The docs window's question search (`models/docs-search`) uses this worker as a
text embedder. The Qt host starts a separate instance for it, so a Best Mix run
never delays a docs question. A request is one line,
`{"kind":"embed","ids":[[101, ...], ...]}`, holding token ids from the host's
WordPiece tokenizer. The reply is `{"vectors":[[...], ...]}`: one unit-length,
384-dimension vector per sequence. The CPU session loads on the first request,
runs one sequence per call on one non-spinning thread, and holds about 50 MB
while the worker lives; the host closes an idle worker after a minute. The model,
tokenizer contract and ranking are described in `models/docs-search/README.md`.

## Build and runtime

The main CMake build compiles and stages the worker, ORT's Dawn runtime,
selected models, and model licenses beside `orchard` (plus a checksum-verified
FFmpeg on Windows). Runtime decoding needs **FFmpeg**
on PATH (or `ORCHARD_FFMPEG` set to its executable). GPU validation and the
complete native build were exercised on Linux; other desktop targets were
not runtime-tested.

Overrides: `ORCHARD_MODELS_DIR` (Qt model directory) and `ORCHARD_FFMPEG`
(decoder path). No model downloads
occur during playback. GPU sessions are retained in the isolated worker.

## Validation

- `cargo test -p orchard-adaptive-mix`
- `cargo test -p orchard-adaptive-mix --test docs_embed` (CPU only)
- `cargo clippy -p orchard-adaptive-mix --all-targets -- -D warnings`
- Hardware test: `cargo test -p orchard-adaptive-mix --test models -- --ignored`.
- `scripts/validate-vocal-model.py` compares vocal inference with the original
  PyTorch checkpoint.
- `ctest --test-dir build -R 'orchard_audio_proxy|orchard_playback_persistence|orchard_adaptive_mix_span' --output-on-failure`
- `ctest --test-dir build -R orchard_docs_search --output-on-failure` runs the docs search tests against the staged worker.

The FP16 vocal export failed the strict GPU-only session check, so only FP32
is packaged. Source hashes, licenses, tensor contracts, and measured numerical
errors live with the models under `models/`.
