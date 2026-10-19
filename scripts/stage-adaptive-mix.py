# Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD.

"""Stage the worker, ORT's Dawn runtime, FFmpeg and model files beside orchard."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

PLATFORMS = ("linux-x86_64", "linux-aarch64", "windows-x86_64-msvc", "windows-x86_64-gnu",
             "windows-aarch64-msvc", "macos-x86_64", "macos-aarch64")
p = argparse.ArgumentParser()
p.add_argument("--platform", required=True, choices=PLATFORMS)
p.add_argument("--profile", type=Path, required=True)
p.add_argument("--destination", type=Path, required=True)
p.add_argument("--models", type=Path, required=True)
p.add_argument("--cache", type=Path, required=True)
a = p.parse_args()
a.destination.mkdir(parents=True, exist_ok=True)
a.cache.mkdir(parents=True, exist_ok=True)
found = False
dxc = set()
for output in (a.profile / "build").glob("ort-sys-*/output"):
    for line in output.read_text().splitlines():
        if "rustc-link-search=" not in line:
            continue
        directory = Path(line.split("rustc-link-search=", 1)[1].removeprefix("native="))
        for library in directory.glob("*webgpu*dawn*"):
            if library.suffix in (".so", ".dll", ".dylib"):
                shutil.copy2(library, a.destination / library.name)
                found = True
        # Dawn's D3D12 backend loads DXC from beside the worker.
        for name in ("dxil.dll", "dxcompiler.dll"):
            if (directory / name).exists():
                shutil.copy2(directory / name, a.destination / name)
                dxc.add(name)
if not found:
    raise SystemExit("ort's Dawn runtime was not found in Cargo's linker output")
if a.platform == "windows-x86_64-msvc" and dxc != {"dxil.dll", "dxcompiler.dll"}:
    raise SystemExit("ort's DXC libraries were not found in Cargo's linker output")
if a.platform in ("windows-x86_64-msvc", "windows-x86_64-gnu"):
    subprocess.run([
        sys.executable, str(Path(__file__).with_name("stage-ffmpeg-windows.py")),
        str(a.destination), str(a.cache),
    ], check=True)
worker = "orchard-adaptive-mix" + (".exe" if a.platform.startswith("windows") else "")
shutil.copy2(a.profile / worker, a.destination / worker)
# Stage only the validated runtime graphs and their documentation/licenses.
for relative in ("nirovi/nirovi_fp16.onnx", "nirovi/nirovi_int8.onnx", "nirovi/README.md",
                 "vocal-separation/vocals_umxhq_fp32.onnx", "vocal-separation/README.md", "vocal-separation/LICENSE",
                 "docs-search/model_quantized.onnx", "docs-search/vocab.txt", "docs-search/README.md",
                 "docs-search/LICENSE", "slop/fakeprint_lr.f32"):
    target = a.destination / "models" / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(a.models / relative, target)

# Retire only earlier generated runtime graphs when rebuilding this directory.
# Reused build directories may hold a wgpu-native library the worker never loads.
for name in ("libwgpu_native.so", "wgpu_native.dll", "libwgpu_native.dylib"):
    (a.destination / name).unlink(missing_ok=True)
for relative in ("beat-this/beat_this.onnx", "beat-this/beat_this_webgpu.onnx", "beat-this/README.md",
                 "beat-this/LICENSE", "vocal-separation/vocals_umxhq_int8.onnx",
                 "vocal-separation/vocals_umxhq_fp32_fp16.onnx"):
    stale = a.destination / "models" / relative
    if stale.exists() and stale.resolve() != (a.models / relative).resolve():
        stale.unlink()
if (a.destination / "models" / "beat-this").is_dir() and not any((a.destination / "models" / "beat-this").iterdir()):
    (a.destination / "models" / "beat-this").rmdir()
