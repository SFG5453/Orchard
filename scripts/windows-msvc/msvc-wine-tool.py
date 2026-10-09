#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TOOLS_DIR = Path(os.environ.get("ORCHARD_WIN_TOOLS", ROOT / ".cache/windows-msvc"))
MSVC_ROOT = Path(os.environ.get("ORCHARD_MSVC_ROOT", TOOLS_DIR / "msvc"))
VC_ROOT = Path(os.environ.get("ORCHARD_MSVC_VC_ROOT", MSVC_ROOT / "VC"))
SDK = Path(os.environ.get("ORCHARD_WINSDK_ROOT", MSVC_ROOT / "Windows Kits/10"))


def newest(parent: Path, override: str, marker: str) -> str:
    if os.environ.get(override):
        return os.environ[override]
    versions = sorted(
        (p.name for p in parent.iterdir() if (p / marker).is_dir() and p.name[0].isdigit()),
        key=lambda name: tuple(int(part) for part in name.split(".") if part.isdigit()),
    ) if parent.is_dir() else []
    if not versions:
        sys.exit(f"msvc-wine-tool: no toolchain under {parent}; run scripts/windows-msvc/setup.sh")
    return versions[-1]


MSVC = VC_ROOT / "Tools/MSVC" / newest(VC_ROOT / "Tools/MSVC", "ORCHARD_MSVC_VERSION", "include")
SDK_VERSION = newest(SDK / "Include", "ORCHARD_WINSDK_VERSION", "um")
TOOLS = {
    "cl": MSVC / "bin/Hostx64/x64/cl.exe",
    "link": MSVC / "bin/Hostx64/x64/link.exe",
    "lib": MSVC / "bin/Hostx64/x64/lib.exe",
    "rc": SDK / f"bin/{SDK_VERSION}/x64/rc.exe",
    "mt": SDK / f"bin/{SDK_VERSION}/x64/mt.exe",
}

# Bindgen runs Linux Clang outside Wine; show it the same C headers as cl.exe.
if sys.argv[1:] == ["--print-bindgen-includes"]:
    print(MSVC / "include")
    print(SDK / f"Include/{SDK_VERSION}/ucrt")
    sys.exit(0)


def windows_path(value: str) -> str:
    return "Z:" + value.replace("/", "\\")


def translate(argument: str) -> str:
    # CMake, Ninja, Cargo, and rustc pass absolute Unix paths to the wrappers.
    # Wine applications need the equivalent path through Wine's Z: mapping.
    if argument.startswith("@/"):
        return "@" + windows_path(argument[1:])
    for prefix in ("/Fo", "/Fe", "/Fd", "/Fa", "/Fi", "/Fp", "/I", "/OUT:", "/PDB:", "/IMPLIB:", "/DEF:", "/MANIFESTFILE:"):
        if argument.startswith(prefix) and argument[len(prefix):].startswith("/"):
            return prefix + windows_path(argument[len(prefix):])
    if argument.startswith("/") and Path(argument).exists():
        return windows_path(argument)
    return argument


tool = os.environ.get("ORCHARD_MSVC_TOOL") or Path(sys.argv[0]).name.removeprefix("msvc-")
executable = TOOLS[tool]
arguments = sys.argv[1:]
if tool == "lib" and arguments and set(arguments[0]) <= set("crs"):
    # Meson speaks Unix ar; lib.exe prefers to be addressed in its own dialect.
    arguments = ["/NOLOGO", "/OUT:" + arguments[1], *arguments[2:]]
os.environ.setdefault("WINEDEBUG", "-all")
os.environ.setdefault(
    "WINEPREFIX", str(TOOLS_DIR / "wine-prefix")
)
os.environ["PATH"] = os.pathsep.join(
    (
        str(MSVC / "bin/Hostx64/x64"),
        str(SDK / f"bin/{SDK_VERSION}/x64"),
        os.environ.get("PATH", ""),
    )
)
os.environ["WINEPATH"] = ";".join(
    windows_path(str(path))
    for path in (MSVC / "bin/Hostx64/x64", SDK / f"bin/{SDK_VERSION}/x64")
)
os.environ["INCLUDE"] = ";".join(
    windows_path(str(path))
    for path in (
        MSVC / "include",
        SDK / f"Include/{SDK_VERSION}/ucrt",
        SDK / f"Include/{SDK_VERSION}/shared",
        SDK / f"Include/{SDK_VERSION}/um",
        SDK / f"Include/{SDK_VERSION}/winrt",
    )
)
os.environ["LIB"] = ";".join(
    windows_path(str(path))
    for path in (
        MSVC / "lib/x64",
        SDK / f"Lib/{SDK_VERSION}/ucrt/x64",
        SDK / f"Lib/{SDK_VERSION}/um/x64",
    )
)
# Wine may start background services which inherit stdout/stderr. If those are
# Ninja pipes, Ninja waits forever for EOF after the compiler has exited. Relay
# through regular temporary files so only this wrapper owns Ninja's pipes.
with tempfile.TemporaryDirectory(prefix="orchard-msvc-") as temporary_dir:
    stdout_path = Path(temporary_dir) / "stdout"
    stderr_path = Path(temporary_dir) / "stderr"
    with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
        result = subprocess.run(
            ["wine", str(executable), *(translate(arg) for arg in arguments)],
            stdout=stdout_file,
            stderr=stderr_file,
            check=False,
        )
    sys.stdout.buffer.write(stdout_path.read_bytes())
    sys.stderr.buffer.write(stderr_path.read_bytes())
# mt.exe uses 0x41020001 to report that /notify_update changed a manifest.
# Unix exposes only its low byte (1), while CMake expects success here.
if tool == "mt" and result.returncode == 1 and "/notify_update" in sys.argv[1:]:
    sys.exit(0)
sys.exit(result.returncode)
