#!/usr/bin/env bash
# Rebuilds the reduced-op libonnxruntime.so for arm64-v8a. Re-run when a model's operators change.
# Needs: a venv with onnx, flatbuffers, packaging, setuptools; ORT checkout at the tag matching the AAR.
set -euo pipefail
ORT_SRC=${ORT_SRC:?path to onnxruntime v1.30.0 checkout}
SDK=${ANDROID_HOME:-$HOME/.local/share/android/sdk}
NDK=$SDK/ndk/28.2.13676358
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-/tmp/ort-build-arm64}

# ops.config comes from create_reduced_build_config.py over src/main/assets/*.onnx plus the
# fused ops the optimizer emits (DynamicQuantizeMatMul, FusedMatMul, Gelu, MatMulIntegerToFloat).
python "$ORT_SRC/tools/ci_build/build.py" --build_dir "$OUT" --config MinSizeRel --parallel 8 \
  --skip_tests --build_shared_lib --android --android_abi arm64-v8a --android_api 31 \
  --android_sdk_path "$SDK" --android_ndk_path "$NDK" --disable_ml_ops --disable_rtti \
  --include_ops_by_config "$HERE/ops.config" --compile_no_warning_as_error \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  -o "$HERE/arm64-v8a/libonnxruntime.so" "$OUT/MinSizeRel/libonnxruntime.so"
