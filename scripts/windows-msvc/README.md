# Windows cross build (MSVC under Wine)

Builds `orchard.exe` on Linux with the real MSVC toolchain, Qt's official MSVC kit, and Wine.
The Windows Qt kit is pinned to 6.11.2; downloads land in `.cache/windows-msvc/` (git-ignored).

Host requirements: `wine`, `meson` 1.12+, `cmake`, `ninja`, `python3`, `7z`, `msiextract` (msitools), `cargo` with the
`x86_64-pc-windows-msvc` target, and `uv` or `pipx`. The scripts use `/usr` only
when its Qt version matches the Windows kit. Otherwise, setup, configure, and build
download matching Linux Qt tools into `.cache/windows-msvc/host-qt/`.
`ORCHARD_QT_VERSION` may explicitly override the pinned version.

```sh
scripts/windows-msvc/setup.sh --accept-msvc-license   # one time: Wine prefix, MSVC, Qt, host qjsc
scripts/windows-msvc/configure.sh                     # extra args go to meson setup
scripts/windows-msvc/build.sh                         # output: build-windows-msvc-6.11.2/orchard.exe
scripts/package-windows-msvc.sh                       # 7z package
```

Run `setup.sh wine|msvc|qt|host-qt|qjsc` to repeat a single step.
The default build directory includes the Qt version; `build.sh` configures it
automatically on first use. Explicit `ORCHARD_MSVC_BUILD_DIR` overrides must use a
fresh directory after a Qt version change; Meson caches the Qt paths.
Set `ORCHARD_QT_HOST_PATH` to reuse a matching Linux SDK. Packaging verifies Qt imports in the staged
executables and plugins, including the Qt Serial Port dependency of the NMEA plugin.

## Overrides

Set any of these to reuse an existing install: `ORCHARD_WIN_TOOLS`, `ORCHARD_MSVC_BUILD_DIR`,
`ORCHARD_MSVC_VC_ROOT` (`.../VC`), `ORCHARD_WINSDK_ROOT` (`.../Windows Kits/10`),
`ORCHARD_MSVC_VERSION`, `ORCHARD_WINSDK_VERSION`, `ORCHARD_MSVC_QT_ROOT`, `WINEPREFIX`,
`ORCHARD_HOST_QJSC`, `ORCHARD_BUILD_JOBS`.

Packaging needs the matching x64 `icu.dll`, `icuuc.dll`, and `icuin.dll` files for Qt
and its deployment tools. Set `ORCHARD_WINE_SYSTEM32` to a Windows System32 directory
if the Wine prefix lacks them. All three DLLs are included in the portable archive;
Wine builtin DLLs cannot be used as portable Windows runtime files.
