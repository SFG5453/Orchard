# Orchard

Orchard is an MIT-licensed, unofficial desktop YouTube Music client built with Electron, Vue 3, Quasar, Vite, Socket.IO, and `youtubei.js`. It signs in through an embedded browser session, talks to YouTube Music through browser-backed InnerTube requests, and layers a native-feeling music player around the catalog.

## Features

- Browser-based YouTube Music sign-in, account switching, and cached sign-in restore
- Home, search, library, playlist, album, artist, podcast, and section-more browsing
- Pins, personalized radio, release radar, recently played, and local Replay stats
- Queue management with shuffle, repeat, autoplay, playback history, and persistent restore
- Audio and video playback with age-gate fallback warnings, media keys, desktop controls, tray behavior, and fullscreen player
- Synced and unsynced lyrics with provider status
- Smart or fixed crossfade, sleep timer, and queue transition planning
- Orchard Audio Engine with automatic EQ, manual ten-band EQ, presets, dynamic leveling, per-track gain memory, live spectrum, profile import/export, and output-device routing
- Song Cache for replaying cached tracks from disk and prefetching the current queue
- Appearance controls for immersive artwork backgrounds, accent color source, OLED mode, system theme following, and installable artist packs
- Song and collection sharing through Orchard Song Links, with direct or search links for supported music services
- Peer-to-peer listening parties with synchronized playback and host-controlled queues
- Orchard Connect for approving LAN-paired web or Android controllers
- Discord Rich Presence with animated Apple Music artwork when available
- Last.fm now-playing updates and scrobbling with encrypted local account sessions, plus optional YouTube listening-history updates
- Ticketmaster-backed live-show discovery by city, postal code, or location
- Private support reports with optional diagnostics, screenshots, conversation replies, and unread reply notifications
- Update checks, setup checklist, diagnostics, and backup/restore tools

## Getting Started

Building Orchard requires Node.js and npm, Python, and a C++17 toolchain supported by `node-gyp`. Install the JavaScript dependencies first:

```bash
npm install
```

Run the desktop app in development:

```bash
npm run dev
```

This starts Vite on `127.0.0.1:5173` and launches Electron against the dev server.

Build the native audio analyzer and renderer:

```bash
npm run build
```

For a renderer-only build, use `npm run build:frontend`.

Run the root application test suite:

```bash
npm test
```

Launch the locally built app:

```bash
npm run start
```

This runs the full native and renderer build first, then opens Electron against the generated `dist/` files.

## Packaging

Create an unpacked Electron Builder package for the current platform:

```bash
npm run package
```

Create distributable artifacts for the current platform:

```bash
npm run make
```

GitHub release automation publishes Linux AppImage, Debian, RPM, and Arch packages; a Windows NSIS installer; and unsigned macOS zips for Apple Silicon and Intel. Tagged builds run in GitHub Actions and attach the packages directly to the matching GitHub Release. The desktop updater reads from `ORCHARD_UPDATE_URL` when set, otherwise it uses the default downloads host in `electron-builder.config.cjs`.

Build the universal macOS zip from Linux with:

```bash
npm run make:mac
```

The local command cross-compiles Orchard's native audio analyzer with a pinned [OSXCross](https://github.com/tpoechtrager/osxcross) toolchain and a checksummed macOS 15.5 SDK from [alexey-lysiuk/macos-sdk](https://github.com/alexey-lysiuk/macos-sdk), then merges Apple Silicon and Intel Electron bundles. Review Apple's Xcode license before enabling the SDK-based cross-build in your environment. Tagged GitHub releases instead compile each architecture on a native macOS runner. Because the GitHub artifacts are not signed or notarized, macOS will block the first launch; after trying to open Orchard, use **System Settings → Privacy & Security → Open Anyway** if you trust the downloaded build.

To create an application payload for a distro-provided Electron 43 runtime:

```bash
npm run package:linux-system -- --electron-dist=/usr/lib/electron43
```

To build the Arch Linux package locally:

```bash
packaging/linux/arch/build-local.sh
```

These system-runtime builds expect Electron 43 at `/usr/lib/electron43`. Distro packages disable Orchard's bundled updater because updates are owned by the package manager.

## Project Structure

- `src/` is renderer-only; application installers are grouped by domain under `src/app/`
- `src/audio/` separates the live Web Audio engine from offline Smart Crossfade analysis and its worker
- `src/components/` groups window chrome, controls, dialogs, player UI, settings, and browse views
- `electron/main/` is the Electron composition root, while `electron/preload/` contains the isolated context bridge
- `electron/audio/`, `auth/`, `bridge/`, `catalog/`, `connect/`, `playback/`, `platform/`, and `integrations/` keep main-process responsibilities explicit
- `shared/` contains process-neutral compatibility contracts such as Electron IPC channel names
- `native/binding/` owns N-API conversion and worker dispatch; `native/analyzer/` owns offline audio DSP
- `mobile/orchard-connect/` contains the native Android/Kotlin Orchard Connect companion
- `workers/song-links/` contains the Cloudflare Worker and D1 schema for Orchard share pages
- `workers/listening-party/` contains the Durable Object used for party rooms and WebRTC signaling
- `workers/artwork-proxy/` contains the Cloudflare Worker that validates and proxies animated artwork conversion
- `workers/support/` contains the private support Worker, D1/R2 integration, Discord commands, and issue mirroring
- `workers/concerts/` contains the Ticketmaster-backed live-shows Worker
- `workers/lastfm/` contains the credential-protected Last.fm authentication and scrobbling Worker
- `workers/artist-packs/` serves hosted artist packs from R2, while `workers/artist-metadata/` caches confirmed iTunes genre matches in D1
- `services/artwork-converter/` contains the Node/FFmpeg service used by the artwork proxy
- `packaging/` contains Linux packaging assets and runner container files
- `scripts/` contains local launch and packaging helpers

The renderer reaches privileged desktop APIs only through the sandboxed preload
surface. Catalog and playback requests use a loopback Socket.IO bridge; Orchard
Connect is a separate paired-device service that intentionally listens on the
local network.

## Notes

- Orchard is a desktop app, not a headless service.
- Sign-in, playback, browsing, live shows, sharing, updates, and support depend on external services and network access.
- Orchard is not affiliated with or endorsed by YouTube or Google.
- `npm run build:frontend` checks renderer-only changes; `npm run build` also rebuilds the native analyzer.
- Worker and service READMEs contain their own provisioning, secrets, and deployment instructions.

## License

[MIT](LICENSE).
