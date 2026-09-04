# Orchard for Linux (Tauri)

This directory contains Orchard's Linux-only Tauri shell. It is separate from
the Electron main process and intentionally uses Linux-native integrations.

## Architecture

- Tauri owns the windows, lifecycle, and command boundary.
- YouTube.js runs in the Tauri webview. Its custom `fetch` implementation uses
  Tauri's HTTP plugin so YouTube requests are not blocked by browser CORS.
- `ffmpeg-next` demuxes and decodes audio; CPAL sends stereo PCM directly to the
  system audio device. WebKit is not used for music playback.
- `playwire` owns Linux system-media/MPRIS integration. It is not a playback
  engine.
- `discord-rich-presence` owns Discord IPC directly; the Linux shell does not
  route presence updates through Electron.
- `orchard-transition-core` is linked directly from the existing Rust core for
  Smart Crossfade transition rendering.
- The existing renderer keeps `hls.js` for animated-artwork manifests.
- `src/platform/desktop` selects an Electron or Tauri adapter before Vue starts.

There is no guest session, guest catalog access, or guest playback fallback.
YouTube Music operations require the browser-backed sign-in flow.

## System requirements

Install Node.js/npm, stable Rust, FFmpeg development libraries, ALSA development
headers, GTK 3, WebKitGTK 4.1, and the usual C/C++ build tools. On Debian or
Ubuntu the relevant packages are typically:

```sh
sudo apt install build-essential curl pkg-config libgtk-3-dev \
  libwebkit2gtk-4.1-dev libasound2-dev libavcodec-dev libavformat-dev \
  libavutil-dev libswresample-dev
```

Then install JavaScript dependencies from the repository root:

```sh
npm ci
```

## Commands

Run the development shell:

```sh
npm run dev:linux
```

The Linux development command uses Vite's normal development server, including
hot module replacement.

Build AppImage, Debian, and RPM packages:

```sh
npm run build:linux
```

Useful verification commands:

```sh
cargo check --manifest-path linux/Cargo.toml
cargo test --manifest-path linux/Cargo.toml
npm run build:frontend
npm test
```

## Current scope

The Linux shell provides authenticated sign-in, paginated Home and library
feeds, library categories, search, album/artist/playlist browsing, Autoplay,
and direct native-audio stream resolution. It also provides dual native decks,
system media controls, and the shared transition renderer. Catalog handlers
that have not yet moved to the renderer's Tauri YouTube service fail explicitly
instead of falling back to an unauthenticated client.
