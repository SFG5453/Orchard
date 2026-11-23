"""Build the pinned macOS FFmpeg sources and stage their command-line tools."""

import hashlib
from pathlib import Path
import shutil
import subprocess
import sys


def stage(destination: Path, cache: Path, arch: str) -> None:
    root = Path(__file__).resolve().parent.parent
    script = root / "scripts/build-ffmpeg-macos.sh"
    fingerprint = hashlib.sha256(script.read_bytes())
    for name in ("ffmpeg", "libwebp"):
        source = root / "third_party" / name
        if not (source / ".git").exists():
            raise SystemExit("Initialize the ffmpeg and libwebp submodules before building")
        fingerprint.update(subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"]))
    build = cache.resolve() / (f"ffmpeg-macos-{arch}-" + fingerprint.hexdigest()[:16])
    binaries = build / "install/bin"
    names = ("ffmpeg", "ffprobe", "ffmpeg-LICENSE.txt")
    if not all((binaries / name).is_file() for name in names):
        subprocess.run(["bash", str(script), arch, str(build)], check=True)
    for name in names:
        shutil.copy2(binaries / name, destination / name)


if __name__ == "__main__":
    stage(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
