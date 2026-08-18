# native-media

Native OS media integration for Orchard: MPRIS on Linux, System Media Transport
Controls on Windows, Now Playing / Control Center on macOS. Built as a napi-rs
addon and loaded from `electron/platform/systemMedia.js`.

## Relationship to souvlaki

The design follows [souvlaki](https://github.com/Sinono3/souvlaki) (MIT), which
solves the same problem and was the reference for the platform work here --
particularly the WinRT sequencing on Windows. No souvlaki source is vendored:
every dependency it uses had moved past an API break by the time this was
written (zbus 3 to 5, windows 0.44 to 0.62, raw `objc`/`cocoa` to `objc2`), so
each backend is a fresh implementation against current crates.

It also closes gaps that souvlaki has on every platform:

- **Shuffle and repeat.** Souvlaki exposes neither, on any backend. Here they are
  `Shuffle`/`LoopStatus` on MPRIS, `SetShuffleEnabled`/`SetAutoRepeatMode` plus
  the matching change-requested events on SMTC, and
  `changeShuffleModeCommand`/`changeRepeatModeCommand` on macOS.
- **Real capability flags.** Souvlaki pins `CanGoNext`/`CanSeek`/etc. to `true`
  and enables every SMTC button unconditionally, so the shell offers a next
  button on an empty queue. These follow actual playback state.
- **`mpris:trackid`.** Souvlaki emits the literal path `/` (its own source calls
  this a workaround); clients that key a cache on the track id then treat every
  track as the same one. This emits a per-track path.
- **Full artist lists and `xesam:url`**, rather than a single artist in a
  one-element array.
- **`DesktopEntry`**, which GNOME and KDE use to resolve the player to its
  `.desktop` file for an app icon. Souvlaki omits it, and misspells
  `HasTrackList` as `HasTracklist`.
- **`MPNowPlayingInfoPropertyPlaybackRate`** on macOS, without which Control
  Center's scrubber never advances between updates.
- **`stopCommand`** on macOS, which souvlaki never wires up.
- Souvlaki reads the macOS seek position out of the private `_positionTime`
  ivar; the framework has a public `positionTime` accessor.

## Building

`npm run build:native:media` builds for the host. The cross-builds
(`build:native:media:windows`, `build:native:media:macos:cross`) reuse the
llvm-mingw and osxcross toolchains that the existing C++ addon scripts cache, so
run those first.

Two things about the Windows target are worth knowing before touching the build:

- It targets `x86_64-pc-windows-gnullvm`, for which `CARGO_CFG_TARGET_ENV` is
  `"gnu"`. That sends napi's own build script down a path which panics unless it
  finds a `libnode.dll` -- an artifact Electron does not ship. The build script
  hands it a throwaway stub. Nothing links against it: napi-sys' `dyn-symbols`
  feature resolves every `napi_*` symbol at runtime via `GetProcAddress` on the
  host executable, and the script asserts `libnode.dll` is absent from the
  finished import table.
- `-C target-feature=+crt-static` is required, or the addon imports
  `libunwind.dll`, which the packaging checks reject.

Because napi resolves its symbols from the host process, the same binary loads
under both `node` and `electron` and does **not** need rebuilding when Electron
is upgraded, unlike the node-gyp addon in `native/`.
