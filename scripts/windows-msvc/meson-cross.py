#!/usr/bin/env python3
"""Write the Meson cross file for the existing Wine/MSVC wrappers."""

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--script-dir", type=Path, required=True)
    parser.add_argument("--qt-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    wrap = args.script_dir.resolve()
    qt = args.qt_root.resolve()
    entries = [
        "[binaries]",
        "c = " + repr(str(wrap / "msvc-cl")),
        "cpp = " + repr(str(wrap / "msvc-cl")),
        "c_ld = " + repr(str(wrap / "msvc-link")),
        "cpp_ld = " + repr(str(wrap / "msvc-link")),
        "ar = " + repr(str(wrap / "msvc-lib")),
        "windres = " + repr(str(wrap / "msvc-rc")),
        "cmake = 'cmake'",
        "pkg-config = 'pkg-config'",
        "exe_wrapper = 'wine'",
        "",
        "[host_machine]",
        "system = 'windows'",
        "cpu_family = 'x86_64'",
        "cpu = 'x86_64'",
        "endian = 'little'",
        "",
        "[properties]",
        "cmake_toolchain_file = " + repr(str(wrap / "msvc-toolchain.cmake")),
        "cmake_defaults = false",
        "",
        "[built-in options]",
        "cmake_prefix_path = [" + repr(str(qt)) + "]",
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(entries) + "\n")


if __name__ == "__main__":
    main()
