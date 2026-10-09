{
  description = "Orchard - native Qt 6/QML music player with a Rust core";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # nixpkgs' rustc only carries the host std; the Android build needs the NDK targets too.
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  # This is a development shell rather than a package: the build runs npm
  # against the network and `ort` downloads ONNX Runtime (with Dawn) inside
  # cargo, neither of which the Nix sandbox would ever allow. Purity is a
  # journey, and this repo has not packed for it yet.
  outputs =
    {
      self,
      nixpkgs,
      rust-overlay,
    }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems =
        f:
        nixpkgs.lib.genAttrs systems (
          system:
          f (
            import nixpkgs {
              inherit system;
              overlays = [ rust-overlay.overlays.default ];
            }
          )
        );
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          # One merged Qt prefix, so CMake, plugins and QML imports all agree
          # on where Qt lives. Seven Q-prefixed modules walk into a buildEnv...
          qtEnv = pkgs.qt6.env "orchard-qt-${pkgs.qt6.qtbase.version}" (
            with pkgs.qt6;
            [
              qtdeclarative
              qtshadertools
              qtmultimedia
              qtsvg
              qtwayland
              # WebEngine's CMake config quietly insists on these two. Nobody
              # asked where the login window is, but it knows anyway.
              qtwebchannel
              qtpositioning
              qtwebview
              qtwebengine
            ]
          );

          # Host std is implicit; targets match orchardStlTriples in mobile/android build.gradle.
          # Two phones, one crab.
          rustToolchain = pkgs.rust-bin.stable.latest.default.override {
            extensions = [
              "rust-src"
              "rust-analyzer"
              "clippy"
              "rustfmt"
            ];
            targets = [
              "aarch64-linux-android"
              "x86_64-linux-android"
            ];
          };

          # ort's downloaded libonnxruntime/Dawn binaries are prebuilt for a
          # "normal" distro, so they need these found at runtime via
          # LD_LIBRARY_PATH (the WebGPU backend talks Vulkan).
          runtimeLibs = with pkgs; [
            stdenv.cc.cc.lib
            vulkan-loader
            libGL
            wayland
            libxkbcommon
            libx11
            libxcursor
            libxi
            libxrandr
          ];
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              # C++ / Qt build
              cmake
              ninja
              pkg-config
              qtEnv
              vulkan-headers # Qt6Gui's CMake config goes looking for these
              libsecret # qtkeychain's Linux backend

              # Rust core (the YouTube extractor needs rustc >= 1.96)
              rustToolchain
              cargo-ndk # Gradle shells out to `cargo ndk` for Earmark
              rustPlatform.bindgenHook # signalsmith-stretch runs bindgen

              # Provider bundling, JS tests and adaptive-mix staging
              nodejs
              python3

              # Adaptive mix reads audio with ffmpeg from PATH on Linux
              ffmpeg

              git
            ];

            buildInputs = runtimeLibs;

            # Let the (unwrapped) dev build find Qt's platform plugins and QML
            # modules without wrapQtAppsHook.
            QT_PLUGIN_PATH = "${qtEnv}/${pkgs.qt6.qtbase.qtPluginPrefix}";
            QML_IMPORT_PATH = "${qtEnv}/${pkgs.qt6.qtbase.qtQmlPrefix}";
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibs;

            shellHook = ''
              # CMake add_subdirectory()s these, so an empty folder means a
              # very confusing configure error. Nag early, nag gently.
              if [ ! -e third_party/quickjs/CMakeLists.txt ] || [ ! -e third_party/qtkeychain/CMakeLists.txt ]; then
                echo "orchard: submodules missing, run: git submodule update --init --recursive"
              fi
              if [ ! -d providers/youtube/node_modules ]; then
                echo "orchard: provider deps missing, run: npm ci --prefix providers/youtube"
              fi
            '';
          };
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
