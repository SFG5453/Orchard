#!/usr/bin/env python3
"""Deploy a native MSVC build and archive its runtime as one 7z file."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess

from windows_icu_runtime import ICU_DLLS, copy_icu_runtime
from windows_qt_runtime import validate_qt_runtime


def require(path: Path) -> Path:
    if not path.exists():
        raise SystemExit(f"Missing package input: {path}")
    return path


def copy_msvc_runtime(stage: Path) -> None:
    redist = os.environ.get("VCToolsRedistDir")
    if not redist:
        raise SystemExit("VCToolsRedistDir is missing; run from an MSVC developer shell")
    directories = [path for path in Path(redist).glob("x64/Microsoft.VC*.CRT") if path.is_dir()]
    if len(directories) != 1:
        raise SystemExit(f"Expected one x64 MSVC CRT directory under {redist}")
    runtime = directories[0]
    for name in ("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll"):
        require(runtime / name)
    for path in runtime.glob("*.dll"):
        shutil.copy2(path, stage / path.name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--qt-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    build = args.build_dir.resolve()
    qt = args.qt_root.resolve()
    package = args.output.resolve()
    source = Path(__file__).resolve().parent.parent
    stage = package.parent / "Orchard"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    binaries = ("orchard.exe", "orchard-auth-helper.exe", "orchard-adaptive-mix.exe",
                "webgpu_dawn.dll", "discord_partner_sdk.dll", "libLiteRt.dll", "ffmpeg.exe",
                "ffmpeg-LICENSE.txt")
    for name in binaries:
        shutil.copy2(require(build / name), stage / name)
    shutil.copytree(require(build / "models"), stage / "models")
    for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
        shutil.copy2(source / name, stage / name)
    shutil.copy2(source / "vendor/discord_social_sdk/License-Notices.txt",
                 stage / "DiscordSocialSdk-Notices.txt")
    # The updater launches app-local CRT DLLs without running a system installer.
    copy_msvc_runtime(stage)
    copy_icu_runtime(stage)

    deploy = require(qt / "bin" / "windeployqt.exe")
    require(qt / "plugins" / "webview" / "qtwebview_webengine.dll")
    # The helper selects WebEngine at runtime, so deployment must include its plugin.
    for exe in ("orchard.exe", "orchard-auth-helper.exe"):
        subprocess.run([str(deploy), "--release", "--no-compiler-runtime",
                        "--include-plugins", "qtwebview_webengine",
                        "--exclude-plugins", "qtwebview_webview2", "--qmldir",
                        str(source / "app" / "qml"), "--dir", str(stage),
                        str(stage / exe)], check=True)
    (stage / "sqldrivers").mkdir(exist_ok=True)
    shutil.copy2(require(qt / "plugins" / "sqldrivers" / "qsqlite.dll"),
                 stage / "sqldrivers" / "qsqlite.dll")

    required = ("QtWebEngineProcess.exe", "Qt6WebEngineCore.dll", "Qt6WebEngineQuick.dll",
                "platforms/qwindows.dll", "webview/qtwebview_webengine.dll",
                "qml/QtWebEngine/qtwebenginequickplugin.dll", "resources/icudtl.dat",
                "resources/qtwebengine_resources.pak", "translations/qtwebengine_locales/en-US.pak",
                "multimedia/ffmpegmediaplugin.dll", "models/beat-this/beat_this_webgpu.onnx",
                "models/vocal-separation/vocals_umxhq_fp32.onnx",
                "models/docs-search/model_quantized.onnx", "models/docs-search/vocab.txt",
                "models/slop/fakeprint_lr.f32")
    for name in required:
        require(stage / name)
    for name in ICU_DLLS:
        require(stage / name)
    validate_qt_runtime(stage)

    package.parent.mkdir(parents=True, exist_ok=True)
    if package.exists():
        package.unlink()
    seven_zip = (shutil.which("7z") or shutil.which("7z.exe") or
                 str(Path("C:/Program Files/7-Zip/7z.exe")))
    if not Path(seven_zip).exists():
        raise SystemExit("7z is required to create the Windows archive")
    # Solid LZMA2 keeps Chromium and FFmpeg from eating the whole release shelf.
    subprocess.run([seven_zip, "a", "-t7z", "-m0=lzma2", "-mx=9", "-ms=on",
                    str(package), stage.name], cwd=package.parent, check=True)
    subprocess.run([seven_zip, "t", str(package)], check=True)
    print(package)


if __name__ == "__main__":
    main()
