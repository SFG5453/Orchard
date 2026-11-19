#!/usr/bin/env bash
set -euo pipefail

# Run from the repository root after a release build has staged adaptive mix.
build_dir="$(realpath "${1:?pass the Meson build directory}")"
qt_root="$(realpath "${2:?pass the Qt installation directory}")"
output_dir="$(realpath -m "${3:?pass an output directory}")"
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
appdir="$output_dir/AppDir"
mkdir -p "$output_dir" "$appdir/usr/bin" "$appdir/usr/share/orchard"

for name in orchard orchard-auth-helper orchard-widevine-host orchard-adaptive-mix libwebgpu_dawn.so libdiscord_partner_sdk.so orchard-discord.so libLiteRt.so; do
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
export LD_LIBRARY_PATH="$here/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export ORCHARD_WEBKIT_ROOT="$here/usr"
export GSETTINGS_SCHEMA_DIR="$here/usr/share/glib-2.0/schemas"
export GIO_EXTRA_MODULES="$here/usr/lib/gio/modules${GIO_EXTRA_MODULES:+:$GIO_EXTRA_MODULES}"
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

gio_tls="$(pkg-config --variable=giomoduledir gio-2.0)/libgiognutls.so"
test -f "$gio_tls"
pixbuf_dir="$(pkg-config --variable=gdk_pixbuf_moduledir gdk-pixbuf-2.0)"
pixbuf_query="$(pkg-config --variable=gdk_pixbuf_query_loaders gdk-pixbuf-2.0)"
test -d "$pixbuf_dir"
test -x "$pixbuf_query"
pixbuf_loaders=("$pixbuf_dir"/*.so)
pixbuf_args=()
for loader in "${pixbuf_loaders[@]}"; do
  pixbuf_args+=(-l "$loader")
done

./linuxdeploy-x86_64.AppImage --appdir "$appdir" \
  -e "$(command -v ffmpeg)" -e "$(command -v ffprobe)" \
  -l "$gio_tls" "${pixbuf_args[@]}" \
  -i "$output_dir/orchard.png" \
  -d "$output_dir/orchard.desktop" --plugin qt

test -f "$appdir/usr/bin/ffmpeg"
test -f "$appdir/usr/bin/ffprobe"
test -f "$appdir/usr/plugins/sqldrivers/libqsqlite.so"
test -f "$appdir/usr/lib/libgdk_pixbuf-2.0.so.0"
test -f "$appdir/usr/lib/librsvg-2.so.2"
compgen -G "$appdir/usr/lib/libwebkit2gtk-4.1.so*" > /dev/null
compgen -G "$appdir/usr/lib/libjavascriptcoregtk-4.1.so*" > /dev/null
dependencies="$(LD_LIBRARY_PATH="$appdir/usr/lib" ldd "$appdir/usr/bin/orchard-auth-helper" 2>&1)" || {
  printf '%s\n' "$dependencies" >&2
  exit 1
}
if ! awk -v lib="$appdir/usr/lib/" '
  /not found/ { bad = 1 }
  $1 ~ /^libQt6/ && index($0, "=> " lib) == 0 { bad = 1 }
  $1 == "libQt6Core.so.6" { core = 1 }
  END { exit (bad || !core) }
' <<< "$dependencies"; then
  printf 'Orchard auth helper needs the packaged Qt runtime:\n%s\n' "$dependencies" >&2
  exit 1
fi

pixbuf_bundle="lib/gdk-pixbuf-2.0"
mkdir -p "$appdir/usr/$pixbuf_bundle/loaders"
for loader in "${pixbuf_loaders[@]}"; do
  mv "$appdir/usr/lib/${loader##*/}" "$appdir/usr/$pixbuf_bundle/loaders/"
done
(
  cd "$appdir/usr"
  export LD_LIBRARY_PATH="$PWD/lib"
  # The helper uses this runtime root as its working directory.
  "$pixbuf_query" "$PWD/$pixbuf_bundle"/loaders/*.so |
    sed -E 's|^".*/(lib/gdk-pixbuf-2\.0/loaders/[^"/]+\.so)"$|"./\1"|' > "$pixbuf_bundle/loaders.cache"
  if ! grep -Eq '^"\./lib/gdk-pixbuf-2\.0/loaders/libpixbufloader[-_]svg\.so"$' "$pixbuf_bundle/loaders.cache"; then
    echo "The packaged GdkPixbuf cache is missing the SVG loader" >&2
    exit 1
  fi
  GDK_PIXBUF_MODULE_FILE="$PWD/$pixbuf_bundle/loaders.cache" \
    gdk-pixbuf-thumbnailer "$source_dir/app/qml/assets/lucide/circle-alert.svg" "$output_dir/pixbuf-smoke.png"
  rm "$output_dir/pixbuf-smoke.png"
)

mkdir -p "$appdir/usr/lib/gio/modules"
cp "$gio_tls" "$appdir/usr/lib/gio/modules/"
gio-querymodules "$appdir/usr/lib/gio/modules"
test -s "$appdir/usr/lib/gio/modules/giomodule.cache"
python3 "$source_dir/scripts/stage-webkit-appimage.py" "$appdir"
mkdir -p "$appdir/usr/share/glib-2.0"
cp -a /usr/share/glib-2.0/schemas "$appdir/usr/share/glib-2.0/"
glib-compile-schemas "$appdir/usr/share/glib-2.0/schemas"

./linuxdeploy-x86_64.AppImage --appdir "$appdir" --output appimage
mapfile -t images < <(find "$output_dir" -maxdepth 1 -name '*.AppImage' ! -name 'linuxdeploy*' -print)
[[ ${#images[@]} -eq 1 ]] || { echo "Expected one AppImage, found ${#images[@]}" >&2; exit 1; }
mv "${images[0]}" "$output_dir/Orchard-Linux-x86_64.AppImage"
