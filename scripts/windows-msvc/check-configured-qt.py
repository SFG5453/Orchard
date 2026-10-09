#!/usr/bin/env python3
"""Reject a Meson build directory configured against a different Windows Qt kit."""

import json
import sys
from pathlib import Path


def main() -> None:
    build_dir = Path(sys.argv[1])
    qt_root = Path(sys.argv[2]).resolve()
    options_file = build_dir / "meson-info" / "intro-buildoptions.json"
    if not options_file.is_file():
        return

    options = json.loads(options_file.read_text())
    prefixes = next(
        (item["value"] for item in options
         if item["name"] == "cmake_prefix_path" and item["machine"] == "host"),
        [],
    )
    if qt_root not in (Path(prefix).resolve() for prefix in prefixes):
        # Meson remembers CMake packages; reconfiguration will keep the old Qt at the party.
        sys.exit(
            f"windows-msvc: {build_dir} uses Qt from {prefixes}, expected {qt_root}; "
            "select a fresh ORCHARD_MSVC_BUILD_DIR before configuring or packaging"
        )


if __name__ == "__main__":
    main()
