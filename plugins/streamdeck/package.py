#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
# A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

"""Package the Orchard Stream Deck plugin and optionally install it in OpenDeck."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent
plugin = root / "dev.sfg.orchard.sdPlugin"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--install-opendeck", action="store_true")
parser.add_argument("--output", type=Path, default=root.parents[1] / "build/streamdeck/Orchard.streamDeckPlugin")
args = parser.parse_args()
subprocess.run(["npm.cmd" if sys.platform == "win32" else "npm", "ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=plugin, check=True)
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
    for filename in sorted(plugin.rglob("*")):
        if filename.is_file():
            archive.write(filename, Path(plugin.name) / filename.relative_to(plugin))
print(f"Packaged {args.output}")
if args.install_opendeck:
    if sys.platform == "win32":
        config = Path(os.environ["APPDATA"])
    elif sys.platform == "darwin":
        config = Path.home() / "Library/Application Support"
    else:
        config = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config")))
    destination = config / "opendeck/plugins" / plugin.name
    shutil.copytree(plugin, destination, dirs_exist_ok=True)
    print(f"Installed {destination}. Restart OpenDeck to load it.")
