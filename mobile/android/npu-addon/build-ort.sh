#!/usr/bin/env bash
# Builds the add-on's ONNX Runtime: full ONNX loading, QNN EP linked in, CPU kernels reduced to ops.config.
# Full build because the QNN EP compiles QDQ ONNX graphs and caches them as EPContext ONNX models.
set -euo pipefail
ORT_SRC=${ORT_SRC:?path to onnxruntime v1.31.0 checkout}
QNN_SDK=${QNN_SDK:?path to qairt/2.51.0.260929}
SDK=${ANDROID_HOME:-$HOME/.local/share/android/sdk}
NDK=$SDK/ndk/28.2.13676358
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-${XDG_CACHE_HOME:-$HOME/.cache}/orchard-ort-qnn-addon}
python "$ORT_SRC/tools/ci_build/build.py" --build_dir "$OUT" --config MinSizeRel --parallel "$(nproc)" \
  --skip_tests --build_shared_lib --android --android_abi arm64-v8a --android_api 31 \
  --android_sdk_path "$SDK" --android_ndk_path "$NDK" --disable_ml_ops --no_telemetry \
  --use_qnn static_lib --qnn_home "$QNN_SDK" --include_ops_by_config "$HERE/ops.config" \
  --compile_no_warning_as_error --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF
mkdir -p "$HERE/prebuilt"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  -o "$HERE/prebuilt/libonnxruntime.so" "$OUT/MinSizeRel/libonnxruntime.so"
