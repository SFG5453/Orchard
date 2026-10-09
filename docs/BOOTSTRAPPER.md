# Orchard bootstrapper

`bootstrapper/` builds one small native binary (`Orchard.exe` on Windows, `orchard` on Linux) that installs, updates, repairs, rolls back and launches Orchard. It has no Qt dependency: C++20, vendored mbedTLS (hashing, signatures, and TLS on Linux), nlohmann/json, and WinHTTP on Windows. A release build is about 1.0 MB on Linux (libstdc++ linked statically) and 0.7 MB on Windows.

## Build and test

```bash
meson setup build-boot bootstrapper
ninja -C build-boot
meson test -C build-boot          # end-to-end test over a local HTTPS server
```

Windows cross build: `meson setup build-boot-win bootstrapper --cross-file <llvm-mingw cross file>`. GCC 16's MinGW LTO crashes with an internal compiler error; use llvm-mingw or MSVC. `bootstrapper/tests/wine_smoke.py` runs the Windows build under Wine against a local server.

### Windows setup executable

`bootstrapper/OrchardSetup.nsi` wraps the release Windows bootstrapper in a per-user NSIS setup executable. It embeds only the bootstrapper; that program downloads and verifies the signed app release, installs into `%LOCALAPPDATA%\Programs\Orchard`, and creates the Start menu shortcut and Windows uninstall entry. The setup wizard lets users choose Stable or Canary; manual builds default to Stable, while the release workflow preselects its published channel. It passes that channel to `--install --no-launch`. Its final page offers to launch Orchard when the user clicks Finish, so the installer does not wait for the app. To preselect a channel from the command line, run `OrchardSetup.exe /CHANNEL=canary` (or `/CHANNEL=stable`). `/S` hides the NSIS wizard and does not launch the app, though the native bootstrapper still shows its installation progress. The NSIS wrapper waits for installation and returns a nonzero exit code if it fails. Uninstall remains the bootstrapper's `--uninstall` command.

```bash
makensis -DBOOTSTRAPPER_EXE="$PWD/build-boot-win/orchard-bootstrapper.exe" \
  -DOUTPUT_FILE="$PWD/build/OrchardSetup.exe" bootstrapper/OrchardSetup.nsi
```

Set `ORCHARD_VERSION` and `ORCHARD_FILE_VERSION` with `-D` when packaging a newer bootstrapper. Package the release binary, not `orchard-bootstrapper-test.exe`, which includes test-key hooks. The downloaded app requires a published, signed release for the selected channel on the configured release server.

The `orchard-bootstrapper-test` binary is the same code compiled with `ORCHARD_BOOTSTRAP_TEST_HOOKS`, which trusts one extra public key from `ORCHARD_BOOTSTRAP_TEST_KEY`. Release builds trust only the keys in `src/security/TrustedKeys.cpp`.

## Install layout

| Path | Contents |
| --- | --- |
| `orchard` / `Orchard.exe` | the bootstrapper |
| `install.json` | install state (below) |
| `update-state.json` | progress of the current or last update, read by the app |
| `versions/<version>/` | app files of one release |
| `components/<name>/<version>/` | shared runtimes: Qt, FFmpeg, ONNX Runtime (Dawn), models, MSVC runtime |
| `manifests/<version>-<platform>.json` | signed manifests of installed versions |
| `cache/objects/<aa>/<sha256>` | verified download cache, emptied after each successful stage |
| `logs/bootstrapper.log` | rotated at 1 MiB |

Install roots: `%LOCALAPPDATA%\Programs\Orchard` on Windows, `$XDG_DATA_HOME/orchard` (normally `~/.local/share/orchard`) on Linux. User data stays where Qt puts it (`SFG545/Orchard`) and is never touched.

The GUI app never replaces itself. A release is assembled in `.<name>.staging` next to the running one, verified, renamed into place, and then activated by changing `current` in `install.json`.

## Server layout

