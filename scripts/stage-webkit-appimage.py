#!/usr/bin/env python3
# Copyright (C) 2026 SFG545. SPDX-License-Identifier: AGPL-3.0-or-later
"""Stage WebKitGTK's matching subprocesses and make its compiled paths relative."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess


def stage(appdir: Path, libdir: Path) -> None:
    library = (appdir / 'usr/lib/libwebkit2gtk-4.1.so.0').resolve(strict=True)
    data = library.read_bytes()
    candidates = [libdir / 'webkit2gtk-4.1', Path('/usr/libexec/webkit2gtk-4.1'),
                  Path('/usr/lib/webkit2gtk-4.1')]
    roots = list(dict.fromkeys(p for p in candidates if str(p).encode() in data and p.is_dir()))
    if len(roots) != 1 or not str(roots[0]).startswith('/usr/'):
        raise SystemExit(f'Could not identify a unique WebKit process directory: {roots}')
    root = roots[0]
    for name in ('WebKitNetworkProcess', 'WebKitWebProcess',
                 'injected-bundle/libwebkit2gtkinjectedbundle.so'):
        source = root / name
        if not source.is_file():
            raise SystemExit(f'Missing WebKit runtime file: {source}')
    destination = appdir / str(root).lstrip('/')
    for name in ('WebKitNetworkProcess', 'WebKitWebProcess', 'WebKitGPUProcess',
                 'injected-bundle/libwebkit2gtkinjectedbundle.so'):
        source = root / name
        if source.is_file():
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    old = str(root).encode()
    new = b'././' + old[4:]
    if len(new) != len(old):
        raise SystemExit('WebKit path replacement changes binary size')
    mode = library.stat().st_mode
    os.chmod(library, mode | 0o200)
    try:
        library.write_bytes(data.replace(old, new))
    finally:
        os.chmod(library, mode)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('appdir', type=Path)
    args = parser.parse_args()
    libdir = subprocess.check_output(['pkg-config', '--variable=libdir', 'webkit2gtk-4.1'], text=True).strip()
    stage(args.appdir, Path(libdir))


if __name__ == '__main__':
    main()
