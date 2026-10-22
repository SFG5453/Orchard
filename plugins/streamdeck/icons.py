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

"""Export Orchard's Lucide icons at explicit Stream Deck dimensions."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent
assets = root.parents[1] / "app/qml/assets"
output = root / "dev.sfg.orchard.sdPlugin/icons"
license_header = (root.parents[1] / "app/src/main.cpp").read_text().split("*/", 1)[0]
license_text = "\n".join(line[3:] if line.startswith(" * ") else "" for line in license_header.splitlines()[1:])
glyphs = {"playpause": "play", "pause": "pause", "previous": "skip-back", "next": "skip-forward",
          "nowplaying": "music-2", "volumeup": "volume-2", "volumedown": "volume-2", "mute": "volume-x",
          "shuffle": "shuffle", "repeat": "repeat", "like": "heart", "seekback": "skip-back",
          "seekforward": "skip-forward", "bestmix": "sparkles", "playmix": "list-music", "request": "search"}
for name, glyph in glyphs.items():
    source = (assets / f"lucide/{glyph}.svg").read_text()
    drawing = re.search(r"<svg\b[^>]*>(.*?)</svg>", source, re.S).group(1)
    if name in ("volumeup", "volumedown"):
        drawing = '<path d="m11 5-5 4H3v6h3l5 4z"/><path d="M16 12h7"/>'
        if name == "volumeup":
            drawing += '<path d="M19.5 8.5v7"/>'
    svg = ('<!--\n' + license_text + '\n-->\n<!-- Lucide icons: ISC license; see LICENSE-LUCIDE.txt. -->\n'
           '<svg xmlns="http://www.w3.org/2000/svg" width="144" height="144" viewBox="0 0 144 144">'
           '<rect width="144" height="144" rx="20" fill="#141414"/>'
           '<g transform="translate(40 40) scale(2.666667)" fill="none" stroke="#f2f6f3" '
           'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + drawing + '</g></svg>')
    filename = output / f"{name}.svg"
    filename.write_text(svg)
    subprocess.run(["magick", "-background", "none", str(filename), str(output / f"{name}.png")], check=True)
category = (assets / "lucide/music-2.svg").read_text().replace('stroke="#fff"', 'stroke="#ffffff"')
for size, suffix in [(28, ""), (56, "@2x")]:
    subprocess.run(["magick", "-background", "none", "svg:-", "-resize", f"{size}x{size}", str(output / f"category{suffix}.png")],
                   input=category.encode(), check=True)