The bootstrapper and updater read releases from
`https://depot.sfg545.dev/`:

```
releases/<channel>.json                 signed channel file
manifests/<version>-<platform>.json     signed release manifest
objects/<sha256>                        immutable content-addressed bytes
```

Platforms: `win-x86_64`, `linux-x86_64`; `win-arm64` and `linux-arm64` are recognized by the client. Objects never change once uploaded, so the CDN can cache them forever. Channel files must not be cached for long.

## Signed envelope

Channel files and manifests are wrapped like this:

```json
{
  "format": "orchard-signed-v1",
  "keyId": "orchard-release-1",
  "algorithm": "ecdsa-p256-sha256",
  "payload": "<base64 of the JSON payload bytes>",
  "signature": "<base64 DER ECDSA signature over the payload bytes>"
}
```

The client verifies the signature with the embedded public key named by `keyId` before parsing the payload. Unknown key ids, other algorithms, and bad signatures are rejected. The private key never ships. To rotate: add the new public key to `TrustedKeys.cpp`, release that bootstrapper, wait for clients to update, then sign with the new key.

## Channel file payload

```json
{
  "schema": 1,
  "channel": "stable",
  "sequence": 42,
  "version": "4.2.14",
  "minimumBootstrapperVersion": "1.0.0",
  "platforms": {
    "win-x86_64": {"manifest": "manifests/4.2.14-win-x86_64.json", "sha256": "<hash of the envelope file>", "size": 18234}
  },
  "bootstrapper": {
    "version": "1.1.0",
    "platforms": {"win-x86_64": {"path": "Orchard.exe", "size": 701440, "sha256": "...", "executable": true}}
  }
}
```

- `channel` must match the requested channel, so a canary file cannot be served as stable.
- `sequence` must increase with every publish. The client remembers the highest sequence per channel and rejects older files, so a compromised CDN cannot replay an old release.
- The manifest is pinned by hash and size, and is also signed in its own right so stored manifests can be re-verified offline.
- `bootstrapper` is optional. When it is newer than the installed bootstrapper, the client replaces itself (below).

## Manifest payload

```json
{
  "schema": 1,
  "version": "4.2.14",
  "platform": "win-x86_64",
  "minimumBootstrapperVersion": "1.0.0",
  "files": [
    {"path": "orchard.exe", "size": 19384721, "sha256": "...", "chunks": [{"sha256": "...", "size": 4194304}, "..."]},
    {"path": "LICENSE", "size": 34523, "sha256": "..."}
  ],
  "components": {
    "qt": {"version": "6.12.0-1", "files": ["..."]},
    "models": {"version": "1", "files": ["..."]}
  },
  "launch": {
    "executable": "orchard.exe",
    "args": [],
    "env": {"QT_PLUGIN_PATH": "{component:qt}/plugins", "ORCHARD_MODELS_DIR": "{component:models}"},
    "prependPaths": {"PATH": ["{component:qt}", "{component:onnxruntime}"]}
  },
  "icon": "orchard.png"
}
```

File entries:

- `path` is relative, `/`-separated, and validated: absolute paths, drive letters, `..`, backslashes, `:`, control characters, trailing dots or spaces, Windows device names (`CON`, `NUL`, `COM1`...) and case-insensitive duplicates are rejected. Every write is also checked to land inside the install root.
- A file without `chunks` is one object whose hash is the file hash. With `chunks`, the file is the concatenation of the chunk objects, and the chunk sizes must add up to `size`. The whole-file `sha256` is always verified after assembly.
- `executable: true` sets the execute bit on Linux.
- An optional `encoding` (on a file or chunk) other than `identity` means a newer bootstrapper is required. This reserves room for compressed or delta objects.

Components: a component version names one exact set of files. The client computes a digest over the sorted `path`, `size`, `sha256` and `executable` of its files, and refuses a component whose files changed without a version bump. `orchard_release.py` enforces the same rule before publishing. Unchanged components are skipped entirely, so a normal update downloads nothing for Qt, FFmpeg, models or runtimes.

