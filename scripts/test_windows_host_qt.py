"""Check Linux host Qt selection for the Windows cross build."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
ENV = ROOT / "scripts/windows-msvc/env.sh"


@unittest.skipUnless(sys.platform == "linux", "Linux cross-build scripts")
class WindowsHostQtTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.env = dict(os.environ)
        for name in ("ORCHARD_QT_HOST_PATH", "ORCHARD_MSVC_BUILD_DIR"):
            self.env.pop(name, None)
        self.env.update(ORCHARD_WIN_TOOLS=str(self.root), ORCHARD_QT_VERSION="99.1")

    def sdk(self, path, version):
        config = path / "lib/cmake/Qt6Core/Qt6CoreConfigVersionImpl.cmake"
        config.parent.mkdir(parents=True)
        config.write_text(f'set(PACKAGE_VERSION "{version}")\n')

    def shell(self, code):
        return subprocess.run(["bash", "-euc", 'source "$1"\n' + code, "test", str(ENV)],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_env_can_load_without_installed_host_sdk(self):
        result = self.shell('printf "%s\\n" "$ORCHARD_QT_HOST_PATH" "$ORCHARD_MSVC_BUILD_DIR"')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(str(self.root / "host-qt/99.1/gcc_64"), result.stdout)
        self.assertIn("build-windows-msvc-99.1", result.stdout)

    def test_matching_cached_sdk_sets_tool_and_pkgconfig_paths(self):
        sdk = self.root / "host-qt/99.1/gcc_64"
        self.sdk(sdk, "99.1")
        result = self.shell('orchard_ensure_host_qt\nprintf "%s\\n" "$PATH" "$PKG_CONFIG_PATH"')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"{sdk}/libexec:{sdk}/bin:", result.stdout)
        self.assertIn(f"{sdk}/lib/pkgconfig", result.stdout)

    def test_explicit_incompatible_sdk_is_rejected_without_download(self):
        sdk = self.root / "custom"
        self.sdk(sdk, "98.0")
        self.env["ORCHARD_QT_HOST_PATH"] = str(sdk)
        result = self.shell('orchard_aqt() { echo unexpected-download; }\norchard_ensure_host_qt')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("host Qt 98.0", result.stderr)
        self.assertNotIn("unexpected-download", result.stdout)

    def test_missing_default_sdk_installs_once(self):
        result = self.shell('''
orchard_aqt() {
    printf '%s\\n' "$*" >> "$ORCHARD_WIN_TOOLS/downloads.txt"
    local config="$ORCHARD_QT_HOST_PATH/lib/cmake/Qt6Core/Qt6CoreConfigVersionImpl.cmake"
    mkdir -p "$(dirname "$config")"
    printf 'set(PACKAGE_VERSION "99.1")\\n' > "$config"
}
orchard_ensure_host_qt
orchard_ensure_host_qt
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = (self.root / "downloads.txt").read_text().splitlines()
        self.assertEqual(len(calls), 1)
        self.assertIn("install-qt linux desktop 99.1 linux_gcc_64", calls[0])
        self.assertIn("-m qtshadertools --archives qtbase qtdeclarative icu", calls[0])


if __name__ == "__main__":
    unittest.main()
