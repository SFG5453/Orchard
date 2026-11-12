#!/usr/bin/env python3
"""Render the macOS app icon and disk image background into packaging/macos.

Run with Pillow available, e.g. `uv run --with pillow python scripts/macos-artwork.py`.
The outputs are committed so builds need neither Pillow nor this script.
"""
import math
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).resolve().parent.parent / "packaging/macos"
GREEN = (0x2F, 0xDF, 0x93, 255)

# Geometry of mobile/android/app/src/main/res/drawable/ic_widget_orchard.xml in its 108 unit
# viewport: a 25 unit radius arc stroked 18 wide with butt caps, rotated -8 degrees.
CENTER, RADIUS, STROKE, ROTATION = 54.0, 25.0, 18.0, -8.0
START, END = (77.64, 45.86), (66.12, 32.13)


def arc_polygon(scale, offset):
    """Outline of the stroked arc in screen coordinates (y down)."""
    a0 = math.atan2(START[1] - CENTER, START[0] - CENTER)
    a1 = math.atan2(END[1] - CENTER, END[0] - CENTER)
    if a1 <= a0:
        a1 += 2 * math.pi
    turn = math.radians(ROTATION)
    steps = 720

    def ring(r, forward):
        points = []
        for i in range(steps + 1):
            t = i / steps if forward else 1 - i / steps
            a = a0 + (a1 - a0) * t + turn
            points.append((offset[0] + (CENTER + r * math.cos(a)) * scale,
                           offset[1] + (CENTER + r * math.sin(a)) * scale))
        return points

    return ring(RADIUS + STROKE / 2, True) + ring(RADIUS - STROKE / 2, False)


def mark(size, color, diameter, center=None, supersample=4):
    """The mark on a transparent canvas, its outer diameter `diameter` pixels."""
    n = size * supersample
    cx, cy = center or (size / 2, size / 2)
    scale = diameter * supersample / (2 * RADIUS + STROKE)
    offset = (cx * supersample - CENTER * scale, cy * supersample - CENTER * scale)
    image = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(image).polygon(arc_polygon(scale, offset), fill=color)
    return image.resize((size, size), Image.LANCZOS)


def icon(size):
    # Apple's 1024 grid keeps artwork inside 824; a bare mark fills a little less of it.
    glyph = mark(size, GREEN, size * 0.80)
    shadow = Image.new("RGBA", glyph.size, (0, 0, 0, 0))
    shadow.putalpha(glyph.getchannel("A").point(lambda v: v * 0.28))
    shadow = shadow.filter(ImageFilter.GaussianBlur(size / 90))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(shadow, (0, round(size / 100)))
    canvas.alpha_composite(glyph)
    return canvas


def write_icns():
    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "Orchard.iconset"
        iconset.mkdir()
        for base in (16, 32, 128, 256, 512):
            icon(base).save(iconset / f"icon_{base}x{base}.png")
            icon(base * 2).save(iconset / f"icon_{base}x{base}@2x.png")
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(OUT / "Orchard.icns")],
                       check=True)


# Finder window content size in points; dmg-settings.py places the icons on this layout.
WIDTH, HEIGHT = 640, 400
APP_X, APPS_X, ICON_Y = 170, 470, 190


def background(scale):
    w, h = WIDTH * scale, HEIGHT * scale
    top, bottom = (250, 252, 248), (233, 243, 236)
    image = Image.new("RGBA", (w, h))
    draw = ImageDraw.Draw(image)
    for y in range(h):
        t = y / (h - 1)
        draw.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))

    watermark = mark(w, (*GREEN[:3], 22), h * 1.15, center=(w * 0.92, h * 1.02))
    image.alpha_composite(watermark)

    # Arrow between the two icon slots, clear of the 128pt icons.
    y = ICON_Y * scale
    x0, x1 = (APP_X + 88) * scale, (APPS_X - 88) * scale
    ink = (118, 160, 144)
    width = 5 * scale
    draw = ImageDraw.Draw(image)
    draw.line([(x0, y), (x1 - 6 * scale, y)], fill=ink, width=width)
    head = 16 * scale
    draw.polygon([(x1, y), (x1 - head, y - head * 0.7), (x1 - head, y + head * 0.7)], fill=ink)

    font = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", 15 * scale)
    text = "Drag Orchard to Applications to install"
    box = draw.textbbox((0, 0), text, font=font)
    draw.text(((w - (box[2] - box[0])) / 2, 318 * scale), text, font=font, fill=(52, 84, 74))
    return image.convert("RGB")


def write_background():
    with tempfile.TemporaryDirectory() as tmp:
        one, two = Path(tmp) / "bg.png", Path(tmp) / "bg@2x.png"
        background(1).save(one, dpi=(72, 72))
        background(2).save(two, dpi=(144, 144))
        subprocess.run(["tiffutil", "-cathidpicheck", str(one), str(two),
                        "-out", str(OUT / "dmg-background.tiff")], check=True)


if __name__ == "__main__":
    if not shutil.which("iconutil"):
        raise SystemExit("macos-artwork: needs macOS iconutil and tiffutil")
    write_icns()
    write_background()
    print(f"macos-artwork: wrote {OUT}/Orchard.icns and dmg-background.tiff")
