# Windows cross build (MSVC under Wine)

Builds `orchard.exe` on Linux with the real MSVC toolchain, Qt's official MSVC kit, and Wine.
The Windows Qt version follows the installed host Qt version; downloads land in `.cache/windows-msvc/` (git-ignored).

Host requirements: `wine`, `meson` 1.12+, `cmake`, `ninja`, `python3`, `7z`, `msiextract` (msitools), `cargo` with the
`x86_64-pc-windows-msvc` target, `uv` or `pipx`, and a host Qt 6 (default `/usr`,
override with `ORCHARD_QT_HOST_PATH`). `ORCHARD_QT_VERSION` may explicitly override the detected version.

```sh
scripts/windows-msvc/setup.sh --accept-msvc-license   # one time: Wine prefix, MSVC, Qt, host qjsc
scripts/windows-msvc/configure.sh                     # extra args go to meson setup
scripts/windows-msvc/build.sh                         # output: build-windows-msvc-meson/orchard.exe
scripts/package-windows-msvc.sh                       # 7z package
```

Run `setup.sh wine|msvc|qt|qjsc` to repeat a single step.
After changing Qt versions, use a fresh `ORCHARD_MSVC_BUILD_DIR`; Meson caches the old Qt paths.

## Overrides

Set any of these to reuse an existing install: `ORCHARD_WIN_TOOLS`, `ORCHARD_MSVC_BUILD_DIR`,
`ORCHARD_MSVC_VC_ROOT` (`.../VC`), `ORCHARD_WINSDK_ROOT` (`.../Windows Kits/10`),
`ORCHARD_MSVC_VERSION`, `ORCHARD_WINSDK_VERSION`, `ORCHARD_MSVC_QT_ROOT`, `WINEPREFIX`,
`ORCHARD_HOST_QJSC`, `ORCHARD_BUILD_JOBS`.

Packaging also needs `icuuc.dll` for Qt's deployment tools. Set `ORCHARD_WINE_SYSTEM32` to a
Windows System32 directory if the Wine prefix lacks it.
