<div align="center">
  <img src="assets/icon.png" alt="Orchard logo" width="128">

# Orchard Mobile

**A power-user Android client for YouTube Music.**

Smart Crossfade with real beat matching, on-device track analysis, synced lyrics, animated artwork, Android Auto, Orchard Connect, and a full library that works signed in or signed out.

[![License](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2012%2B-informational)](#install)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Donate-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/sfg545)

[Orchard for desktop](../) · [Report an issue](https://github.com/SFG5453/Orchard/issues) · [Support on Ko-fi](https://ko-fi.com/sfg545)

</div>

---

Orchard Mobile is the phone half of [Orchard](../) — a standalone native Android app, not a remote control for the desktop client. It plays music on its own, keeps its own library, and signs into YouTube Music directly. Orchard is not affiliated with or endorsed by YouTube or Google.

## Why Orchard Mobile?

* **Transitions that actually mix.** Smart Crossfade analyzes both tracks on the device, finds the downbeat, time-stretches one to meet the other, and rides a filter through the blend. When the evidence isn't there, it falls back to a normal fade.
* **It does the analysis itself.** Beat grids, tempo, key, energy, and vocal presence are computed on the phone from the audio.
* **Everything the phone can show.** Synced lyrics, animated cover art when a provider has it, artwork-derived accent colors, and offline caching for instant replays.

## Screenshots

<div align="center">
  <img src="docs/screenshots/home.png" width="24%" alt="Home">
  <img src="docs/screenshots/now-playing.png" width="24%" alt="Now Playing">
  <img src="docs/screenshots/lyrics.png" width="24%" alt="Synced lyrics">
  <img src="docs/screenshots/queue.png" width="24%" alt="Queue">
</div>

## Features

### Playback

* Smart Crossfade with beat-matched, phrase-aligned transitions, or a fixed crossfade of 1–12 seconds
* Gapless playback for albums played in order
* Queue with reordering, removal, history, and restore after the app is killed
* Media notification, lock-screen controls, headset and Bluetooth buttons, audio focus
* Artwork-tinted home-screen widgets for playback controls and recent tracks
* Android Auto browsing and voice search
* Playback history

### On-device analysis

Orchard listens to the audio rather than trusting a catalog. Beat and downbeat tracking runs a quantized [Beat This!](https://github.com/CPJKU/beat_this) model, vocal presence comes from open-unmix, and tempo, key, and energy come from a native C++ analyzer. Everything feeds the transition planner, which decides where a mix belongs and how ambitious it can afford to be.

### Library and browsing

* Home, search, library, playlists, albums, and artists
* Native YouTube Music sign-in through a dedicated Compose screen - sign in for your library, or skip it and browse as a guest
* Offline metadata cache and a configurable audio cache for instant re-listens
* Synced and unsynced lyrics from Orchard's resolver chain

### Connected listening

* **Orchard Connect** - hand playback to a paired Orchard desktop and take it back, over the local network
* Discord Rich Presence, with animated artwork where available
* Shareable Orchard Song Links

## Install

- Download the latest apk at https://sfg545.dev/orchard

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **Orchard** from the app drawer. Search and playback work immediately; sign in from **Profile → Account** to load your own library.

Release builds are signed from `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`:

```bash
./gradlew assembleRelease
```

## Pairing with Orchard desktop

The phone is always its own playback destination. To add a desktop, open the device picker from Now Playing or **Profile → Connected devices**, then:

1. Open Orchard desktop and its Orchard Connect pairing view.
2. Scan the QR code, or paste its `orchard-connect://pair` link.
3. Approve the phone on the desktop.
4. Pick the desktop to send playback there, or the phone to bring it home.

Both devices need to be on the same network. The pairing token is AES/GCM encrypted with an AndroidKeyStore key, bound to the remembered host, and kept out of backups.

## Development

```bash
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The unit suite covers auth signing, queue edits and restoration, playback state, artwork matching, transition filtering and planning, pairing, reconnect policy, and device transfers. Instrumented tests cover the analysis models, which need a real device.

Deeper notes live in [the beat model's provenance](docs/BEAT_MODEL.md).

### Project map

```text
android/app/src/main/java/dev/sfg/orchard/
  mobile/playback/    Media3 service, stream resolution, queue rules
  mobile/playback/smart/  Analysis, transition planning, rendering
  mobile/catalog/     YouTube Music API boundary
  mobile/artwork/     Static and animated cover art providers
  mobile/auth/        Cookie-session auth and Keystore storage
  mobile/lyrics/      Lyrics resolver chain
  mobile/connect/     Orchard Connect target selection and transfer
  mobile/ui/          Compose theme, navigation, screens
  connect/            Typed Socket.IO protocol and pairing client
android/app/src/main/cpp/
  analyzer/           Log-mel front end and tempo analysis
  transition/         Time-stretch and transition rendering
```

## Support

If you enjoy using Orchard Mobile and would like to support its development, consider [buying me a coffee on Ko-fi](https://ko-fi.com/sfg545).

## License

Orchard Mobile is free software under the [GNU Affero General Public License v3.0 or later](LICENSE).

Copyright © 2026 SFG545.

AGPL rather than GPL so code can move freely between here and Orchard desktop, which is also AGPL-3.0. The native analysis front end and the transition engine are both shared source, and more is expected to be.

### Third-party components

Smart Crossfade ships trained models. Both were chosen because their **weights**, carry a permissive license. Most published music-information-retrieval weights, Essentia's included, are CC BY-NC-SA and cannot be distributed in an application.

* **[Beat This!](https://github.com/CPJKU/beat_this)** (Foscarin, Schlüter & Widmer, ISMIR 2024) — beat and downbeat tracking. Code and weights both MIT. Mobile ships the official `small0` checkpoint converted to ONNX and quantized to int8 to reduce model size and inference cost. See [docs/BEAT_MODEL.md](docs/BEAT_MODEL.md).
* **open-unmix** (Stöter & Liutkus, Inria/SigSep) — used only to measure how much vocal content is present at a given instant. Code and the umxhq weights both MIT, confirmed on the weights' own [Zenodo deposit](https://zenodo.org/record/3370489). Only the `vocals` target ships. Meta's htdemucs separates better but releases its weights under CC-BY-NC-4.0, which a distributed app cannot ship.
* **ONNX Runtime** (Microsoft) — MIT.
* **earmark** — the beat-aware crossfade engine that plans and renders every transition, shared with Orchard desktop and vendored at `native-audio-rust/vendor/earmark`. MIT OR Apache-2.0. It reaches Kotlin through [UniFFI](https://github.com/mozilla/uniffi-rs) (MPL-2.0) and [JNA](https://github.com/java-native-access/jna) (Apache-2.0 or LGPL-2.1-or-later), and time-stretches with [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) (MIT).
