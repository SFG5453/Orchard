#!/usr/bin/env python3
"""Build qtkeychain with its upstream CMake rules until Meson can import them."""

import argparse
import shutil
import subprocess
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-type", required=True)
    parser.add_argument("--toolchain", type=Path, required=True)
    args = parser.parse_args()

    configure = [
        "cmake", "-S", str(args.source), "-B", str(args.build_dir),
        "-G", "Ninja", "-DCMAKE_BUILD_TYPE=" + args.build_type,
        "-DCMAKE_TOOLCHAIN_FILE=" + str(args.toolchain),
        "-DBUILD_SHARED_LIBS=OFF", "-DBUILD_TRANSLATIONS=OFF",
        "-DBUILD_TEST_APPLICATION=OFF", "-DBUILD_QTQUICK_DEMO=OFF",
        "-DBUILD_TESTING=OFF",
    ]
    subprocess.run(configure, check=True)
    subprocess.run(["cmake", "--build", str(args.build_dir), "--target", "qt6keychain"], check=True)

    candidates = sorted((args.build_dir / "lib").glob("*qt6keychain*.a"))
    if args.output.suffix == ".lib":
        candidates = sorted((args.build_dir / "lib").glob("*qt6keychain*.lib"))
    if not candidates:
        raise FileNotFoundError("qtkeychain archive was not produced")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(candidates[0], args.output)


if __name__ == "__main__":
    main()
