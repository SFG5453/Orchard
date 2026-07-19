#!/bin/sh

set -eu

MACOS_SDK_VERSION=15.5
MACOS_SDK_SHA256=5096f730e9935adbd99d4bb082c3c6c01c077d966405ee4ae45705521476332e
OSXCROSS_COMMIT=eae02eaf16c32c401afbe60b024e8ee3f5bd8b59
OSXCROSS_SHA256=a36e4ceb6eabff52b91bc1066ed1167d6a9f252aee2c6b469c03269a8c2b8bed
OSXCROSS_TARGET=darwin24.5
MACOS_DEPLOYMENT_TARGET=12.0

project_dir="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cache_root="${ORCHARD_MACOS_CROSS_CACHE:-${XDG_CACHE_HOME:-${HOME:?}/.cache}/orchard-macos-cross}"
toolchain_dir="$cache_root/osxcross-${OSXCROSS_COMMIT}-${MACOS_SDK_VERSION}-macos${MACOS_DEPLOYMENT_TARGET}"
sdk_archive="$cache_root/MacOSX${MACOS_SDK_VERSION}.tar.xz"
osxcross_archive="$cache_root/osxcross-${OSXCROSS_COMMIT}.tar.gz"
native_output="${ORCHARD_MACOS_NATIVE_OUTPUT:-$project_dir/native/build/macos}"
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/orchard-native-macos-cross.XXXXXX")"

cleanup() {
  rm -rf "$temporary_dir"
}
trap cleanup EXIT HUP INT TERM

download() {
  url=$1
  destination=$2
  if [ -f "$destination" ]; then return; fi
  mkdir -p "$(dirname -- "$destination")"
  curl --fail --location --retry 3 --silent --show-error \
    --output "$temporary_dir/download" "$url"
  mv "$temporary_dir/download" "$destination"
}

verify_sha256() {
  expected=$1
  file=$2
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check -
}

for tool in clang clang++ curl file ld64.lld llvm-lipo make patch sha256sum tar xz; do
  command -v "$tool" >/dev/null
done

download \
  "https://github.com/alexey-lysiuk/macos-sdk/releases/download/${MACOS_SDK_VERSION}/MacOSX${MACOS_SDK_VERSION}.tar.xz" \
  "$sdk_archive"
download \
  "https://codeload.github.com/tpoechtrager/osxcross/tar.gz/${OSXCROSS_COMMIT}" \
  "$osxcross_archive"
verify_sha256 "$MACOS_SDK_SHA256" "$sdk_archive"
verify_sha256 "$OSXCROSS_SHA256" "$osxcross_archive"

if [ ! -x "$toolchain_dir/bin/x86_64-apple-${OSXCROSS_TARGET}-clang++" ] || \
   [ ! -x "$toolchain_dir/bin/arm64-apple-${OSXCROSS_TARGET}-clang++" ]; then
  mkdir -p "$temporary_dir/osxcross" "$temporary_dir/osxcross/tarballs"
  tar -xzf "$osxcross_archive" --strip-components=1 -C "$temporary_dir/osxcross"
  ln -s "$sdk_archive" "$temporary_dir/osxcross/tarballs/MacOSX${MACOS_SDK_VERSION}.tar.xz"
  PATH="/usr/local/sbin:/usr/local/bin:/usr/bin:/bin" \
  TARGET_DIR="$temporary_dir/toolchain" \
  ENABLE_ARCHS="arm64 x86_64" \
  OSX_VERSION_MIN="$MACOS_DEPLOYMENT_TARGET" \
  SKIP_BUILD=cctools-port \
  UNATTENDED=1 \
    "$temporary_dir/osxcross/build.sh"
  rm -rf "$toolchain_dir"
  mkdir -p "$(dirname -- "$toolchain_dir")"
  mv "$temporary_dir/toolchain" "$toolchain_dir"
fi

electron_version="$(CDPATH= cd -- "$project_dir" && node -p "require('./node_modules/electron/package.json').version")"
electron_dir="$cache_root/electron-$electron_version"
headers_archive="node-v${electron_version}-headers.tar.gz"
headers_path="$electron_dir/$headers_archive"
checksums_path="$electron_dir/SHASUMS256.txt"

download "https://artifacts.electronjs.org/headers/dist/v${electron_version}/SHASUMS256.txt" "$checksums_path"
headers_sha256="$(awk -v name="$headers_archive" '$2 == name { print $1; exit }' "$checksums_path")"
test -n "$headers_sha256"
download "https://artifacts.electronjs.org/headers/dist/v${electron_version}/$headers_archive" "$headers_path"
verify_sha256 "$headers_sha256" "$headers_path"

if [ ! -f "$electron_dir/include/node/node_api.h" ]; then
  mkdir -p "$temporary_dir/headers"
  tar -xzf "$headers_path" --strip-components=1 -C "$temporary_dir/headers"
  rm -rf "$electron_dir/include"
  mv "$temporary_dir/headers/include" "$electron_dir/include"
fi

mkdir -p "$native_output"
for architecture in x86_64 arm64; do
  output_path="$temporary_dir/orchard_audio_analysis-${architecture}.node"
  "$toolchain_dir/bin/${architecture}-apple-${OSXCROSS_TARGET}-clang++" \
    -std=c++17 \
    -stdlib=libc++ \
    -O3 \
    -bundle \
    -undefined dynamic_lookup \
    -DNAPI_DISABLE_CPP_EXCEPTIONS \
    -DBUILDING_NODE_EXTENSION \
    -I "$project_dir/node_modules/node-addon-api" \
    -I "$electron_dir/include/node" \
    "$project_dir/native/binding/addon.cpp" \
    "$project_dir/native/analyzer/audio_analysis.cpp" \
    "$project_dir/native/analyzer/tempo_analysis.cpp" \
    -o "$output_path"
  file "$output_path" | grep -q "Mach-O 64-bit ${architecture} bundle"
  mv "$output_path" "$native_output/orchard_audio_analysis-${architecture}.node"
done

file "$native_output"/*.node
