#!/usr/bin/env python3
"""Embed generated files under stable Qt resource aliases."""

import argparse
import subprocess
import tempfile
from pathlib import Path
from xml.sax.saxutils import escape


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rcc", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("inputs", nargs="+")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as temp:
        qrc = Path(temp) / (args.name + ".qrc")
        entries = []
        for input_name in args.inputs:
            source = Path(input_name).resolve()
            entries.append(f'    <file alias="{escape(source.name)}">{escape(str(source))}</file>')
        qrc.write_text('<RCC>\n  <qresource prefix="' + escape(args.prefix)
                       + '">\n' + '\n'.join(entries)
                       + '\n  </qresource>\n</RCC>\n')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([args.rcc, "-name", args.name, "--compress-algo", "zlib",
                        "-o", str(args.output), str(qrc)], check=True)


if __name__ == "__main__":
    main()
