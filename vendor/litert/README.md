# LiteRT 2.2.0

C API headers from `litert_cc_sdk.zip` (github.com/google-ai-edge/LiteRT, release v2.2.0)
and the matching prebuilt runtimes from storage.googleapis.com/litert/binaries/2.2.0/:

- `lib/linux/libLiteRt.so` (linux_x86_64)
- `lib/windows/libLiteRt.dll` (windows_x86_64)
- `lib/macos/libLiteRt.dylib` (macos_arm64)
- `lib/android/arm64-v8a/libLiteRt.so` (android_arm64)
- `lib/android/x86_64/libLiteRt.so` (android_x86_64, emulator builds only)

Orchard loads the runtime with dlopen/LoadLibrary (`core/native/translation/litert_api.cpp`) and calls the C API only, so the C++ SDK sources,
abseil, and flatbuffers are not needed. `build_common/build_config.h` is the CMake output
of `build_config.h.in` with GPU and NPU left enabled (the defaults).
`litert/c/internal/litert_options_helper.h` was dropped: it needs abseil and no public header includes it.
