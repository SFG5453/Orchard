#!/usr/bin/env python3
# Copyright (C) 2026 SFG545. SPDX-License-Identifier: AGPL-3.0-or-later
"""Exercise component identities across releases."""

import base64
import json
from pathlib import Path
import tempfile
import unittest
from argparse import Namespace

import orchard_release as release
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


class ComponentVersionTest(unittest.TestCase):
    def test_changed_files_get_a_new_stable_safe_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tree = root / "tree"
            tree.mkdir()
            (tree / "ffmpeg.exe").write_bytes(b"first build")
            layout = root / "layout.json"
            layout.write_text(json.dumps({
                "components": {"ffmpeg": {"version": "9.0.2-orchard-1", "map": [
                    {"from": "ffmpeg.exe"}]}},
                "app": {"map": []}, "launch": {"executable": "orchard.exe"},
            }))
            key = root / "key.pem"
            key.write_bytes(ec.generate_private_key(ec.SECP256R1()).private_bytes(
                serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption()))
            manifests = root / "out" / "manifests"
            manifests.mkdir(parents=True)
            old = {"components": {"ffmpeg": {"version": "9.0.2-orchard-1", "files": []}}}
            (manifests / "old-win-x86_64.json").write_text(json.dumps({
                "payload": base64.b64encode(json.dumps(old).encode()).decode()}))

            def build(version):
                release.cmd_manifest(Namespace(layout=layout, out=root / "out", tree=tree,
                    version=version, platform="win-x86_64", key=key, key_id="test",
                    chunk_size=4, chunk_threshold=8, minimum_bootstrapper=None))
                return release.unwrap(manifests / f"{version}-win-x86_64.json")["components"]["ffmpeg"]

            first = build("1")
            self.assertEqual(first["version"], build("2")["version"])
            self.assertLessEqual(len(first["version"]), 64)
            self.assertRegex(first["version"], r"^[A-Za-z0-9_+.-]+$")
            (tree / "ffmpeg.exe").write_bytes(b"different build")
            changed = build("3")
            self.assertNotEqual(first["version"], changed["version"])
            self.assertEqual(changed["version"], build("4")["version"])


if __name__ == "__main__":
    unittest.main()
