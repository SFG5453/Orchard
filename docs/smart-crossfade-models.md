# Smart Crossfade ONNX models

Orchard release builds include the Smart Crossfade ONNX model suite as an
application resource. Users enable inference with **Settings → Playback → AI
Smart Crossfade analysis**; there is no separate model installation flow.
Playback never depends on inference succeeding: a failed model run leaves the
native/worker analysis and deterministic transition planner in control.

## Bundled pipeline

The shipped pipeline is:

1. HTDemucs separates decoded stereo PCM into drums, bass, other, and vocals.
2. All-In-One analyzes the four stem spectrograms for functional sections.
3. Orchard merges trusted intro/outro/section and vocal-activity cues into its
   native tempo, key, loudness, and energy analysis.
4. Orchard's transition planner scores the outgoing and incoming All-In-One
   functional sections, checks localized vocal overlap from HTDemucs, and
   schedules the final beat-aligned mix.

All-In-One already expects HTDemucs stems, so these are two stages of one
pipeline rather than independent models.

The build-stage installer downloads the pinned assets:

```bash
npm run models:smart-crossfade
```

This downloads about 170 MB to `models/smart-crossfade/` and verifies every
asset with its pinned byte size and SHA-256 digest. The directory is ignored by
Git. The `predev`, `prestart`, and packaging lifecycle scripts run this step
automatically. Electron Builder copies the verified directory to
`resources/smart-crossfade-models`; a release build without that directory
fails packaging.

Runtime lookup order is:

1. `ORCHARD_SMART_CROSSFADE_MODELS`, for development and diagnostics.
2. `<userData>/smart-crossfade-models`, for testing a replacement pack.
3. `models/smart-crossfade` in a development checkout.
4. `resources/smart-crossfade-models` in a packaged build.

The first two paths are developer overrides; they are not part of the
user-facing feature.

Inference is offline, uses ONNX Runtime's CPU execution provider, and is
serialized to one track at a time. HTDemucs typically needs roughly 1–1.5 GB
of working memory in addition to decoded audio and spectrogram buffers.

## Licensing

All-In-One and Demucs are MIT licensed. The installer includes their license
texts, source URLs, pinned revisions, and license identifiers in the staged
pack. MERT is intentionally not included, avoiding its CC BY-NC restriction.

## Model-pack contract

`manifest.json` currently supports schema version 1 and the
`all-in-one-htdemucs` pipeline. Paths must remain inside the pack directory.
Required files and tensor contracts are:

- HTDemucs fp16-weights input `mix`: float32 `[1, 2, 343980]` at
  44.1 kHz.
- HTDemucs output `stems`: float32 `[1, 4, 2, 343980]`, ordered
  drums/bass/other/vocals.
- All-In-One input `spec`: float32 `[1, 4, frames, 81]`, ordered
  bass/drums/other/vocals.
- All-In-One outputs: `logits_beat`, `logits_downbeat`, `logits_section`, and
  `logits_function`.

Changing model weights or preprocessing semantics requires changing the
manifest `version`; that signature invalidates older cached neural results.
