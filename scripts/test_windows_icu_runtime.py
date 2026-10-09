"""Regression checks for portable Windows ICU deployment."""

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from windows_icu_runtime import copy_icu_runtime
from release.orchard_release import map_path


class WindowsIcuRuntimeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.system32 = self.root / "Windows" / "System32"
        self.system32.mkdir(parents=True)
        self.stage = self.root / "Orchard"
        self.stage.mkdir()
        self.runtime = {"icu.dll": b"combined runtime", "icuuc.dll": b"common forwarder",
                        "icuin.dll": b"international forwarder"}
        for name, data in self.runtime.items():
            (self.system32 / name).write_bytes(data)

    def test_portable_runtime_and_forwarders_are_in_signed_qt_component(self):
        copy_icu_runtime(self.stage, self.system32)
        layout = json.loads((Path(__file__).parent / "release/layouts/win-x86_64.json")
                            .read_text())
        for name, data in self.runtime.items():
            self.assertEqual((self.stage / name).read_bytes(), data)
            self.assertEqual(map_path(name, layout["components"]["qt"]["map"]), name)

    def test_missing_combined_runtime_fails_without_copying_forwarders(self):
        (self.system32 / "icu.dll").unlink()
        with self.assertRaisesRegex(SystemExit, "Missing Windows ICU runtime:.*icu.dll"):
            copy_icu_runtime(self.stage, self.system32)
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_incomplete_input_does_not_overwrite_staged_runtime(self):
        (self.stage / "icu.dll").write_bytes(b"staged runtime")
        (self.system32 / "icuin.dll").unlink()
        with self.assertRaisesRegex(SystemExit, "Missing Windows ICU runtime:.*icuin.dll"):
            copy_icu_runtime(self.stage, self.system32)
        self.assertEqual((self.stage / "icu.dll").read_bytes(), b"staged runtime")

    def test_native_packaging_uses_system_root(self):
        with patch.dict(os.environ, {"SystemRoot": str(self.system32.parent)}):
            copy_icu_runtime(self.stage)
        self.assertEqual((self.stage / "icu.dll").read_bytes(), self.runtime["icu.dll"])

    def test_wine_builtin_cannot_enter_portable_windows_archive(self):
        (self.system32 / "icu.dll").write_bytes(b"MZ" + bytes(62) + b"Wine builtin DLL")
        with self.assertRaisesRegex(SystemExit, "Use a native Windows ICU runtime"):
            copy_icu_runtime(self.stage, self.system32)
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_missing_system_root_has_actionable_error(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(SystemExit, "SystemRoot is missing"):
                copy_icu_runtime(self.stage)


if __name__ == "__main__":
    unittest.main()