Launch placeholders: `{app}` (the version directory), `{root}` (the install root) and `{component:name}` (that component's directory). A placeholder must start the value and may be followed by `/relative/path`. Every `prependPaths` entry must use one. The bootstrapper also sets `ORCHARD_BOOTSTRAPPER`, `ORCHARD_INSTALL_ROOT`, `ORCHARD_INSTALLED_VERSION` and `ORCHARD_UPDATE_CHANNEL`.

`schema` higher than the client knows, or `minimumBootstrapperVersion` above its own version, makes the client update itself first (when the channel offers a new enough bootstrapper) or exit with code 4 and a "download the latest installer" message.

## install.json

```json
{
  "schema": 1, "channel": "stable", "platform": "win-x86_64",
  "current": "4.2.14", "previous": "4.2.13", "pending": "4.2.15",
  "components": {"qt": "6.12.0-1", "models": "1"},
  "versions": {"4.2.14": {"components": {"...": "..."}, "launch": {"...": "..."}, "icon": "", "healthy": true, "launchAttempts": 0}},
  "installedComponents": {"qt": {"6.12.0-1": "<digest>"}},
  "skippedVersions": ["4.2.12"],
  "channelSequence": {"stable": 42},
  "lastCheck": 1790000000, "autoUpdate": true,
  "bootstrapperVersion": "1.0.0",
  "created": ["C:\\Users\\...\\Orchard.lnk", "registry:HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Orchard"]
}
```

Unknown keys survive rewrites, so older bootstrappers keep fields that newer ones add. Every write is atomic (write, flush, rename) and keeps `install.json.bak`. `created` lists everything outside the install root (shortcuts, desktop entry, icon, registry key) so uninstall removes exactly that.

## Commands

| Command | Effect |
| --- | --- |
| (none) / `--launch` | Activate a staged update if one is ready, then start Orchard. Installs first if nothing is installed. Never waits on the network. |
| `--install` | Install, or resume an interrupted install, then launch. A downloaded installer run on an installed machine hands off to the installed bootstrapper. |
| `--check-update` | Network check; prints `{"current","latest","available","downloadBytes",...}`. |
| `--update` | Check, download, verify and stage. The update activates on the next launch. |
| `--update-on-exit --wait-pid PID` | Wait for PID to exit, activate the staged update, start Orchard. |
| `--status` | Print install and update state as JSON. |
| `--repair` | Hash every installed file of the current version, redownload only damaged chunks. |
| `--rollback` | Make `previous` current and skip the abandoned version. |
| `--channel stable|canary` | Switch channel. Combined with another command, it switches first. |
| `--auto-update on|off` | Toggle background downloads. |
| `--confirm-healthy` | Mark the running version healthy. |
| `--cancel` | Stop a running download; it resumes next time. |
| `--uninstall [--yes]` | Remove shortcuts, the uninstall entry and the install root. |
| `--root DIR`, `--server URL` | Override the install root or release server (https only; signatures still apply). |

Exit codes: 0 success, 1 error, 2 usage, 3 cancelled, 4 bootstrapper too old. Anything that is not an option, and everything after `--`, is passed to Orchard.

## App IPC

There is no resident service. The app reads two files in `ORCHARD_INSTALL_ROOT` and starts the bootstrapper for actions (`app/src/update/bootstrapper_client.cpp`, exposed to QML as `OrchardUpdates`):

- Status: `update-state.json` (`state` is `checking`, `downloading`, `staging`, `ready`, `up-to-date`, `error` or `cancelled`, plus `done`/`total` bytes, `latest` and `error`) and `pending` in `install.json`.
- Download: start `--update` detached and poll the state file while it is busy.
- Install on restart: start `--update-on-exit --wait-pid <app pid>` detached, then quit.
- Cancel, channel, auto-update, repair and rollback map to their commands.

Settings > General > Updates shows the state and offers "Update ready. Restart Orchard to install."

## Launch, health and rollback

A healthy version starts with no waiting. The first launches of a new version are watched for 30 seconds: a non-zero exit in that window counts as a failed start, and after two failed starts the bootstrapper rolls back to `previous` and starts it. Surviving the window, or exiting with code 0, marks the version healthy. The launch attempt counter is written before starting, so a crash that also takes the bootstrapper down still counts.

After launch, the bootstrapper starts a detached `--background-update` when automatic updates are on and the last check is more than six hours old. That run stages any new release and, once the current version is healthy, deletes versions other than current, previous and pending, plus unreferenced components and staging debris.

## Downloads

Objects download over HTTPS with certificate verification always on: WinHTTP and the system store on Windows; mbedTLS with the system CA bundle on Linux (`SSL_CERT_FILE` overrides). Six workers fetch in parallel over keep-alive connections, largest objects first. Each object streams to `<sha256>.part`, resumes with a `Range` request after an interruption, retries up to five times with backoff, and is renamed into the cache only when its size and hash match. Before downloading, needed objects are copied out of installed files whose manifests list them (rehashed on copy), so bytes already on disk are never downloaded again. Disk space is checked before downloading.

## Self-update

The new bootstrapper is assembled as `Orchard.new.exe` and verified against the signed channel file. The running `Orchard.exe` is renamed to `Orchard.old.exe` (Windows allows renaming a running executable) and the new file takes its name. On Linux a rename swaps the directory entry and the running copy keeps its inode. The fallback is deleted on a later launch.

## Publishing a release

The normal path is the [desktop release workflow](../.github/workflows/manual-desktop-build.yml).
It builds both platforms and their bootstrappers, then runs
[`publish_r2.py`](../scripts/release/publish_r2.py) on Ubuntu 24.04. The publisher
downloads retained manifests to check component versions, signs new manifests
and the channel file, uploads objects and manifests plus versioned installer
downloads, and writes `releases/<channel>.json` last. A failed upload leaves the
old channel pointer in place. Run the workflow from `master` after changing the
app version and bundled release notes. The existing version cannot be
republished.

Required repository secrets are `ORCHARD_RELEASE_PRIVATE_KEY` (PEM),
`R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`.
The bucket lives in the US jurisdiction, so the publisher uses the matching
S3 endpoint. Generated installers are available at
`downloads/<channel>/<version>/orchard`, `Orchard.exe`, and
`OrchardSetup.exe` under that directory. The AppImage and Windows `.7z` are
published in the same directory. A workflow-built Windows setup selects its
published channel by default. For the Linux canary bootstrapper, pass
`--channel canary --install` on first run.

The commands below remain useful for local signing and layout checks:

```bash
scripts/release/orchard_release.py keygen --out ~/.config/orchard-release/orchard-release-1.pem   # once
scripts/release/orchard_release.py manifest --version 4.2.14 --platform win-x86_64 \
    --tree build-windows-msvc-meson/package/Orchard --layout scripts/release/layouts/win-x86_64.json \
    --out site --key ~/.config/orchard-release/orchard-release-1.pem
scripts/release/orchard_release.py channel --channel stable --version 4.2.14 \
    --platform win-x86_64 --platform linux-x86_64 \
    --bootstrapper win-x86_64=build-boot-win/orchard-bootstrapper.exe --bootstrapper-version 1.0.0 \
    --out site --key ~/.config/orchard-release/orchard-release-1.pem
```

Upload `site/objects` and `site/manifests` first and `site/releases` last, so a client never sees a channel file that points at missing objects. Keep the manifests of older releases on the server: repair of an older install fetches its manifest by version. The layouts in `scripts/release/layouts/` map the existing packaging trees (the AppImage AppDir and the windeployqt folder) onto components. Unmapped files fail the build, and every component version must be bumped when its files change. Files above 8 MiB are split into 4 MiB chunks; the client only sees chunk hashes, so the pipeline can switch to content-defined chunking without a client change.
