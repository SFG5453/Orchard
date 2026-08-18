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

# Cross-builds the system-media addon for Windows, reusing the llvm-mingw
# toolchain that scripts/build-native-windows.sh already caches.

set -eu

LLVM_MINGW_VERSION=20260616
TARGET=x86_64-pc-windows-gnullvm

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
crate_dir="$project_dir/native-media"
output_dir="$crate_dir/build"
cache_dir=${ORCHARD_NATIVE_WINDOWS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/orchard-native-windows}
toolchain_dir="$cache_dir/llvm-mingw-${LLVM_MINGW_VERSION}-ucrt"

if [ ! -x "$toolchain_dir/bin/x86_64-w64-mingw32-clang" ]; then
  echo 'llvm-mingw toolchain missing; run scripts/build-native-windows.sh first.' >&2
  exit 1
fi

rustup target add "$TARGET" >/dev/null

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/orchard-native-media-windows.XXXXXX")
trap 'rm -rf "$temporary_dir"' EXIT HUP INT TERM

# napi's own build script takes a windows-gnu branch that panics unless it finds
# a libnode.dll, because CARGO_CFG_TARGET_ENV is "gnu" for the gnullvm target.
# Nothing is ever linked from it: napi-sys' dyn-symbols feature (enabled in
# native-media/Cargo.toml) resolves every napi_* symbol at runtime through
# GetProcAddress on the host executable. The check below asserts that, by
# failing if libnode.dll shows up in the finished import table.
stub_dir="$temporary_dir/libnode"
mkdir -p "$stub_dir"
echo 'void __orchard_napi_stub(void) {}' > "$temporary_dir/stub.c"
"$toolchain_dir/bin/x86_64-w64-mingw32-clang" -shared -o "$stub_dir/libnode.dll" "$temporary_dir/stub.c"

# crt-static keeps libunwind.dll out of the import table, matching the constraint
# scripts/build-native-windows.sh enforces on the C++ addon.
LIBNODE_PATH="$stub_dir" \
CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER="$toolchain_dir/bin/x86_64-w64-mingw32-clang" \
CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_RUSTFLAGS="-C target-feature=+crt-static" \
  cargo build --release --target "$TARGET" --manifest-path "$crate_dir/Cargo.toml"

binary="$crate_dir/target/$TARGET/release/orchard_system_media.dll"
mkdir -p "$output_dir"
cp "$binary" "$output_dir/orchard-system-media-win32-x64.node"

file "$binary" | grep -Eq 'PE32\+.*\(DLL\).*x86-64'

imports=$("$toolchain_dir/bin/llvm-objdump" -p "$binary")

# napi-rs exports the entry point but imports no napi symbols, so the C++
# addon's "must import node.exe" check does not apply here; the export is the
# thing that has to be present.
printf '%s\n' "$imports" | grep -q 'napi_register_module_v1' || {
  echo 'Media addon is missing the napi_register_module_v1 export.' >&2
  exit 1
}

printf '%s\n' "$imports" | grep -q 'DLL Name: combase.dll' || {
  echo 'Media addon does not import combase.dll, so WinRT was stripped.' >&2
  exit 1
}

if printf '%s\n' "$imports" | grep -Eq 'DLL Name: (libc\+\+|libunwind)\.dll'; then
  echo 'Media addon unexpectedly depends on an LLVM runtime DLL.' >&2
  exit 1
fi

if printf '%s\n' "$imports" | grep -q 'DLL Name: libnode.dll'; then
  echo 'Media addon linked against the libnode stub; it must resolve napi dynamically.' >&2
  exit 1
fi

file "$output_dir/orchard-system-media-win32-x64.node"
