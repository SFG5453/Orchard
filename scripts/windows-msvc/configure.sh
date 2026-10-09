#!/usr/bin/env bash
set -euo pipefail
orchard_script=configure
source "$(dirname -- "${BASH_SOURCE[0]}")/env.sh"

[[ -x "$ORCHARD_HOST_QJSC" ]] || orchard_die "host qjsc missing; run scripts/windows-msvc/setup.sh"
[[ -d "$ORCHARD_MSVC_QT_ROOT" ]] || orchard_die "Windows Qt missing: $ORCHARD_MSVC_QT_ROOT (run setup.sh)"
python3 "$orchard_win_dir/check-configured-qt.py" "$ORCHARD_MSVC_BUILD_DIR" "$ORCHARD_MSVC_QT_ROOT"
export ORCHARD_MSVC_QT_ROOT ORCHARD_QT_HOST_PATH

orchard_need_cmd meson "Meson 1.12 or newer"
cross_file="$ORCHARD_WIN_TOOLS/meson-msvc-cross.ini"
python3 "$orchard_win_dir/meson-cross.py" \
  --script-dir "$orchard_win_dir" --qt-root "$ORCHARD_MSVC_QT_ROOT" \
  --output "$cross_file"
meson_setup_args=()
if [[ -f "$ORCHARD_MSVC_BUILD_DIR/meson-info/meson-info.json" ]]; then
  meson_setup_args+=(--reconfigure)
fi
meson setup "${meson_setup_args[@]}" "$ORCHARD_MSVC_BUILD_DIR" "$orchard_source_dir" \
  --cross-file "$cross_file" --buildtype release \
  -Dorchard_rust_target=x86_64-pc-windows-msvc \
  -Dtests=false -Dhost_qjsc="$ORCHARD_HOST_QJSC" "$@"
