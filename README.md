<div align="center">
  <img src="public/orchard-logo.png" alt="Orchard logo" width="128">

# Orchard

**A power-user desktop client for YouTube Music.**

Real shuffle, Best Mix queue sorting, smart crossfade, advanced audio controls, Replay, Orchard Connect, listening parties, lyrics, Last.fm, Discord Rich Presence, and more.

[![Latest release](https://img.shields.io/github/v/release/SFG5453/Orchard?display_name=tag\&sort=semver)](https://sfg545.dev/orchard)
[![License](https://img.shields.io/github/license/SFG5453/Orchard)](LICENSE)
[![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20Linux%20%7C%20macOS-informational)](https://sfg545.dev/orchard)

[Download Orchard](https://sfg545.dev/orchard) · [Report an issue](https://github.com/SFG5453/Orchard/issues) · [View the source](https://github.com/SFG5453/Orchard)

</div>

---

Orchard is an open-source, unofficial YouTube Music desktop client built for people who want more control over playback, queues, audio, discovery, and connected devices than the website provides.

It signs in through an embedded browser session and uses browser-backed InnerTube requests to access YouTube Music. Orchard is not affiliated with or endorsed by YouTube or Google.

## Why Orchard?

* **A better queue:** true shuffle, persistent playback state, autoplay, queue history, and **Best Mix** sorting using BPM and musical-key data.
* **A serious audio stack:** smart or fixed crossfade, automatic EQ, a manual ten-band equalizer, dynamic leveling, per-track gain memory, output-device routing, and a live spectrum.
* **Built for desktop:** media keys, tray controls, fullscreen playback, Discord Rich Presence, Last.fm scrobbling, local song caching, and automatic updates.
* **More ways to listen:** local Replay statistics, Release Radar, personalized radio, listening parties, Orchard Connect, live-show discovery, lyrics, and shareable song links.

## Features

### Playback and queues

* Real shuffle, repeat, autoplay, queue history, and persistent queue restore
* **Best Mix** queue ordering with local analysis and catalog BPM/key metadata
* Smart and fixed crossfade modes with transition planning
* Audio and video playback with media keys, desktop controls, tray behavior, and fullscreen mode
* Sleep timer and playback-history tracking
* Song Cache for replaying tracks from disk and prefetching the active queue

### Orchard Audio Engine

* Automatic EQ and manual ten-band EQ
* Built-in presets and profile import/export
* Dynamic leveling and remembered per-track gain
* Live spectrum visualization
* Output-device routing
* Native audio analysis through an N-API addon

### Smart Crossfade

Orchard reproduces beat-matched, phrase-aligned AutoMix transitions with 3-phase volume curves, progressive filter sweeps, downbeat quantization, and bass swaps — inspired by [Apple Music's AutoMix](https://x.com/actuallyaridan/status/1936085699104473205).

<div align="center">
  <video src="docs/automix-crossfade.mp4" controls width="100%"></video>
</div>

### Library and discovery

* Home, search, library, playlist, album, artist, podcast, and expanded-section browsing
* Pins, personalized radio, recently played, and Release Radar
* Local Replay summaries for top tracks, artists, albums, and listening time
* Nearby live-show discovery powered by Ticketmaster
* Synced and unsynced lyrics with provider status

### Social and connected listening

* Peer-to-peer listening parties with synchronized playback and host-controlled queues
* Orchard Connect for approved LAN-paired web and Android controllers
* Discord Rich Presence, including animated artwork when available
* Last.fm now-playing updates and scrobbling
* Optional YouTube listening-history updates
* Shareable Orchard Song Links for songs and collections

### Appearance and reliability

* Immersive artwork backgrounds, OLED mode, system-theme following, and artwork-derived accents
* Installable artist packs with custom artwork, layouts, aliases, and page effects
* Account switching and cached sign-in restore
* Setup checks, diagnostics, backup/restore, and private support reports
* Automatic update checks for bundled desktop packages

## Download

Get the latest release from **[sfg545.dev/orchard](https://sfg545.dev/orchard)**.

| Platform | Available packages                                            |
| -------- | ------------------------------------------------------------- |
| Windows  | NSIS installer                                                |
| Linux    | AppImage, Debian package, RPM package, and Arch Linux package |
| macOS    | ZIP packages for Apple Silicon and Intel                      |

Release files and `SHA256SUMS.txt` are also published at [downloads.sfg545.dev/orchard](https://downloads.sfg545.dev/orchard/).

> [!NOTE]
> Current Windows and macOS builds are unsigned. Your operating system may display a warning during the first launch. Only install Orchard from the official website or this repository, and verify the published checksum when possible.

## Building from source

### Requirements

* Node.js 24 and npm
* Python
* A C++17 toolchain supported by `node-gyp`

Clone the repository and install the locked dependencies:

```bash
git clone https://github.com/SFG5453/Orchard.git
cd Orchard
npm ci
```

Run Orchard in development mode:

```bash
npm run dev
```

This builds the native audio analyzer, starts Vite on `127.0.0.1:5173`, and launches Electron against the development server.

Build the complete application:

```bash
npm run build
```

Run the test suite:

```bash
npm test
```

Launch the locally built application:

```bash
npm run start
```

### Useful commands

| Command                  | Purpose                                                |
| ------------------------ | ------------------------------------------------------ |
| `npm run build:native`   | Build the native audio-analysis addon                  |
| `npm run build:frontend` | Build only the Vue renderer                            |
| `npm run test:native`    | Run the audio, transition, and related native tests    |
| `npm run package`        | Create an unpacked Electron application                |
| `npm run make`           | Create distributable packages for the current platform |
| `npm run make:mac`       | Cross-build a universal macOS ZIP from Linux           |

## Orchard Connect for Android

The native Kotlin companion is located in [`mobile/orchard-connect`](mobile/orchard-connect). It supports Android 7.0 (API 24) and newer.

Build a debug APK with JDK 17 and Android SDK API 36:

```bash
cd mobile/orchard-connect/android
./gradlew assembleDebug
```

Pair it by opening **Settings → Orchard Connect** in the desktop app, scanning the QR code, and approving the device. Both devices must be reachable on the same local network.

## Project structure

```text
src/                         Vue renderer and application state
src/audio/                   Live audio engine and Smart Crossfade pipeline
electron/main/               Electron composition root
electron/preload/            Sandboxed renderer bridge
electron/audio/              Native analysis and audio services
electron/auth/               Browser-backed YouTube authentication
electron/connect/            Orchard Connect server and pairing UI
electron/playback/           Stream resolution, proxying, and playback services
native/                      C++ audio analyzer and N-API bindings
mobile/orchard-connect/      Native Android/Kotlin companion
workers/                     Cloudflare Workers and Durable Objects
services/artwork-converter/  Animated-artwork conversion service
packaging/                   Linux packaging and runner assets
scripts/                     Build, launch, and release utilities
test/                        Node test suite
```

The renderer reaches privileged desktop functionality only through the sandboxed preload surface. Catalog and playback requests use a loopback Socket.IO bridge. Orchard Connect is a separate paired-device service that intentionally listens on the local network.

## Contributing

Bug reports, feature requests, and pull requests are welcome in the main Orchard repository.

Before submitting a code change:

1. Create a focused branch.
2. Keep unrelated changes out of the same pull request.
3. Run `npm test`.
4. Run `npm run build:frontend` for renderer-only work, or `npm run build` when native code is affected.
5. Explain what changed and how it was tested.

Use the [Issues tab](https://github.com/SFG5453/Orchard/issues) for all public bug reports and feature requests. Private reports with optional diagnostics and screenshots can be submitted through Orchard's in-app Support System.

## Service dependencies

Some Orchard features depend on external services and may stop working when those services change. This includes YouTube Music sign-in, catalog access, playback, live-show discovery, sharing, scrobbling, updates, and support.

BPM and musical-key metadata is provided by [GetSongBPM](https://getsongbpm.com).

## Acknowledgements

The Orchard logo was created with the assistance of ChatGPT image generation.

## License

Orchard is available under the [MIT License](LICENSE).

Copyright © 2025–2026 SFG545.
