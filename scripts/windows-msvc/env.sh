# Shared settings for the Windows/MSVC cross build. Source, do not execute.
# Every path can be overridden from the environment.

orchard_win_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
orchard_source_dir="$(cd -- "$orchard_win_dir/../.." && pwd)"

# Match the host tools that compile QML. A different patch version makes Qt
# greet the compiled QML with "who are you?" instead of loading it.
: "${ORCHARD_QT_HOST_PATH:=/usr}"
orchard_host_qt_config="$ORCHARD_QT_HOST_PATH/lib/cmake/Qt6Core/Qt6CoreConfigVersionImpl.cmake"
[[ -f "$orchard_host_qt_config" ]] || {
  echo "windows-msvc: host Qt 6 not found under $ORCHARD_QT_HOST_PATH (set ORCHARD_QT_HOST_PATH)" >&2
  exit 1
}
orchard_host_qt_version="$(sed -n 's/^set(PACKAGE_VERSION "\([0-9][0-9.]*\)")/\1/p' "$orchard_host_qt_config" | head -n 1)"
[[ -n "$orchard_host_qt_version" ]] || {
  echo "windows-msvc: could not read host Qt version from $orchard_host_qt_config" >&2
  exit 1
}
: "${ORCHARD_QT_VERSION:=$orchard_host_qt_version}"
[[ "$ORCHARD_QT_VERSION" == "$orchard_host_qt_version" ]] || {
  echo "windows-msvc: host Qt $orchard_host_qt_version differs from target Qt $ORCHARD_QT_VERSION" >&2
  exit 1
}

# Other pinned inputs (override only to test a new toolchain).
: "${ORCHARD_QT_ARCH:=win64_msvc2022_64}"
: "${ORCHARD_QT_MODULES:=qtmultimedia qtshadertools qtwebengine qtwebview qtwebchannel qtpositioning qtwebsockets}"
# Released aqtinstall lacks the Qt 6.11 repository layout; pin a git commit.
: "${ORCHARD_AQT_SPEC:=git+https://github.com/miurahr/aqtinstall@076e1659807d0b362a3ed684d54c2e9c775eb9c7}"
: "${ORCHARD_MSVC_WINE_REV:=514f8ea34842cd6d831804d0e9658d3a32870ae1}"
# Empty selects the newest toolset in the VS channel (14.51+, needed by the prebuilt ONNX Runtime).
: "${ORCHARD_MSVC_VS_VERSION:=}"
: "${ORCHARD_VS_MAJOR:=18}"
: "${ORCHARD_WINSDK_BUILD:=10.0.26100}"

# Downloaded toolchains live here; the directory is git-ignored (/.cache/).
: "${ORCHARD_WIN_TOOLS:=$orchard_source_dir/.cache/windows-msvc}"
: "${ORCHARD_MSVC_BUILD_DIR:=$orchard_source_dir/build-windows-msvc-meson}"
: "${ORCHARD_MSVC_ROOT:=$ORCHARD_WIN_TOOLS/msvc}"
: "${ORCHARD_MSVC_VC_ROOT:=$ORCHARD_MSVC_ROOT/VC}"
: "${ORCHARD_WINSDK_ROOT:=$ORCHARD_MSVC_ROOT/Windows Kits/10}"
: "${ORCHARD_MSVC_QT_ROOT:=$ORCHARD_WIN_TOOLS/qt/$ORCHARD_QT_VERSION/msvc2022_64}"
: "${WINEPREFIX:=$ORCHARD_WIN_TOOLS/wine-prefix}"
: "${ORCHARD_HOST_QJSC:=$ORCHARD_WIN_TOOLS/host-qjsc/qjsc}"
: "${ORCHARD_BUILD_JOBS:=$(nproc)}"

export ORCHARD_MSVC_VC_ROOT ORCHARD_WINSDK_ROOT WINEPREFIX
export WINEDEBUG="${WINEDEBUG:--all}"

orchard_die() {
    echo "${orchard_script:-windows-msvc}: $*" >&2
    exit 1
}

orchard_need_cmd() {
    command -v "$1" >/dev/null 2>&1 || orchard_die "missing host tool: $1 ($2)"
}
