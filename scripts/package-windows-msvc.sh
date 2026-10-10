#!/usr/bin/env bash
set -euo pipefail

# Package an already-built Windows/MSVC executable. This intentionally does
# not invoke Meson, Ninja, Cargo, or any other build command.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_dir="$(cd -- "$script_dir/.." && pwd)"
source "$script_dir/windows-msvc/env.sh"
build_dir="$ORCHARD_MSVC_BUILD_DIR"
qt_root="$ORCHARD_MSVC_QT_ROOT"
qt_bin="$qt_root/bin"
package_parent="$build_dir/package"
package_dir="$package_parent/Orchard"
archive="$build_dir/Orchard-Windows-x86_64-MSVC-Qt${ORCHARD_QT_VERSION}.7z"
executable="$build_dir/orchard.exe"
auth_helper_executable="$build_dir/orchard-auth-helper.exe"
# Adaptive crossfade and Best Mix worker, staged by Meson.
adaptive_files=(orchard-adaptive-mix.exe webgpu_dawn.dll dxil.dll dxcompiler.dll discord_partner_sdk.dll libLiteRt.dll ffmpeg.exe ffmpeg-LICENSE.txt)
wine_prefix="$WINEPREFIX"

die() {
    echo "package-windows-msvc: $*" >&2
    exit 1
}

require_file() {
    [[ -f "$1" ]] || die "required file not found: $1"
}

windows_path() {
    local path="$1"
    path="${path//\//\\}"
    echo "Z:$path"
}

command -v wine >/dev/null 2>&1 || die "wine is required to run Qt's windeployqt"
command -v 7z >/dev/null 2>&1 || die "7z is required to create and verify the archive"

require_file "$executable"
require_file "$auth_helper_executable"
python3 "$script_dir/windows-msvc/check-configured-qt.py" "$build_dir" "$qt_root"
for adaptive_file in "${adaptive_files[@]}"; do
    require_file "$build_dir/$adaptive_file"
done
[[ -d "$build_dir/models" ]] || die "adaptive mix models not found: $build_dir/models"
require_file "$qt_bin/windeployqt.exe"
require_file "$qt_bin/QtWebEngineProcess.exe"
require_file "$qt_root/plugins/webview/qtwebview_webengine.dll"
require_file "$qt_root/plugins/sqldrivers/qsqlite.dll"
[[ -d "$qt_root/qml" ]] || die "Qt QML directory not found: $qt_root/qml"

if [[ -n "${ORCHARD_WINE_SYSTEM32:-}" ]]; then
    wine_system32="$ORCHARD_WINE_SYSTEM32"
else
    wine_system32="$wine_prefix/drive_c/windows/system32"
fi
# Qt and its deployment tools import the Windows ICU runtime.
for icu_name in icu.dll icuuc.dll icuin.dll; do
    [[ -f "$wine_system32/$icu_name" ]] || die "$icu_name not found in $wine_system32; set ORCHARD_WINE_SYSTEM32 to a Windows System32 directory"
done

# Prefer the CRT already used by the previous package. This keeps the script
# usable even when the Visual Studio installation is only available through
# the existing build's Wine setup.
runtime_source_dir=""
runtime_names=(
    concrt140.dll
    msvcp140.dll
    msvcp140_1.dll
    msvcp140_2.dll
    msvcp140_atomic_wait.dll
    msvcp140_codecvt_ids.dll
    vccorlib140.dll
    vcruntime140.dll
    vcruntime140_1.dll
    vcruntime140_threads.dll
)
if [[ -d "$package_dir" ]]; then
    runtime_source_dir="$package_dir"
    for runtime_name in "${runtime_names[@]}"; do
        [[ -f "$runtime_source_dir/$runtime_name" ]] || runtime_source_dir=""
    done
fi

if [[ -z "$runtime_source_dir" && -n "${ORCHARD_MSVC_RUNTIME_DIR:-}" ]]; then
    runtime_source_dir="$ORCHARD_MSVC_RUNTIME_DIR"
fi

if [[ -z "$runtime_source_dir" ]]; then
    redist_root="$ORCHARD_MSVC_VC_ROOT/Redist/MSVC"
    if [[ -d "$redist_root" ]]; then
        runtime_source_dir="$(find "$redist_root" -type d -path '*/x64/Microsoft.VC*.CRT' -print | sort | tail -n 1)"
    fi
fi

[[ -n "$runtime_source_dir" ]] || die "MSVC runtime directory not found; set ORCHARD_MSVC_RUNTIME_DIR"
for runtime_name in "${runtime_names[@]}"; do
    require_file "$runtime_source_dir/$runtime_name"
done

mkdir -p "$package_parent"
deployment_dir="$(mktemp -d "$package_parent/.Orchard-deployment.XXXXXX")"

export WINEDEBUG="${WINEDEBUG:--all}"
export WINEPREFIX="$wine_prefix"
export PATH="$qt_bin:$PATH"
export WINEPATH="$(windows_path "$qt_bin");$(windows_path "$wine_system32")"
if [[ -d "$package_dir" ]]; then
    export WINEPATH="$WINEPATH;$(windows_path "$package_dir")"
