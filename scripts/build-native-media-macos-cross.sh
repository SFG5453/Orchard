#!/bin/sh
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

# Cross-builds the system-media addon for macOS, reusing the osxcross toolchain
# that scripts/build-native-macos-cross.sh already builds and caches.

set -eu

MACOS_SDK_VERSION=15.5
OSXCROSS_COMMIT=eae02eaf16c32c401afbe60b024e8ee3f5bd8b59
OSXCROSS_TARGET=darwin24.5
MACOS_DEPLOYMENT_TARGET=12.0

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
crate_dir="$project_dir/native-media"
output_dir="$crate_dir/build"
cache_root="${ORCHARD_MACOS_CROSS_CACHE:-${XDG_CACHE_HOME:-${HOME:?}/.cache}/orchard-macos-cross}"
toolchain_dir="$cache_root/osxcross-${OSXCROSS_COMMIT}-${MACOS_SDK_VERSION}-macos${MACOS_DEPLOYMENT_TARGET}"

if [ ! -x "$toolchain_dir/bin/arm64-apple-${OSXCROSS_TARGET}-clang" ]; then
  echo 'osxcross toolchain missing; run scripts/build-native-macos-cross.sh first.' >&2
  exit 1
fi

mkdir -p "$output_dir"

for architecture in x86_64 arm64; do
  case "$architecture" in
    x86_64) target=x86_64-apple-darwin; node_arch=x64 ;;
    arm64) target=aarch64-apple-darwin; node_arch=arm64 ;;
  esac

  rustup target add "$target" >/dev/null

  linker="$toolchain_dir/bin/${architecture}-apple-${OSXCROSS_TARGET}-clang"
  target_env=$(echo "$target" | tr '[:lower:]-' '[:upper:]_')

  # -undefined dynamic_lookup comes from napi-build's macOS branch, which
  # native-media/build.rs reaches because it keys off CARGO_CFG_TARGET_OS rather
  # than a host-evaluated cfg!. MediaPlayer.framework is linked there too, for
  # the same reason -- souvlaki's own build.rs gates that on
  # #[cfg(target_os = "macos")] and so silently omits it on every cross-build.
  env \
    "CARGO_TARGET_${target_env}_LINKER=$linker" \
    "CC_${target}=$linker" \
    "MACOSX_DEPLOYMENT_TARGET=$MACOS_DEPLOYMENT_TARGET" \
    cargo build --release --target "$target" --manifest-path "$crate_dir/Cargo.toml"

  binary="$crate_dir/target/$target/release/liborchard_system_media.dylib"
  file "$binary" | grep -q "Mach-O 64-bit dynamically linked shared library ${architecture}"
  cp "$binary" "$output_dir/orchard-system-media-darwin-${node_arch}.node"
done

file "$output_dir"/orchard-system-media-darwin-*.node
