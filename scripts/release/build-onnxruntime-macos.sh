#!/usr/bin/env bash
# Build the x86_64 macOS ONNX Runtime library, which ort-sys does not ship.
set -euo pipefail

version="${1:?usage: build-onnxruntime-macos.sh VERSION WORK_DIRECTORY OUTPUT_DIRECTORY}"
work="${2:?usage: build-onnxruntime-macos.sh VERSION WORK_DIRECTORY OUTPUT_DIRECTORY}"
output="${3:?usage: build-onnxruntime-macos.sh VERSION WORK_DIRECTORY OUTPUT_DIRECTORY}"

# microsoft/onnxruntime is unavailable on GitHub, so the source is a fork archive pinned by hash.
case "$version" in
  v1.31.0) sha256=a7e84230b5d509e48fbd6b73c2453a56345ccb22da34a46dac1f0cb4a2d6507b ;;
  *) echo "No pinned ONNX Runtime archive for $version" >&2; exit 1 ;;
esac

source_dir="$work/onnxruntime"
archive="$work/onnxruntime.tar.gz"
mkdir -p "$source_dir" "$output"
curl -fL --retry 5 --retry-all-errors -o "$archive" \
  "https://codeload.github.com/ossia/onnxruntime/tar.gz/refs/tags/$version"
echo "$sha256  $archive" | shasum -a 256 -c -
tar -xzf "$archive" --strip-components=1 -C "$source_dir"

cd "$source_dir"
# The archive is not a git checkout, and this build needs none of its submodules.
./build.sh --config Release --build_shared_lib --parallel --skip_tests --compile_no_warning_as_error \
  --skip_submodule_sync --apple_deploy_target 14.4 --cmake_extra_defines CMAKE_OSX_ARCHITECTURES=x86_64
cp -a build/MacOS/Release/libonnxruntime*.dylib "$output/"
lipo -archs "$output/libonnxruntime.dylib" | grep -qx x86_64
