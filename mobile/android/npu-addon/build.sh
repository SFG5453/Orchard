#!/usr/bin/env bash
# Builds one Orchard NPU add-on APK per Hexagon generation, plus npu-addons.json for the download host.
# Needs: prebuilt/libonnxruntime.so from build-ort.sh, the QAIRT SDK, and the key Orchard is signed with
# (ANDROID_KEYSTORE_FILE and friends; the debug keystore otherwise, which matches debug and local canary builds).
set -euo pipefail
ORT_SRC=${ORT_SRC:?path to onnxruntime v1.31.0 checkout}
QNN_SDK=${QNN_SDK:?path to qairt/2.51.0.260929}
SDK=${ANDROID_HOME:-$HOME/.local/share/android/sdk}
NDK=$SDK/ndk/28.2.13676358
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT=${OUT:-$HERE/build}
ARCHES=${ARCHES:-"v68 v69 v73 v75 v79 v81"}
# Bump when the runner, ONNX Runtime, QNN or the models change; Orchard asks for at least this.
VERSION_CODE=1
VERSION_NAME="1-qnn2.51"
BASE_URL=${BASE_URL:-https://depot.sfg545.dev/android/npu}

TOOLS=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
mkdir -p "$OUT/runner"
"$TOOLS/aarch64-linux-android31-clang++" -O2 -std=c++17 -shared -fPIC -fvisibility=hidden -static-libstdc++ \
  -Wl,-z,max-page-size=16384 -I "$ORT_SRC/include/onnxruntime/core/session" \
  -o "$OUT/runner/liborchard_qnn.so" "$HERE/runner/qnn_runner.cpp" -ldl -llog
"$TOOLS/llvm-strip" --strip-unneeded "$OUT/runner/liborchard_qnn.so"

if [ -n "${ANDROID_KEYSTORE_FILE:-}" ]; then
  SIGN=(--ks "$ANDROID_KEYSTORE_FILE" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass env:ANDROID_KEY_PASSWORD)
else
  SIGN=(--ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android)
fi

manifest="{"
for arch in $ARCHES; do
  A=${arch^^}
  work="$OUT/$arch"
  rm -rf "$work" && mkdir -p "$work/lib/arm64-v8a" "$work/assets"
  sed -e "s/@ARCH@/$arch/; s/@ARCH_UPPER@/$A/; s/@VERSION_CODE@/$VERSION_CODE/; s/@VERSION_NAME@/$VERSION_NAME/" \
    "$HERE/AndroidManifest.xml.in" > "$work/AndroidManifest.xml"
  cp "$HERE/prebuilt/libonnxruntime.so" "$OUT/runner/liborchard_qnn.so" "$work/lib/arm64-v8a/"
  for lib in libQnnHtp.so libQnnSystem.so libQnnHtpPrepare.so "libQnnHtp${A}Stub.so"; do
    cp "$QNN_SDK/lib/aarch64-android/$lib" "$work/lib/arm64-v8a/"
  done
  cp "$QNN_SDK/lib/hexagon-$arch/unsigned/libQnnHtp${A}Skel.so" "$work/lib/arm64-v8a/"
  cp "$ROOT/models/npu/qnn/nirovi_qnn.onnx" "$ROOT/models/npu/qnn/syvenu_qnn.onnx" "$work/assets/"
  "$BT/aapt2" link -I "$JAR" --manifest "$work/AndroidManifest.xml" -A "$work/assets" -o "$work/base.apk"
  (cd "$work" && cp base.apk unsigned.apk && zip -q -9 -r unsigned.apk lib)
  "$BT/zipalign" -f -p 16 "$work/unsigned.apk" "$work/aligned.apk"
  apk="$OUT/orchard-npu-$arch.apk"
  "$BT/apksigner" sign "${SIGN[@]}" --out "$apk" "$work/aligned.apk"
  size=$(stat -c %s "$apk")
  sha=$(sha256sum "$apk" | cut -d' ' -f1)
  manifest+="\"$arch\":{\"url\":\"$BASE_URL/orchard-npu-$arch.apk\",\"sha256\":\"$sha\",\"size\":$size,\"versionCode\":$VERSION_CODE},"
  echo "$arch: $size bytes"
done
echo "${manifest%,}}" > "$OUT/npu-addons.json"
