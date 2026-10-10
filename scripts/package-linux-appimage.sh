#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root after a release build has staged adaptive mix.
build_dir="$(realpath "${1:?pass the Meson build directory}")"
qt_root="$(realpath "${2:?pass the Qt installation directory}")"
output_dir="$(realpath -m "${3:?pass an output directory}")"
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
appdir="$output_dir/AppDir"
mkdir -p "$output_dir" "$appdir/usr/bin" "$appdir/usr/share/orchard"

for name in orchard orchard-auth-helper orchard-adaptive-mix libwebgpu_dawn.so libdiscord_partner_sdk.so libLiteRt.so; do
  cp "$build_dir/$name" "$appdir/usr/bin/$name"
done
cp -a "$build_dir/models" "$appdir/usr/bin/models"
cp "$source_dir/LICENSE" "$source_dir/THIRD_PARTY_NOTICES.md" "$appdir/usr/share/orchard/"
cp "$source_dir/vendor/discord_social_sdk/License-Notices.txt" "$appdir/usr/share/orchard/DiscordSocialSdk-Notices.txt"
cp "$source_dir/vendor/litert/LICENSE" "$appdir/usr/share/orchard/LiteRT-LICENSE.txt"
mkdir -p "$appdir/usr/plugins/webview" "$appdir/usr/plugins/sqldrivers"
# These plugins are loaded by name, so an ELF dependency scan cannot see them.
cp "$qt_root/plugins/webview/libqtwebview_webengine.so" "$appdir/usr/plugins/webview/"
cp "$qt_root/plugins/sqldrivers/libqsqlite.so" "$appdir/usr/plugins/sqldrivers/"
mkdir -p "$appdir/usr/qml"
cp -a "$qt_root/qml/QtWebEngine" "$qt_root/qml/QtWebView" "$appdir/usr/qml/"

# The Qt deploy plugin scans whole plugin directories, including unused drivers.
# Mimer and NMEA can wait backstage instead of demanding client libraries.
plugin_stash="$(mktemp -d "$output_dir/unused-qt-plugins.XXXXXX")"
hidden_plugins=()
hide_qt_plugin() {
  local plugin="$1"
  [[ -e "$plugin" || -L "$plugin" ]] || return 0
  mv -- "$plugin" "$plugin_stash/${#hidden_plugins[@]}"
  hidden_plugins+=("$plugin")
}
restore_qt_plugins() {
  local index
  for index in "${!hidden_plugins[@]}"; do
    mv -- "$plugin_stash/$index" "${hidden_plugins[$index]}"
  done
  rmdir -- "$plugin_stash"
}
trap restore_qt_plugins EXIT
for driver in "$qt_root/plugins/sqldrivers"/libqsql*.so; do
  [[ "$driver" == "$qt_root/plugins/sqldrivers/libqsqlite.so" ]] && continue
  hide_qt_plugin "$driver"
done
hide_qt_plugin "$qt_root/plugins/position/libqtposition_nmea.so"

cat > "$appdir/AppRun" <<'EOF'
#!/usr/bin/env bash
set -e
here="$(dirname "$(readlink -f "$0")")"
export PATH="$here/usr/bin:$PATH"
export QTWEBENGINEPROCESS_PATH="$here/usr/bin/QtWebEngineProcess"
export QTWEBENGINE_RESOURCES_PATH="$here/usr/resources"
export QTWEBENGINE_LOCALES_PATH="$here/usr/translations/qtwebengine_locales"
exec "$here/usr/bin/orchard" "$@"
EOF
chmod +x "$appdir/AppRun"

cat > "$output_dir/orchard.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Orchard
Exec=orchard
Icon=orchard
Categories=AudioVideo;Audio;Player;
EOF
cp "$source_dir/app/qml/assets/orchard-logo.png" "$output_dir/orchard.png"

curl -fL "https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-x86_64.AppImage" -o "$output_dir/linuxdeploy-x86_64.AppImage"
curl -fL "https://github.com/linuxdeploy/linuxdeploy-plugin-qt/releases/download/continuous/linuxdeploy-plugin-qt-x86_64.AppImage" -o "$output_dir/linuxdeploy-plugin-qt-x86_64.AppImage"
chmod +x "$output_dir"/linuxdeploy*.AppImage

export PATH="$qt_root/bin:$PATH"
export LD_LIBRARY_PATH="$qt_root/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export QMAKE="$qt_root/bin/qmake6"
export QML_SOURCES_PATHS="$source_dir/app/qml"
export EXTRA_QT_MODULES='sql;multimedia;webenginecore;webchannel'
export ARCH=x86_64
export APPIMAGE_EXTRACT_AND_RUN=1
cd "$output_dir"

# linuxdeploy scans both Orchard executables and the WebEngine child process.
./linuxdeploy-x86_64.AppImage --appdir "$appdir" \
  -e "$qt_root/libexec/QtWebEngineProcess" \
  -e "$(command -v ffmpeg)" -e "$(command -v ffprobe)" \
  -i "$output_dir/orchard.png" \
  -d "$output_dir/orchard.desktop" --plugin qt

# Chromium dlopens the host libsoftokn3, which must match the NSS core libraries.
rm -f "$appdir"/usr/lib/libnss3.so "$appdir"/usr/lib/libnssutil3.so "$appdir"/usr/lib/libsmime3.so

mkdir -p "$appdir/usr/resources" "$appdir/usr/translations/qtwebengine_locales"
cp -a "$qt_root/resources/." "$appdir/usr/resources/"
cp -a "$qt_root/translations/qtwebengine_locales/." "$appdir/usr/translations/qtwebengine_locales/"
test -f "$appdir/usr/bin/QtWebEngineProcess"
test -f "$appdir/usr/bin/ffmpeg"
test -f "$appdir/usr/bin/ffprobe"
test -f "$appdir/usr/resources/icudtl.dat"
test -f "$appdir/usr/translations/qtwebengine_locales/en-US.pak"
test -f "$appdir/usr/plugins/webview/libqtwebview_webengine.so"
test -f "$appdir/usr/plugins/sqldrivers/libqsqlite.so"
test -f "$appdir/usr/qml/QtWebEngine/libqtwebenginequickplugin.so"

./linuxdeploy-x86_64.AppImage --appdir "$appdir" --output appimage
mapfile -t images < <(find "$output_dir" -maxdepth 1 -name '*.AppImage' ! -name 'linuxdeploy*' -print)
[[ ${#images[@]} -eq 1 ]] || { echo "Expected one AppImage, found ${#images[@]}" >&2; exit 1; }
mv "${images[0]}" "$output_dir/Orchard-Linux-x86_64.AppImage"
