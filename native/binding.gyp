{
  "targets": [
    {
      "target_name": "orchard_audio_analysis",
      "sources": [
        "binding/addon.cpp",
        "analyzer/audio_analysis.cpp",
        "analyzer/tempo_analysis.cpp",
        "analyzer/mel_spectrogram.cpp",
        "analyzer/vocal_spectrogram.cpp",
        "transition/transition_render.cpp",
        "transition/rubberband_stretch.cpp",
        # Rubber Band's own single-file build unit (vendored from
        # breakfastquay/rubberband, GPL-2-or-later). It #includes the rest of
        # the library's .cpp files itself via paths relative to its own
        # directory, so only this one file is listed here -- see
        # vendor/rubberband/single/RubberBandSingle.cpp for what that pulls in.
        "vendor/rubberband/single/RubberBandSingle.cpp"
      ],
      "include_dirs": [
        "<!@(node -p \"require('node-addon-api').include\")",
        "vendor/rubberband"
      ],
      "dependencies": [
        "<!(node -p \"require('node-addon-api').gyp\")"
      ],
      "defines": [
        "NAPI_DISABLE_CPP_EXCEPTIONS"
      ],
      "conditions": [
        ["OS=='win'", {
          "msvs_settings": {
            "VCCLCompilerTool": {
              "AdditionalOptions": ["/std:c++17"],
              # MSVC exceptions are typically on by default for node-gyp
              # projects, but node-addon-api sometimes suppresses them
              # alongside NAPI_DISABLE_CPP_EXCEPTIONS; Rubber Band's vendored
              # source (which does throw internally, unlike our own code)
              # needs them regardless.
              "ExceptionHandling": 1
            }
          }
        }],
        ["OS!='win'", {
          # node-gyp's default cflags disable C++ exceptions, which
          # NAPI_DISABLE_CPP_EXCEPTIONS above is enough for -- our own code
          # never throws. Rubber Band's vendored source does (a handful of
          # internal error paths), so exceptions have to actually work at the
          # compiler level; native/transition/rubberband_stretch.cpp still
          # catches anything that reaches it rather than letting it escape
          # across the worker-thread boundary.
          "cflags_cc!": ["-fno-exceptions"],
          "cflags_cc": ["-std=c++17", "-O3", "-fexceptions"]
        }],
        ["OS=='mac'", {
          "xcode_settings": {
            "GCC_ENABLE_CPP_EXCEPTIONS": "YES"
          },
          # RubberBandSingle.cpp selects the vDSP FFT on Apple platforms
          # instead of its built-in one, which needs the Accelerate framework
          # at link time.
          "link_settings": {
            "libraries": ["-framework Accelerate"]
          }
        }]
      ]
    }
  ]
}
