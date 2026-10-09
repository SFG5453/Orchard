#!/usr/bin/env bash
# Fetch and prepare everything the Windows/MSVC cross build needs, without touching system state.
# Usage: setup.sh [--accept-msvc-license] [wine] [msvc] [qt] [host-qt] [qjsc]
set -euo pipefail
orchard_script=setup
source "$(dirname -- "${BASH_SOURCE[0]}")/env.sh"

steps=()
for arg in "$@"; do
  case "$arg" in
    --accept-msvc-license) ORCHARD_ACCEPT_MSVC_LICENSE=1 ;;
    wine|msvc|qt|host-qt|qjsc) steps+=("$arg") ;;
    *) orchard_die "unknown argument: $arg" ;;
  esac
done
[[ ${#steps[@]} -gt 0 ]] || steps=(wine msvc qt qjsc)

orchard_need_cmd cmake "cmake"
orchard_need_cmd meson "Meson 1.12 or newer"
orchard_need_cmd ninja "ninja"
orchard_need_cmd wine "needed to run cl.exe, link.exe and windeployqt"
orchard_need_cmd python3 "python 3"
orchard_need_cmd cargo "rustup"
orchard_need_cmd 7z "p7zip, used for packaging"
orchard_need_cmd git "git, used to fetch aqtinstall"
rustup target list --installed 2>/dev/null | grep -qx x86_64-pc-windows-msvc ||
  orchard_die "missing Rust target; run: rustup target add x86_64-pc-windows-msvc"

mkdir -p "$ORCHARD_WIN_TOOLS"

setup_wine() {
  [[ -f "$WINEPREFIX/system.reg" ]] && return
  echo "Creating Wine prefix at $WINEPREFIX"
  WINEARCH=win64 wineboot --init >/dev/null 2>&1
}

setup_msvc() {
  if [[ -f "$ORCHARD_MSVC_VC_ROOT/Auxiliary/Build/vcvarsall.bat" || -d "$ORCHARD_MSVC_VC_ROOT/Tools/MSVC" ]]; then
    echo "MSVC already present: $ORCHARD_MSVC_VC_ROOT"
    return
  fi
  [[ "${ORCHARD_ACCEPT_MSVC_LICENSE:-0}" == 1 ]] ||
    orchard_die "MSVC Build Tools need license acceptance (https://go.microsoft.com/fwlink/?LinkId=2179911); rerun with --accept-msvc-license"
  orchard_need_cmd curl "curl"
  orchard_need_cmd msiextract "msitools, used to unpack the Windows SDK"
  # github.com tarball: raw.githubusercontent.com is blocked on some networks.
  local msvc_wine="$ORCHARD_WIN_TOOLS/msvc-wine-$ORCHARD_MSVC_WINE_REV"
  if [[ ! -f "$msvc_wine/vsdownload.py" ]]; then
    mkdir -p "$msvc_wine"
    curl -fsSL "https://github.com/mstorsjo/msvc-wine/archive/$ORCHARD_MSVC_WINE_REV.tar.gz" |
      tar -xz --strip-components=1 -C "$msvc_wine"
  fi
  python3 "$msvc_wine/vsdownload.py" --accept-license --dest "$ORCHARD_MSVC_ROOT" \
    --cache "$ORCHARD_WIN_TOOLS/downloads" --major "$ORCHARD_VS_MAJOR" \
    ${ORCHARD_MSVC_VS_VERSION:+--msvc-version "$ORCHARD_MSVC_VS_VERSION"} \
    --sdk-version "$ORCHARD_WINSDK_BUILD" --architecture x64
}

setup_qt() {
  if [[ -f "$ORCHARD_MSVC_QT_ROOT/lib/cmake/Qt6/Qt6Config.cmake" ]]; then
    [[ -f "$ORCHARD_MSVC_QT_ROOT/bin/Qt6SerialPort.dll" ]] ||
      orchard_die "Qt Serial Port missing; install qtserialport into $ORCHARD_MSVC_QT_ROOT"
    [[ -f "$ORCHARD_MSVC_QT_ROOT/plugins/webview/qtwebview_webengine.dll" ]] ||
      orchard_die "QtWebView WebEngine plugin missing from $ORCHARD_MSVC_QT_ROOT"
    echo "Qt already present: $ORCHARD_MSVC_QT_ROOT"
    return
  fi
  # aqt writes <out>/<version>/msvc2022_64.
  # shellcheck disable=SC2086
  # aqt writes aqtinstall.log into the working directory.
  (cd "$ORCHARD_WIN_TOOLS" && orchard_aqt install-qt windows desktop "$ORCHARD_QT_VERSION" "$ORCHARD_QT_ARCH" \
    -m $ORCHARD_QT_MODULES -O "$ORCHARD_WIN_TOOLS/qt")
}

setup_qjsc() {
  [[ -x "$ORCHARD_HOST_QJSC" ]] && return
  local dir
  dir="$(dirname -- "$ORCHARD_HOST_QJSC")"
  cmake -S "$orchard_source_dir/third_party/quickjs" -B "$dir" -G Ninja -DCMAKE_BUILD_TYPE=Release
  cmake --build "$dir" --target qjsc
  [[ -x "$ORCHARD_HOST_QJSC" ]] || orchard_die "qjsc was not produced at $ORCHARD_HOST_QJSC"
}

for step in "${steps[@]}"; do
  if [[ "$step" == host-qt ]]; then
    orchard_ensure_host_qt
  else
    "setup_$step"
    [[ "$step" != qt ]] || orchard_ensure_host_qt
  fi
done
echo "Windows toolchain ready under $ORCHARD_WIN_TOOLS"