fi

win_qml_dir="$(windows_path "$source_dir/app/qml")"
win_deployment_dir="$(windows_path "$deployment_dir")"
win_executable="$(windows_path "$executable")"
win_auth_helper_executable="$(windows_path "$auth_helper_executable")"

echo "Deploying Qt runtime for $executable"
# The auth helper selects its backend at runtime, so deploy it explicitly.
wine "$qt_bin/windeployqt.exe" \
    --release \
    --no-compiler-runtime \
    --include-plugins qtwebview_webengine \
    --exclude-plugins qtwebview_webview2 \
    --qmldir "$win_qml_dir" \
    --dir "$win_deployment_dir" \
    "$win_executable"

echo "Deploying Qt WebEngine runtime for $auth_helper_executable"
wine "$qt_bin/windeployqt.exe" \
    --release \
    --no-compiler-runtime \
    --include-plugins qtwebview_webengine \
    --exclude-plugins qtwebview_webview2 \
    --qmldir "$win_qml_dir" \
    --dir "$win_deployment_dir" \
    "$win_auth_helper_executable"

# windeployqt copies the executable, but copy it explicitly so the archive
# always contains exactly the build output selected above.
cp -p "$executable" "$deployment_dir/orchard.exe"
cp -p "$auth_helper_executable" "$deployment_dir/orchard-auth-helper.exe"
for adaptive_file in "${adaptive_files[@]}"; do
    cp -p "$build_dir/$adaptive_file" "$deployment_dir/$adaptive_file"
done
cp -p "$source_dir/vendor/discord_social_sdk/License-Notices.txt" "$deployment_dir/DiscordSocialSdk-Notices.txt"
cp -rp "$build_dir/models" "$deployment_dir/models"
mkdir -p "$deployment_dir/sqldrivers"
cp -p "$qt_root/plugins/sqldrivers/qsqlite.dll" "$deployment_dir/sqldrivers/qsqlite.dll"
for runtime_name in "${runtime_names[@]}"; do
    cp -p "$runtime_source_dir/$runtime_name" "$deployment_dir/$runtime_name"
done
python3 "$script_dir/windows_icu_runtime.py" --system32 "$wine_system32" --stage "$deployment_dir"

required_files=(
    orchard.exe
    icu.dll
    icuuc.dll
    icuin.dll
    orchard-auth-helper.exe
    orchard-adaptive-mix.exe
    webgpu_dawn.dll
    dxil.dll
    dxcompiler.dll
    ffmpeg.exe
    models/beat-this/beat_this_webgpu.onnx
    models/vocal-separation/vocals_umxhq_fp32.onnx
    models/docs-search/model_quantized.onnx
    models/docs-search/vocab.txt
    models/slop/fakeprint_lr.f32
    Qt6WebEngineCore.dll
    Qt6WebEngineQuick.dll
    QtWebEngineProcess.exe
    Qt6Multimedia.dll
    Qt6Sql.dll
    multimedia/ffmpegmediaplugin.dll
    multimedia/windowsmediaplugin.dll
    sqldrivers/qsqlite.dll
    platforms/qwindows.dll
    qml/QtWebView/qmldir
    qml/QtWebEngine/qmldir
    qml/QtWebEngine/qtwebenginequickplugin.dll
    webview/qtwebview_webengine.dll
    resources/icudtl.dat
    resources/qtwebengine_devtools_resources.pak
    resources/qtwebengine_resources.pak
    resources/qtwebengine_resources_100p.pak
    resources/qtwebengine_resources_200p.pak
    resources/v8_context_snapshot.bin
    translations/qtwebengine_locales/en-US.pak
)
for required_file in "${required_files[@]}"; do
    require_file "$deployment_dir/$required_file"
done
python3 "$script_dir/windows_qt_runtime.py" --stage "$deployment_dir"

# Keep the previous staging directory recoverable, then install the freshly
# deployed one under the stable path used by the archive and by developers.
previous_dir="$package_parent/Orchard.previous.$(date +%s).$$"
if [[ -d "$package_dir" ]]; then
    mv "$package_dir" "$previous_dir"
fi
mv "$deployment_dir" "$package_dir"

# LZMA2 keeps the bundled ffmpeg.exe under the 300 MB R2 dashboard upload limit.
# Deflate was invited, it just could not fit through the door.
archive_tmp="$build_dir/.Orchard-Windows-x86_64-MSVC-Qt${ORCHARD_QT_VERSION}.$$.7z"
(
    cd "$package_parent"
    7z a -t7z -m0=lzma2 -mx=9 -ms=on -mmt=on -bd -bso0 "$archive_tmp" Orchard
)
7z t -bd -bso0 "$archive_tmp"
mv "$archive_tmp" "$archive"

file_count="$(find "$package_dir" -type f | wc -l)"
archive_size="$(du -h "$archive" | awk '{print $1}')"
echo "Created $archive ($archive_size, $file_count staged files)"
echo "Previous staging preserved at $previous_dir"
