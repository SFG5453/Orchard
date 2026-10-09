#!/usr/bin/env python3
"""Build and stage the adaptive worker after Cargo has finished with the core."""

import argparse
import subprocess
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-dir", type=Path, required=True)
    parser.add_argument("--target", default="")
    parser.add_argument("--profile", choices=("debug", "release"), required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    command = ["cargo", "build", "--package", "orchard-adaptive-mix",
               "--target-dir", str(args.target_dir)]
    if args.profile == "release":
        command.append("--release")
    if args.target:
        command += ["--target", args.target]
    subprocess.run(command, cwd=args.source_root, check=True)

    cargo_output = args.target_dir
    if args.target:
        cargo_output /= args.target
    cargo_output /= args.profile
    subprocess.run([
        sys.executable, str(args.source_root / "scripts/stage-adaptive-mix.py"),
        "--platform", args.platform,
        "--profile", str(cargo_output),
        "--destination", str(args.output.parent),
        "--models", str(args.source_root / "models"),
        "--cache", str(args.source_root / ".cache/adaptive-mix"),
    ], cwd=args.source_root, check=True)
    args.output.touch()


if __name__ == "__main__":
    main()
