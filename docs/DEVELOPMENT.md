# Orchard development

Build commands run from the repository root unless a section says otherwise. For installation and everyday use, see the [README](../README.md).

## Desktop build

Requirements:

- Meson 1.12+, CMake 3.24+ for the vendored CMake submodules, and Ninja.
- A C++20 toolchain and Rust 1.96+ (required by the native YouTube player parser).
- Qt 6.8+ with QML, Quick, Network, Multimedia, WebView, and WebEngine. The release workflow uses Qt 6.12.0.
- Node.js/npm for build-time provider bundling.
- On Linux, Qt DBus and libsecret development packages for the keychain backend.

The [desktop release workflow](../.github/workflows/manual-desktop-build.yml) lists the platform dependencies and Qt installation steps used for release builds.

```sh
git submodule update --init --recursive
npm ci --prefix providers/youtube
meson setup build/dev --buildtype debug
meson compile -C build/dev
meson test -C build/dev --print-errorlogs
```

The `orchard` executable launches the embedded Qt Quick interface. The Rust core and native system media service are linked into the desktop application. Run `./build/dev/orchard` on Linux or `build\dev\orchard.exe` from a Windows command prompt.

### Sign-in backend

Windows sign-in uses QtWebView's Qt WebEngine backend, initialized in the short-lived authentication helper. Use an MSVC Qt installation containing Qt WebEngine Quick and the `qtwebview_webengine` plugin.

The Windows packaging script deploys the WebEngine libraries, helper process, QML module, resources, and locales. Saved Orchard sessions remain in the keychain. WebView2 browser profiles are not migrated to WebEngine.

On Linux, QtWebView's WebEngine cookie store supplies the native session. The release workflow builds QtWebView 6.12.0 with its WebEngine backend enabled. For an SDK without that plugin, run:

```sh
python scripts/build-qt-webview.py --qt-root /path/to/Qt/6.12.0/gcc_64 --build-dir build/qt-webview
```

On Windows, use the `msvc2022_64` SDK from an MSVC developer shell. A complete native YouTube cookie session completes sign-in on both platforms. Account name/avatar probing is best effort and cannot reject a session with an `accounts_list` error.

### Cross compilation

The build uses bundled `qjsc` to compile the YouTube provider into embedded `youtube-provider.qjc` bytecode. For cross compilation without an emulator, build a host compiler from the same source:

```sh
cmake -S third_party/quickjs -B build-qjsc-host -DCMAKE_BUILD_TYPE=Release
cmake --build build-qjsc-host --target qjsc -j 4
```

Pass the executable path with `-Dhost_qjsc=/absolute/path/to/qjsc` at `meson setup`.

## Provider and playback runtime

YouTube player parsing and dependency extraction run in Rust using Oxc. QuickJS executes the extracted signature program. `ORCHARD_PLAYBACK_TIMING=1` prints download, native parsing/analysis/emission, and JavaScript compilation times.

For a cold resolver check that bypasses both player caches:

```sh
ORCHARD_TEST_LIVE_YOUTUBE=1 ORCHARD_TEST_COLD_PLAYER=1 \
  ./build/dev/orchard_provider_runtime_tests liveBrowserPlayerRunsInQuickJs
cargo test -p orchard-youtube-extractor -p orchard-core-ffi --lib
```

- `core/js` holds platform-neutral playback policy.
- `providers/qobuz` holds the fetch-based Qobuz provider.
- `providers/youtube` holds YouTube.js catalog parsing, authenticated request signing, format selection, routing, history/likes, playlist mutation, SponsorBlock, and PO-token cache policy.
- `orchard-system-media` uses playwire and reaches Qt through the Rust core's C ABI.
- `crates/orchard-transition-mobile` provides the Android UniFFI/JNI adapter and builds against `orchard-transition-core` and Earmark.

Node runs provider tests and build-time bundling. It is not part of the application runtime. Qt provides authentication, networking, timers, and native application integration.

High-quality YouTube playback mints video-bound PO tokens in QuickJS. A separate bytecode bundle supplies BgUtils and JSDOM compatibility. It loads on first playback and is reused across tracks. Token minting launches no browser or Node process, and the token cache is memory-only.

See [Contributing](../contributing.md) for file limits and comment guidelines.

## Adaptive mix

Playback settings offer **Standard** and **Adaptive mix** crossfade modes. Adaptive mix prepares local beat-aware transitions and animates the artwork handoff.

Desktop Adaptive mix requires a hardware WebGPU device for Beat This and FFmpeg on PATH. Beat inference has no CPU fallback. See the [adaptive mix crate](../crates/orchard-adaptive-mix/README.md) for runtime packaging, model provenance, and validation instructions, and [Crossfade and gapless playback](app/crossfade.md) for user-facing settings and limitations.

## Desktop releases

Run the [desktop release workflow](../.github/workflows/manual-desktop-build.yml) from **Actions → Build and publish desktop release → Run workflow** on `master`. Choose `canary` or `stable`.

Linux and Windows each build Orchard and its native bootstrapper. Linux also produces an AppImage. Windows produces a portable `.7z` and an NSIS setup executable. The packaging trees and installers pass to an Ubuntu 24.04 publish job as workflow artifacts.

The publish job signs both manifests and the channel file, uploads immutable content objects, manifests, and versioned installer and portable downloads to the US jurisdiction R2 bucket, then updates `releases/<channel>.json` last.

The app version in `meson.build` must match the newest bundled release note. Bump the version for every publish. A canary version belongs on the canary channel. Component versions in `scripts/release/layouts/` must change when their files change.

The Linux release uses the pinned Clang, Clang++, and mold toolchain in [linux-clang-mold.ini](../scripts/release/linux-clang-mold.ini). Windows pins ccache with MSVC in [windows-msvc-ccache.ini](../scripts/release/windows-msvc-ccache.ini). Both platform jobs keep separate 2 GiB ccache directories across Actions runs, including failed packaging runs. Cache stats appear at the end of each job.

Configure these repository secrets:

- `ORCHARD_RELEASE_PRIVATE_KEY`
- `R2_ACCOUNT_ID`
- `R2_BUCKET`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`

The R2 token needs read, list, and write access to the release bucket. The signing key must match `bootstrapper/src/security/TrustedKeys.cpp`. See [Bootstrapper](BOOTSTRAPPER.md) for installer behavior, signed releases, updates, repair, and rollback.

## In-app documentation

The Docs window opens from the book icon in the top bar, a Spotlight entry, or a link in Settings. It renders the pages in [`docs/app`](app). Meson embeds every `docs/app/*.md` file. **Copy page** and **Copy all docs for AI** put raw Markdown on the clipboard.

Page rules, enforced by `orchard_docs_tests`:

- Include a YAML header with `title`, `summary`, `group`, `icon` (a Lucide name from `app/qml/assets/lucide`), `keywords`, `platforms`, and a unique `order`.
- Keep pages in the same group next to each other in `order`.
- Use an H1 that repeats the title, then `##` sections that each answer one question.
- Link pages as `[text](page-id.md)`. The viewer shows plain text plus a Related pages row.
- Use no tables or em dashes.

Each section must stand alone: put the answer first, repeat the feature name when needed, and use the exact UI labels.
