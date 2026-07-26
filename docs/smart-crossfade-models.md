# Smart Crossfade ONNX models

Orchard release builds include the Smart Crossfade ONNX model suite as an
application resource. Users enable inference with **Settings → Playback → AI
Smart Crossfade analysis**; there is no separate model installation flow.
Playback never depends on inference succeeding: a failed model run leaves the
native/worker analysis and deterministic transition planner in control.

## Bundled pipeline

The shipped pipeline is:

1. Orchard computes log-frequency spectrograms from the first 32 and last 40
   seconds of the decoded stereo mix. Songs shorter than those windows use one
   whole-song pass.
2. All-In-One analyzes the mix for functional sections, beats, and downbeats.
3. Orchard merges trusted intro/outro/section cues into its
   native tempo, key, loudness, and energy analysis.
4. Orchard's transition planner scores the outgoing and incoming All-In-One
   functional sections, checks localized vocal overlap from the native analysis,
   and
   schedules the final beat-aligned mix. When both tracks have high-confidence
   neural beat and downbeat activations, compatible intro/outro sections, and no
   localized vocal clash, the planner can select a quiet 12-beat preroll followed
   by a four-beat takeover; otherwise it retains the longer deterministic blend
   or filter transition.

This mix-native path deliberately avoids source separation. It keeps the neural
decision in the transition planner while making first-play analysis practical
on CPU.

The build-stage installer downloads the pinned assets:

```bash
npm run models:smart-crossfade
```

This downloads about 3.5 MB to `models/smart-crossfade/` and verifies every
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
serialized to one track at a time.

## Licensing

All-In-One is MIT licensed. The installer includes its license text, source URL,
pinned revision, and license identifier in the staged pack. MERT is
intentionally not included, avoiding its CC BY-NC restriction.

## Model-pack contract

`manifest.json` currently supports schema version 1 and the `all-in-one-mix`
pipeline. Paths must remain inside the pack directory.
Required files and tensor contracts are:

- All-In-One input `spec`: float32 `[1, 4, frames, 81]`; each plane contains
  the same mono mix spectrogram.
- All-In-One outputs: `logits_beat`, `logits_downbeat`, `logits_section`, and
  `logits_function`.

Changing model weights or preprocessing semantics requires changing the
manifest `version`; that signature invalidates older cached neural results.
