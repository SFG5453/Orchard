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

"""Regenerates assets/ from the Cut O mark, matching the vector drawable exactly.

Geometry lives in the 108dp adaptive-icon space: a ring of radius 25 stroked 18 wide (so it
spans radius 16..34), broken by a 42-degree gap, the whole mark rotated -8 degrees. Because the
mark is a ring, the rotation is applied by shifting the arc angles rather than rotating pixels.
"""
from PIL import Image, ImageDraw
from sys import argv

BG = (13, 59, 51, 255)      # #0D3B33
FG = (233, 245, 216, 255)   # #E9F5D8
SS = 8                      # supersampling; PIL's arc has no anti-aliasing

CENTER, OUTER, WIDTH = 54.0, 34.0, 18.0
START, END = -27.0, 291.0   # 318 degrees of arc, gap at the upper right, -8 rotation baked in


def mark(size, fill, scale=1.0):
    """The mark alone on transparency, drawn in 108-space scaled to `size`."""
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    unit = size * SS / 108.0
    c, r, w = CENTER * unit, OUTER * unit * scale, WIDTH * unit * scale
    draw.arc([c - r, c - r, c + r, c + r], START, END, fill=fill, width=round(w))
    return img.resize((size, size), Image.LANCZOS)


def flatten(layer, size):
    return Image.alpha_composite(Image.new("RGBA", (size, size), BG), layer)


def save(img, name):
    img.save(f"{argv[0]}/../assets/{name}")
    print("wrote", name, img.size)


# Adaptive layers: mark stays inside the 66dp safe zone, background is flat.
save(mark(512, FG), "android-icon-foreground.png")
save(Image.new("RGBA", (512, 512), BG), "android-icon-background.png")
save(mark(432, (255, 255, 255, 255)), "android-icon-monochrome.png")

# Standalone icons: no launcher mask to clear, so the mark fills more of the canvas.
save(flatten(mark(1024, FG, scale=1.42), 1024), "icon.png")
save(flatten(mark(48, FG, scale=1.42), 48), "favicon.png")
# Splash icons are masked to a circle by the system, so this one keeps the original's framing.
save(mark(1024, FG, scale=0.86), "splash-icon.png")
