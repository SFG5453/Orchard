#!/usr/bin/env python3
"""Build a Cargo package and give Meson one predictable archive output."""

import argparse
import shutil
import subprocess
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--target-dir", type=Path, required=True)
    parser.add_argument("--target", default="")
    parser.add_argument("--profile", choices=("debug", "release"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    command = ["cargo", "build", "--package", args.package, "--target-dir", str(args.target_dir)]
    if args.profile == "release":
        command.append("--release")
    if args.target:
        command += ["--target", args.target]
    subprocess.run(command, check=True)

    crate_name = args.package.replace("-", "_")
    archive_name = (crate_name + ".lib" if args.output.suffix == ".lib"
                    else "lib" + crate_name + ".a")
    cargo_output = args.target_dir
    if args.target:
        cargo_output /= args.target
    cargo_output = cargo_output / args.profile / archive_name
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(cargo_output, args.output)


if __name__ == "__main__":
    main()
