#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root after a release build has staged adaptive mix.
build_dir="$(realpath "${1:?pass the Meson build directory}")"
qt_root="$(realpath "${2:?pass the Qt installation directory}")"
output_dir="$(realpath -m "${3:?pass an output directory}")"
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
appdir="$output_dir/AppDir"
mkdir -p "$output_dir" "$appdir/usr/bin" "$appdir/usr/share/orchard"

for name in orchard orchard-auth-helper orchard-widevine-host orchard-adaptive-mix libwebgpu_dawn.so libdiscord_partner_sdk.so libLiteRt.so; do
  cp "$build_dir/$name" "$appdir/usr/bin/$name"
done
cp -a "$build_dir/models" "$appdir/usr/bin/models"
cp "$source_dir/LICENSE" "$source_dir/THIRD_PARTY_NOTICES.md" "$appdir/usr/share/orchard/"
cp "$source_dir/vendor/discord_social_sdk/License-Notices.txt" "$appdir/usr/share/orchard/DiscordSocialSdk-Notices.txt"
cp "$source_dir/vendor/litert/LICENSE" "$appdir/usr/share/orchard/LiteRT-LICENSE.txt"
cp "$source_dir/third_party/chromium_cdm/LICENSE" "$appdir/usr/share/orchard/ChromiumCdm-LICENSE.txt"
mkdir -p "$appdir/usr/plugins/sqldrivers"
# This plugin is loaded by name, so an ELF dependency scan cannot see it.
cp "$qt_root/plugins/sqldrivers/libqsqlite.so" "$appdir/usr/plugins/sqldrivers/"

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
exec "$here/usr/bin/orchard" "$@"
EOF
chmod +x "$appdir/AppRun"

cat > "$output_dir/orchard.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Orchard
Exec=orchard %U
Icon=orchard
MimeType=x-scheme-handler/orchard;
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
export EXTRA_QT_MODULES='sql;multimedia'
export ARCH=x86_64
export APPIMAGE_EXTRACT_AND_RUN=1
cd "$output_dir"

# The sign-in helper uses the system WebKitGTK, whose child processes live at fixed
# system paths. It and the GTK and GLib it is built against stay unbundled.
system_excludes=()
for library in libwebkit2gtk-4.1 libjavascriptcoregtk-4.1 libsoup-3.0 libgtk-3 libgdk-3 \
  libglib-2.0 libgobject-2.0 libgio-2.0 libgmodule-2.0; do
  system_excludes+=(--exclude-library "$library.so*")
done

./linuxdeploy-x86_64.AppImage --appdir "$appdir" "${system_excludes[@]}" \
  -e "$(command -v ffmpeg)" -e "$(command -v ffprobe)" \
  -i "$output_dir/orchard.png" \
  -d "$output_dir/orchard.desktop" --plugin qt

test -f "$appdir/usr/bin/ffmpeg"
test -f "$appdir/usr/bin/ffprobe"
test -f "$appdir/usr/plugins/sqldrivers/libqsqlite.so"

./linuxdeploy-x86_64.AppImage --appdir "$appdir" "${system_excludes[@]}" --output appimage
[[ -z "$(find "$appdir/usr/lib" -name 'libwebkit2gtk*' -o -name 'libgtk-3*' -o -name 'libglib-2.0*')" ]] || { echo "Bundled WebKitGTK stack found in AppDir" >&2; exit 1; }
mapfile -t images < <(find "$output_dir" -maxdepth 1 -name '*.AppImage' ! -name 'linuxdeploy*' -print)
[[ ${#images[@]} -eq 1 ]] || { echo "Expected one AppImage, found ${#images[@]}" >&2; exit 1; }
mv "${images[0]}" "$output_dir/Orchard-Linux-x86_64.AppImage"
