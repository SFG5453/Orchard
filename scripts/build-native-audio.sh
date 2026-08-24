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

# Builds the transition-engine addon for the host platform.

set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
crate_dir="$project_dir/native-audio-rust"
output_dir="$crate_dir/build"

command -v cargo >/dev/null || {
  echo 'cargo not found; install a Rust toolchain to build the transition addon.' >&2
  exit 1
}

case "$(uname -s)" in
  Linux) platform=linux; extension=so; prefix=lib ;;
  Darwin) platform=darwin; extension=dylib; prefix=lib ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    platform=win32
    extension=dll
    prefix=
    ;;
  *) echo "unsupported host platform: $(uname -s)" >&2; exit 1 ;;
esac

case "$(node -p process.arch)" in
  x64|x86_64|amd64) architecture=x64 ;;
  arm64|aarch64) architecture=arm64 ;;
  *) echo "unsupported host architecture: $(node -p process.arch)" >&2; exit 1 ;;
esac

if [ "$platform" = win32 ]; then
  case "$architecture" in
    x64) target=x86_64-pc-windows-msvc ;;
    arm64) target=aarch64-pc-windows-msvc ;;
  esac
  rustup target add "$target" >/dev/null
  cargo build --release --target "$target" --manifest-path "$crate_dir/Cargo.toml"
  binary="$crate_dir/target/$target/release/orchard_audio_transition.dll"
else
  cargo build --release --manifest-path "$crate_dir/Cargo.toml"
  binary="$crate_dir/target/release/${prefix}orchard_audio_transition.${extension}"
fi

mkdir -p "$output_dir"
cp "$binary" \
  "$output_dir/orchard-audio-transition-${platform}-${architecture}.node"

# The addon is useless if it cannot register with Node, and a silently
# entry-point-less binary would only surface as a confusing runtime failure.
(cd "$project_dir" && node -e "
const addon = require('./native-audio-rust/build/orchard-audio-transition-${platform}-${architecture}.node');
if (typeof addon.renderTransition !== 'function') {
  throw new Error('addon did not export renderTransition');
}
if (typeof addon.renderPlannedTransition !== 'function') {
  throw new Error('addon did not export renderPlannedTransition');
}
console.log('transition addon OK: orchard-audio-transition-${platform}-${architecture}.node');
")
