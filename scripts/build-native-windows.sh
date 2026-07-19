#!/bin/sh
set -eu

LLVM_MINGW_VERSION=20260616
LLVM_MINGW_SHA256=534b92e067b22a6b4441f48ae9240a3341b17825d04d577eab0cf85c44b4deda
LLVM_MINGW_ARCHIVE="llvm-mingw-${LLVM_MINGW_VERSION}-ucrt-ubuntu-22.04-x86_64.tar.xz"
LLVM_MINGW_URL="https://github.com/mstorsjo/llvm-mingw/releases/download/${LLVM_MINGW_VERSION}/${LLVM_MINGW_ARCHIVE}"

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cache_dir=${ORCHARD_NATIVE_WINDOWS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/orchard-native-windows}
toolchain_dir="$cache_dir/llvm-mingw-${LLVM_MINGW_VERSION}-ucrt"
electron_version=$(CDPATH= cd -- "$project_dir" && node -p "require('./node_modules/electron/package.json').version")
electron_dir="$cache_dir/electron-$electron_version"
electron_url="https://artifacts.electronjs.org/headers/dist/v${electron_version}"
headers_archive="node-v${electron_version}-headers.tar.gz"
headers_path="$electron_dir/$headers_archive"
node_lib_path="$electron_dir/win-x64/node.lib"
checksums_path="$electron_dir/SHASUMS256.txt"
output_path="$project_dir/native/build/Release/orchard_audio_analysis.node"

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/orchard-native-windows.XXXXXX")
trap 'rm -rf "$temporary_dir"' EXIT HUP INT TERM

download() {
  url=$1
  destination=$2
  if [ -f "$destination" ]; then return; fi
  mkdir -p "$(dirname -- "$destination")"
  curl -fL --retry 3 --silent --show-error --output "$temporary_dir/download" "$url"
  mv "$temporary_dir/download" "$destination"
}

verify_sha256() {
  expected=$1
  file=$2
  printf '%s  %s\n' "$expected" "$file" | sha256sum -c -
}

download "$LLVM_MINGW_URL" "$cache_dir/$LLVM_MINGW_ARCHIVE"
verify_sha256 "$LLVM_MINGW_SHA256" "$cache_dir/$LLVM_MINGW_ARCHIVE"

if [ ! -x "$toolchain_dir/bin/x86_64-w64-mingw32-clang++" ]; then
  mkdir -p "$temporary_dir/toolchain"
  tar -xJf "$cache_dir/$LLVM_MINGW_ARCHIVE" --strip-components=1 -C "$temporary_dir/toolchain"
  rm -rf "$toolchain_dir"
  mv "$temporary_dir/toolchain" "$toolchain_dir"
fi

download "$electron_url/SHASUMS256.txt" "$checksums_path"
headers_sha256=$(awk -v name="$headers_archive" '$2 == name { print $1; exit }' "$checksums_path")
node_lib_sha256=$(awk '$2 == "win-x64/node.lib" { print $1; exit }' "$checksums_path")
test -n "$headers_sha256"
test -n "$node_lib_sha256"

download "$electron_url/$headers_archive" "$headers_path"
download "$electron_url/win-x64/node.lib" "$node_lib_path"
verify_sha256 "$headers_sha256" "$headers_path"
verify_sha256 "$node_lib_sha256" "$node_lib_path"

if [ ! -f "$electron_dir/include/node/node_api.h" ]; then
  mkdir -p "$temporary_dir/headers"
  tar -xzf "$headers_path" --strip-components=1 -C "$temporary_dir/headers"
  rm -rf "$electron_dir/include"
  mv "$temporary_dir/headers/include" "$electron_dir/include"
fi

mkdir -p "$(dirname -- "$output_path")"
"$toolchain_dir/bin/x86_64-w64-mingw32-clang++" \
  -std=c++17 \
  -O3 \
  -shared \
  -static \
  -DNAPI_DISABLE_CPP_EXCEPTIONS \
  -DBUILDING_NODE_EXTENSION \
  -I "$project_dir/node_modules/node-addon-api" \
  -I "$electron_dir/include/node" \
  "$project_dir/native/binding/addon.cpp" \
  "$project_dir/native/analyzer/audio_analysis.cpp" \
  "$project_dir/native/analyzer/tempo_analysis.cpp" \
  "$node_lib_path" \
  -o "$output_path"

file "$output_path" | grep -Eq 'PE32\+.*\(DLL\).*x86-64'
imports=$("$toolchain_dir/bin/llvm-objdump" -p "$output_path")
printf '%s\n' "$imports" | grep -q 'DLL Name: node.exe'
printf '%s\n' "$imports" | grep -q 'napi_register_module_v1'
if printf '%s\n' "$imports" | grep -Eq 'DLL Name: (libc\+\+|libunwind)\.dll'; then
  echo 'Windows addon unexpectedly depends on an LLVM runtime DLL.' >&2
  exit 1
fi

file "$output_path"
