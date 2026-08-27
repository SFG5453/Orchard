<div align="center">
  <img src="public/orchard-logo.png" alt="Orchard logo" width="128">

# Orchard

**A power-user desktop client for YouTube Music.**

Real shuffle, Best Mix queue sorting, smart crossfade, advanced audio controls, Replay, Orchard Connect, listening parties, lyrics, Last.fm, Discord Rich Presence, and more.

[![Latest release](https://img.shields.io/github/v/release/SFG5453/Orchard?display_name=tag\&sort=semver)](https://sfg545.dev/orchard)
[![License](https://img.shields.io/github/license/SFG5453/Orchard)](LICENSE)
[![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20Linux%20%7C%20macOS-informational)](https://sfg545.dev/orchard)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Donate-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/sfg545)

[Download Orchard](https://sfg545.dev/orchard) · [Report an issue](https://github.com/SFG5453/Orchard/issues) · [Support on Ko-fi](https://ko-fi.com/sfg545) · [View the source](https://github.com/SFG5453/Orchard)

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

https://github.com/user-attachments/assets/96391b60-1b48-4be7-aec3-eb0cd9ea6960

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
| `npm run package:orchard` | Build package-service application archives             |
| `npm run package:linux-system` | Stage an app directory for system Electron       |

## Orchard Mobile for Android

Orchard Mobile is Orchard's standalone native Android client. It plays music independently from the desktop app, signs in to YouTube Music directly, and includes its own library, queue, audio cache, and playback history. It brings Orchard's listening experience to Android with on-device beat and downbeat analysis for Smart Crossfade, synced lyrics, animated artwork, Android Auto, and Orchard Connect.

The mobile app lives in [`mobile/`](mobile/) and has its own [README](mobile/README.md), screenshots, build instructions, and development notes.

Build and install a debug APK with JDK 17 and Android SDK 36:

```bash
cd mobile/android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the mobile unit tests and Android lint checks:

```bash
cd mobile/android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Once Orchard Mobile releases, prebuilt APKs will be available from [sfg545.dev/orchard](https://sfg545.dev/orchard/). Orchard Mobile supports Android 12 (API 31) and newer.

## Orchard Connect for Android

Orchard Connect is built into Orchard Mobile. Pair the phone with the desktop app from the mobile device picker or **Profile → Connected devices**, then approve the request in the desktop Orchard Connect view. Both devices need to be on the same local network.

For the complete pairing flow and security details, see the [mobile documentation](mobile/README.md#pairing-with-orchard-desktop).

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
mobile/                      Native Android/Kotlin Orchard Mobile app
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

## Support

If you enjoy using Orchard and would like to support its development, consider [buying me a coffee on Ko-fi](https://ko-fi.com/sfg545).

## Service dependencies

Some Orchard features depend on external services and may stop working when those services change. This includes YouTube Music sign-in, catalog access, playback, live-show discovery, sharing, scrobbling, updates, and support.

BPM and musical-key metadata is provided by [GetSongBPM](https://getsongbpm.com).

## License

Orchard is available under the [GNU Affero General Public License v3.0 or
later](LICENSE). Releases up to and including 3.x were published under the MIT
License; that grant is irrevocable, so those versions remain MIT. Everything
from 4.0.0 onward is AGPL-3.0-or-later.

Copyright © 2025–2026 SFG545.

Orchard is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version.

Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
details.

You should have received a copy of the GNU Affero General Public License along
with Orchard. If not, see <https://www.gnu.org/licenses/>.
