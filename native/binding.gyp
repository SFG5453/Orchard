{
  "targets": [
    {
      "target_name": "orchard_audio_analysis",
      "sources": [
        "binding/addon.cpp",
        "analyzer/audio_analysis.cpp",
        "analyzer/tempo_analysis.cpp"
      ],
      "include_dirs": [
        "<!@(node -p \"require('node-addon-api').include\")"
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
              "AdditionalOptions": ["/std:c++17"]
            }
          }
        }],
        ["OS!='win'", {
          "cflags_cc": ["-std=c++17", "-O3"]
        }]
      ]
    }
  ]
}
