#!/usr/bin/env python3
"""Stage the Apple Music player page's scan fallback beside the helper: adaptive mix built for
wasm32-wasip1 and onnxruntime-web's single-threaded WASM runtime, in <destination>/applemusic-web.

Needs rustup's wasm32-wasip1 target, clang with wasm32 support and a WASI sysroot with
libc++ (on Arch: wasi-libc wasi-libc++ wasi-libc++abi wasi-compiler-rt).
"""

import argparse
import os
import shutil
import subprocess
from pathlib import Path

TARGET = "wasm32-wasip1"
ORT_FILES = ("ort.wasm.min.mjs", "ort-wasm-simd-threaded.mjs", "ort-wasm-simd-threaded.wasm")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--target-dir", type=Path, required=True)
    parser.add_argument("--sysroot", type=Path, default=Path("/usr/share/wasi-sysroot"))
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="stamp file")
    args = parser.parse_args()

    sysroot = args.sysroot
    env = dict(os.environ)
    key = TARGET.replace("-", "_")
    env.update({
        f"CC_{key}": "clang",
        f"CXX_{key}": "clang++",
        f"AR_{key}": "llvm-ar",
        # QuickJS reads process clocks, which WASI only offers through emulation.
        f"CFLAGS_{key}": f"--target={TARGET} --sysroot={sysroot} -O2 -D_WASI_EMULATED_PROCESS_CLOCKS",
        f"CXXFLAGS_{key}": f"--target={TARGET} --sysroot={sysroot} -O2 -fno-exceptions",
        f"BINDGEN_EXTRA_CLANG_ARGS_{key}": f"--target={TARGET} --sysroot={sysroot} -fvisibility=default",
        # The planner's QuickJS needs a deep stack; Signalsmith Stretch links libc++.
        f"CARGO_TARGET_{key.upper()}_RUSTFLAGS": (
            f"-C link-arg=-zstack-size=16777216 -L {sysroot}/lib/{TARGET} "
            "-l static=c++ -l static=c++abi -l static=wasi-emulated-process-clocks"),
    })
    manifest = args.source_root / "crates/orchard-adaptive-mix-web/Cargo.toml"
    subprocess.run(["cargo", "build", "--release", "--target", TARGET, "--manifest-path", str(manifest),
                    "--target-dir", str(args.target_dir)], check=True, env=env)
    web = args.destination / "applemusic-web"
    web.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.target_dir / TARGET / "release/orchard_adaptive_mix_web.wasm", web / "mixwasm.wasm")
    ort = args.source_root / "providers/youtube/node_modules/onnxruntime-web"
    for name in ORT_FILES:
        shutil.copy2(ort / "dist" / name, web / name)
    args.output.touch()


if __name__ == "__main__":
    main()
