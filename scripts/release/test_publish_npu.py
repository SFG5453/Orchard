#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""NPU publishing must validate the complete set and switch the manifest last."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import publish_npu as publisher


class RecordingR2:
    def __init__(self, fail_at=None):
        self.objects = {}
        self.fail_at = fail_at

    def put_object(self, *, Bucket, Key, Body, **metadata):
        if Key == self.fail_at:
            raise RuntimeError("simulated upload failure")
        self.objects[Key] = (Body.read() if hasattr(Body, "read") else Body, metadata)


class PublishNpuTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.manifest = {}
        for arch in publisher.ARCHES:
            data = f"signed APK fixture for {arch}".encode()
            (self.directory / f"orchard-npu-{arch}.apk").write_bytes(data)
            self.manifest[arch] = {"url": f"https://example.test/{arch}.apk",
                                   "sha256": hashlib.sha256(data).hexdigest(),
                                   "size": len(data), "versionCode": 1}
        self.write_manifest()

    def write_manifest(self):
        (self.directory / "npu-addons.json").write_text(json.dumps(self.manifest))

    def test_manifest_is_last_and_points_to_verified_immutable_objects(self):
        store = RecordingR2()
        publisher.publish(store, "bucket", self.directory)
        self.assertEqual(len(store.objects), 7)
        self.assertEqual(list(store.objects)[-1], publisher.MANIFEST_KEY)
        data, metadata = store.objects[publisher.MANIFEST_KEY]
        self.assertIn("no-cache", metadata["CacheControl"])
        for arch, entry in json.loads(data).items():
            key = entry["url"].removeprefix(publisher.BASE_URL + "/")
            apk, metadata = store.objects[key]
            self.assertIn(entry["sha256"], key)
            self.assertEqual(hashlib.sha256(apk).hexdigest(), entry["sha256"])
            self.assertEqual(len(apk), entry["size"])
            self.assertEqual(entry["versionCode"], 1)
            self.assertIn("immutable", metadata["CacheControl"])
            self.assertTrue(key.endswith(f"orchard-npu-{arch}.apk"))

    def test_failed_upload_does_not_publish_manifest(self):
        _, uploads = publisher.prepare(self.directory)
        store = RecordingR2(fail_at=uploads[-1][1])
        with self.assertRaisesRegex(RuntimeError, "simulated upload failure"):
            publisher.publish(store, "bucket", self.directory)
        self.assertNotIn(publisher.MANIFEST_KEY, store.objects)

    def test_corrupt_last_apk_rejects_entire_publication(self):
        apk = self.directory / "orchard-npu-v81.apk"
        apk.write_bytes(b"X" * apk.stat().st_size)
        store = RecordingR2()
        with self.assertRaisesRegex(ValueError, "checksum or size"):
            publisher.publish(store, "bucket", self.directory)
        self.assertFalse(store.objects)

    def test_wrong_size_rejects_entire_publication(self):
        self.manifest["v81"]["size"] += 1
        self.write_manifest()
        store = RecordingR2()
        with self.assertRaisesRegex(ValueError, "checksum or size"):
            publisher.publish(store, "bucket", self.directory)
        self.assertFalse(store.objects)

    def test_missing_generation_is_rejected(self):
        del self.manifest["v81"]
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "all six"):
            publisher.prepare(self.directory)

    def test_invalid_or_mixed_versions_are_rejected(self):
        for version in (0, True, "1", 2):
            with self.subTest(version=version):
                self.manifest["v81"]["versionCode"] = version
                self.write_manifest()
                with self.assertRaises(ValueError):
                    publisher.prepare(self.directory)

    def test_changed_apk_gets_new_url_without_changing_other_urls(self):
        before, _ = publisher.prepare(self.directory)
        data = b"replacement APK"
        (self.directory / "orchard-npu-v68.apk").write_bytes(data)
        self.manifest["v68"].update(sha256=hashlib.sha256(data).hexdigest(), size=len(data))
        self.write_manifest()
        after, _ = publisher.prepare(self.directory)
        self.assertNotEqual(before["v68"]["url"], after["v68"]["url"])
        self.assertEqual(before["v69"], after["v69"])


if __name__ == "__main__":
    unittest.main()
