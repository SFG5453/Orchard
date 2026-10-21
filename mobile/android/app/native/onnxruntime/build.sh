#!/usr/bin/env bash
# Rebuilds the minimal libonnxruntime.so for arm64-v8a. It loads only ORT format models.
# Re-run when a model's operators change.
# Needs: a venv with onnx, flatbuffers, packaging, setuptools; ORT checkout at the tag matching the AAR.
set -euo pipefail
ORT_SRC=${ORT_SRC:?path to onnxruntime v1.31.0 checkout}
SDK=${ANDROID_HOME:-$HOME/.local/share/android/sdk}
NDK=$SDK/ndk/28.2.13676358
HERE=$(cd "$(dirname "$0")" && pwd)
# Off /tmp, which is often RAM-backed; the build tree is several GB.
OUT=${OUT:-${XDG_CACHE_HOME:-$HOME/.cache}/orchard-ort-build-arm64}

# ops.config comes from converting the shipped .onnx sources to .ort:
#   python -m onnxruntime.tools.convert_onnx_models_to_ort DIR --optimization_style Fixed \
#     --target_platform arm --enable_type_reduction
python "$ORT_SRC/tools/ci_build/build.py" --build_dir "$OUT" --config MinSizeRel --parallel 8 \
  --skip_tests --build_shared_lib --android --android_abi arm64-v8a --android_api 31 \
  --android_sdk_path "$SDK" --android_ndk_path "$NDK" --disable_ml_ops --disable_rtti \
  --minimal_build --no_telemetry --enable_reduced_operator_type_support \
  --include_ops_by_config "$HERE/ops.config" --compile_no_warning_as_error \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  -o "$HERE/arm64-v8a/libonnxruntime.so" "$OUT/MinSizeRel/libonnxruntime.so"
