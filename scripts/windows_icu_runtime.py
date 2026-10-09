#!/usr/bin/env python3
"""Stage the Windows ICU runtime used by the MSVC Qt kit."""

import argparse
import os
from pathlib import Path
import shutil


ICU_DLLS = ("icu.dll", "icuuc.dll", "icuin.dll")


def copy_icu_runtime(stage: Path, system32: Path | None = None) -> None:
    if system32 is None:
        system_root = os.environ.get("SystemRoot")
        if not system_root:
            raise SystemExit("SystemRoot is missing; specify the Windows System32 directory")
        system32 = Path(system_root) / "System32"
    inputs = [system32 / name for name in ICU_DLLS]
    for path in inputs:
        if not path.is_file():
            raise SystemExit(f"Missing Windows ICU runtime: {path}")
        with path.open("rb") as dll:
            if b"Wine builtin DLL" in dll.read(96):
                raise SystemExit(f"Use a native Windows ICU runtime, not a Wine builtin: {path}")
    # The legacy ICU DLLs forward exports to the combined icu.dll.
    for path in inputs:
        shutil.copy2(path, stage / path.name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--system32", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()
    copy_icu_runtime(args.stage, args.system32)
