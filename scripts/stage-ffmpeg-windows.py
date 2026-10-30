"""Build the pinned Windows FFmpeg sources and stage their command-line tools."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys


def stage(destination: Path, cache: Path) -> None:
    root = Path(__file__).resolve().parent.parent
    script = root / "scripts/build-ffmpeg-windows.sh"
    fingerprint = hashlib.sha256(script.read_bytes())
    for name in ("ffmpeg", "libwebp"):
        source = root / "third_party" / name
        if not (source / ".git").exists():
            raise SystemExit("Initialize the ffmpeg and libwebp submodules before building")
        fingerprint.update(subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"]))
    build = cache.resolve() / ("ffmpeg-windows-" + fingerprint.hexdigest()[:16])
    binaries = build / "install/bin"
    names = ("ffmpeg.exe", "ffprobe.exe", "ffmpeg-LICENSE.txt")
    if not all((binaries / name).is_file() for name in names):
        bash = os.environ.get("ORCHARD_FFMPEG_BASH") or shutil.which("bash")
        if os.name == "nt" and not os.environ.get("ORCHARD_FFMPEG_BASH"):
            candidate = Path("C:/msys64/usr/bin/bash.exe")
            if candidate.is_file():
                bash = str(candidate)
        if not bash:
            raise SystemExit("FFmpeg needs an MSYS2 bash (ORCHARD_FFMPEG_BASH) or a MinGW cross toolchain")
        subprocess.run([bash, str(script).replace("\\", "/"), str(build).replace("\\", "/")], check=True)
    for name in names:
        shutil.copy2(binaries / name, destination / name)


if __name__ == "__main__":
    stage(Path(sys.argv[1]), Path(sys.argv[2]))
