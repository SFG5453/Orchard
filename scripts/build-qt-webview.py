#!/usr/bin/env python3
"""Build QtWebView with the WebEngine backend for the release Qt SDK."""

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request


QT_VERSION = "6.12.0"
SOURCE_URL = f"https://codeload.github.com/qt/qtwebview/tar.gz/refs/tags/v{QT_VERSION}"
SOURCE_SHA256 = "d7a4a63c4e3a0bb9c03216d7a5d53c7b92ef6152e5523cfe846efd0c87ba35a8"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--qt-root", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--install-prefix", type=Path)
    parser.add_argument("--toolchain-file", type=Path)
    args = parser.parse_args()

    qt = args.qt_root.resolve()
    work = args.build_dir.resolve()
    prefix = (args.install_prefix or qt).resolve()
    if not (qt / "lib/cmake/Qt6/Qt6Config.cmake").is_file():
        raise SystemExit(f"Qt SDK not found: {qt}")
    compiler_args = []
    if args.toolchain_file:
        compiler_args.append(
            f"-DCMAKE_TOOLCHAIN_FILE={args.toolchain_file.resolve()}"
        )
    if sys.platform == "win32":
        # Match the MSVC SDK even when GCC is also present on PATH.
        compiler = shutil.which("cl.exe")
        if compiler is None:
            raise SystemExit("MSVC cl.exe not found; run from an MSVC developer shell")
        compiler_args = [f"-DCMAKE_C_COMPILER={compiler}", f"-DCMAKE_CXX_COMPILER={compiler}"]
    work.mkdir(parents=True, exist_ok=True)
    archive = work / f"qtwebview-{QT_VERSION}.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            archive.write_bytes(response.read())
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise SystemExit(f"QtWebView source checksum mismatch: {archive}")

    with tarfile.open(archive) as source_archive:
        source_archive.extractall(work, filter="data")
    source = work / f"qtwebview-{QT_VERSION}"
    build = work / "build"

    # Qt 6.12 SDKs omit this backend; build it against the installed WebEngine.
    subprocess.run([
        "cmake", "-S", str(source), "-B", str(build), "-G", "Ninja",
        f"-DCMAKE_PREFIX_PATH={qt}", f"-DCMAKE_INSTALL_PREFIX={prefix}",
        "-DCMAKE_BUILD_TYPE=Release", "-DQT_BUILD_TESTS=OFF", "-DQT_BUILD_EXAMPLES=OFF",
        "-DFEATURE_webview_webengine_plugin=ON", "-DFEATURE_webview_webview2_plugin=OFF",
    ] + compiler_args, check=True)
    subprocess.run(["cmake", "--build", str(build)], check=True)
    subprocess.run(["cmake", "--install", str(build)], check=True)

    cache = (build / "CMakeCache.txt").read_text()
    plugin_path = next(line.split("=", 1)[1] for line in cache.splitlines()
                       if line.startswith("INSTALL_PLUGINSDIR:STRING="))
    plugin_dir = prefix / plugin_path / "webview"
    if not any(plugin_dir.glob("*qtwebview_webengine.*")):
        raise SystemExit(f"QtWebView WebEngine plugin not installed: {plugin_dir}")
    print(f"QtWebView {QT_VERSION} WebEngine backend installed: {plugin_dir}")


if __name__ == "__main__":
    main()
