#!/usr/bin/env python3
"""Restore the optional desktop Apple Music logo from the CI secret."""

import base64
import os
from pathlib import Path


encoded = os.environ.get("APPLE_MUSIC_LOGO_PNG_BASE64", "")
if not encoded:
    print("::warning::APPLE_MUSIC_LOGO_PNG_BASE64 is not set; building without the Apple Music logo.")
else:
    data = base64.b64decode("".join(encoded.split()), validate=True)
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SystemExit("APPLE_MUSIC_LOGO_PNG_BASE64 does not contain a PNG")
    target = Path("mobile/android/local/apple-music/apple-music.png")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
