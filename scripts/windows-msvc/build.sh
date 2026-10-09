#!/usr/bin/env bash
set -euo pipefail
orchard_script=build
source "$(dirname -- "${BASH_SOURCE[0]}")/env.sh"

export ORCHARD_MSVC_QT_ROOT ORCHARD_QT_HOST_PATH
export CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER="$orchard_win_dir/msvc-link"
export CC_x86_64_pc_windows_msvc="$orchard_win_dir/msvc-cl"
export CXX_x86_64_pc_windows_msvc="$orchard_win_dir/msvc-cl"
export AR_x86_64_pc_windows_msvc="$orchard_win_dir/msvc-lib"

mapfile -t bindgen_include_dirs < <("$orchard_win_dir/msvc-wine-tool.py" --print-bindgen-includes)
if [[ ${#bindgen_include_dirs[@]} -ne 2 ]] ||
   [[ ! -f "${bindgen_include_dirs[0]}/vcruntime.h" ]] ||
   [[ ! -f "${bindgen_include_dirs[1]}/math.h" ]]; then
  orchard_die "MSVC or Windows SDK headers for bindgen are missing"
fi
# %q preserves spaces in paths when bindgen splits these arguments.
printf -v BINDGEN_EXTRA_CLANG_ARGS -- '-isystem %q -isystem %q' \
  "${bindgen_include_dirs[0]}" "${bindgen_include_dirs[1]}"
export BINDGEN_EXTRA_CLANG_ARGS

meson compile -C "$ORCHARD_MSVC_BUILD_DIR" -j "$ORCHARD_BUILD_JOBS" "$@"
