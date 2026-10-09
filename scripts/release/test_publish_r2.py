#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check that a failed upload cannot move the signed channel pointer."""

import importlib.util
import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("publish_r2", Path(__file__).with_name("publish_r2.py"))
PUBLISH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH)
VERSION = "0.3.0-canary.1"


class RecordingR2:
    def __init__(self, fail_at=None):
        self.keys = []
        self.fail_at = fail_at

    def put_object(self, *, Bucket, Key, Body, **_kwargs):
        if Key == self.fail_at:
            raise RuntimeError("simulated R2 write failure")
        self.keys.append(Key)
        Body.read()


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        (self.root / "app/release-notes").mkdir(parents=True)
        (self.root / "bootstrapper").mkdir()
        (self.root / "meson.build").write_text(f"project('Orchard', version: '{VERSION}')\n")
        (self.root / "bootstrapper/meson.build").write_text(
            "project('orchard-bootstrapper', version: '1.0.1')\n")
        (self.root / "app/release-notes/releases.json").write_text(
            json.dumps([{"version": VERSION}]))
        self.paths = [Path(self.temp.name) / name for name in
                      ("linux", "windows", "Orchard-Linux-x86_64.AppImage",
                       "Orchard-Windows-x86_64.7z", "orchard", "Orchard.exe",
                       "OrchardSetup.exe", "signing.pem")]
        for path in self.paths[:2]:
            path.mkdir()
        for path in self.paths[2:]:
            path.write_bytes(b"placeholder")

    @staticmethod
    def fake_release(*args):
        out = Path(args[args.index("--out") + 1])
        if args[0] == "manifest":
            platform = args[args.index("--platform") + 1]
            (out / "manifests" / f"{VERSION}-{platform}.json").write_bytes(b"signed manifest")
            (out / "objects").mkdir(exist_ok=True)
            (out / "objects" / "012345").write_bytes(b"object")
        else:
            (out / "releases").mkdir(exist_ok=True)
            (out / "releases" / "canary.json").write_bytes(b"signed channel")

    def run_publish(self, store, current=None):
        with patch.object(PUBLISH, "check_signing_key"), \
                patch.object(PUBLISH, "ROOT", self.root), \
                patch.object(PUBLISH, "remote_bytes", return_value=current), \
                patch.object(PUBLISH, "remote_keys", return_value=set()), \
                patch.object(PUBLISH, "run_release", side_effect=self.fake_release):
            PUBLISH.publish(store, "release-bucket", "canary", *self.paths)

    def test_channel_is_last_after_both_platforms_and_installers(self):
        store = RecordingR2()
        self.run_publish(store)
        self.assertEqual(store.keys[0], "objects/012345")
        self.assertEqual(store.keys[-1], "releases/canary.json")
        self.assertLess(store.keys.index(f"manifests/{VERSION}-linux-x86_64.json"),
                        store.keys.index(f"downloads/canary/{VERSION}/orchard"))
        self.assertIn(f"manifests/{VERSION}-win-x86_64.json", store.keys)
        self.assertIn(f"downloads/canary/{VERSION}/OrchardSetup.exe", store.keys)
        self.assertIn(f"downloads/canary/{VERSION}/Orchard-Linux-x86_64.AppImage", store.keys)
        self.assertIn(f"downloads/canary/{VERSION}/Orchard-Windows-x86_64.7z", store.keys)

    def test_failed_manifest_upload_leaves_channel_untouched(self):
        store = RecordingR2(fail_at=f"manifests/{VERSION}-win-x86_64.json")
        with self.assertRaisesRegex(RuntimeError, "simulated R2"):
            self.run_publish(store)
        self.assertNotIn("releases/canary.json", store.keys)

    def test_reusing_a_published_version_is_rejected_before_upload(self):
        payload = base64.b64encode(json.dumps({"version": VERSION}).encode()).decode()
        current = json.dumps({"payload": payload}).encode()
        store = RecordingR2()
        with self.assertRaisesRegex(ValueError, "already points"):
            self.run_publish(store, current=current)
        self.assertEqual(store.keys, [])


if __name__ == "__main__":
    unittest.main()
