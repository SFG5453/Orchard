#!/bin/sh

set -eu

project_dir="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
output_dir="$project_dir/release-macos"
native_output="$project_dir/native/build/macos"
addon_path="$project_dir/native/build/Release/orchard_audio_analysis.node"
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/orchard-macos-cross.XXXXXX")"
had_host_addon=false

if [ -f "$addon_path" ]; then
  cp "$addon_path" "$temporary_dir/host-addon.node"
  had_host_addon=true
fi

cleanup() {
  if [ "$had_host_addon" = true ]; then
    mkdir -p "$(dirname -- "$addon_path")"
    cp "$temporary_dir/host-addon.node" "$addon_path"
  else
    rm -f "$addon_path"
  fi
  rm -rf "$temporary_dir"
}
trap cleanup EXIT HUP INT TERM

cd "$project_dir"
npm run build:frontend
ORCHARD_MACOS_NATIVE_OUTPUT="$native_output" npm run build:native:macos:cross

mkdir -p "$(dirname -- "$addon_path")"
cp "$native_output/orchard_audio_analysis-x86_64.node" "$addon_path"
CSC_IDENTITY_AUTO_DISCOVERY=false ./node_modules/.bin/electron-builder \
  --mac dir \
  --x64 \
  --config electron-builder.config.cjs \
  -c.directories.output="$temporary_dir/stage-x64" \
  --publish never

cp "$native_output/orchard_audio_analysis-arm64.node" "$addon_path"
CSC_IDENTITY_AUTO_DISCOVERY=false ./node_modules/.bin/electron-builder \
  --mac dir \
  --arm64 \
  --config electron-builder.config.cjs \
  -c.directories.output="$temporary_dir/stage-arm64" \
  --publish never

x64_app="$(find "$temporary_dir/stage-x64" -maxdepth 2 -type d -name 'Orchard.app' -print -quit)"
arm64_app="$(find "$temporary_dir/stage-arm64" -maxdepth 2 -type d -name 'Orchard.app' -print -quit)"
test -n "$x64_app"
test -n "$arm64_app"

mkdir -p "$temporary_dir/bin" "$temporary_dir/universal"
ln -s "$(command -v llvm-lipo)" "$temporary_dir/bin/lipo"
PATH="$temporary_dir/bin:$PATH" node scripts/merge-macos-apps.mjs \
  "$x64_app" \
  "$arm64_app" \
  "$temporary_dir/universal/Orchard.app"

main_binary="$temporary_dir/universal/Orchard.app/Contents/MacOS/Orchard"
addon_binary="$temporary_dir/universal/Orchard.app/Contents/Resources/app.asar.unpacked/native/build/Release/orchard_audio_analysis.node"
for binary in "$main_binary" "$addon_binary"; do
  architectures="$(llvm-lipo -archs "$binary")"
  printf '%s\n' "$architectures" | grep -qw x86_64
  printf '%s\n' "$architectures" | grep -qw arm64
  file "$binary"
done

rm -rf "$output_dir"
CSC_IDENTITY_AUTO_DISCOVERY=false ./node_modules/.bin/electron-builder \
  --prepackaged "$temporary_dir/universal/Orchard.app" \
  --mac zip \
  --universal \
  --config electron-builder.config.cjs \
  -c.directories.output="$output_dir" \
  --publish never

test -f "$output_dir/latest-mac.yml"
test -n "$(find "$output_dir" -maxdepth 1 -type f -name '*-mac-universal.zip' -print -quit)"
find "$output_dir" -maxdepth 1 -type f -print
