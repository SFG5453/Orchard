"""Regression checks for Qt DLL import compatibility in Windows packages."""

from pathlib import Path
import struct
import tempfile
import unittest

from windows_qt_runtime import validate_qt_runtime


PUBLIC_CTOR = "??0QAccessibleInterface@@QEAA@XZ"
PROTECTED_CTOR = "??0QAccessibleInterface@@IEAA@XZ"


def pe_fixture(*, exports=(), imports=(), delayed=False, wide=True):
    """Small PE images with one section and import/export tables."""
    data = bytearray(4096)

    def put(offset, format, *values):
        struct.pack_into("<" + format, data, offset, *values)

    def string(offset, value):
        encoded = value.encode("ascii") + b"\0"
        data[offset:offset + len(encoded)] = encoded

    data[:2] = b"MZ"
    put(0x3c, "I", 0x80)
    data[0x80:0x84] = b"PE\0\0"
    optional_size = 240 if wide else 224
    put(0x86, "H", 1)
    put(0x94, "H", optional_size)
    put(0x98, "H", 0x20b if wide else 0x10b)
    put(0x98 + (24 if wide else 28), "Q" if wide else "I", 0x10000000)
    directories = 0x98 + (112 if wide else 96)
    put(0x98 + optional_size + 8, "IIII", 0xe00, 0x1000, 0xe00, 0x200)

    def rva(offset):
        return offset + 0xe00

    if exports:
        put(directories, "II", rva(0x200), 0x100)
        put(0x200, "IIHHIIIIIII", 0, 0, 0, 0, 0, 1, len(exports), len(exports),
            rva(0x240), rva(0x280), rva(0x2c0))
        for index, symbol in enumerate(exports):
            put(0x240 + index * 4, "I", rva(0xf00))
            put(0x280 + index * 4, "I", rva(0x300 + index * 128))
            put(0x2c0 + index * 2, "H", index)
            string(0x300 + index * 128, symbol)
    if imports:
        dll, symbols = imports
        put(directories + (13 if delayed else 1) * 8, "II", rva(0x600), 64)
        if delayed:
            put(0x600, "IIIIIIII", 1, rva(0x680), 0, rva(0x700), rva(0x700), 0, 0, 0)
        else:
            put(0x600, "IIIII", rva(0x700), 0, 0, rva(0x680), rva(0x700))
        string(0x680, dll)
        for index, symbol in enumerate(symbols):
            address = 0x800 + index * 128
            value = ((1 << (63 if wide else 31)) | symbol if isinstance(symbol, int)
                     else rva(address))
            put(0x700 + index * (8 if wide else 4), "Q" if wide else "I", value)
            if isinstance(symbol, str):
                string(address + 2, symbol)
    return data


class WindowsQtRuntimeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.stage = Path(temp.name)

    def write(self, name, **kwargs):
        path = self.stage / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(pe_fixture(**kwargs))

    def test_matching_symbols_with_case_insensitive_dll_names(self):
        self.write("Qt6Gui.dll", exports=(PUBLIC_CTOR,))
        self.write("Qt6WebEngineCore.dll", imports=("QT6GUI.DLL", (PUBLIC_CTOR,)))
        validate_qt_runtime(self.stage)

    def test_public_constructor_cannot_resolve_to_protected_constructor(self):
        self.write("Qt6Gui.dll", exports=(PROTECTED_CTOR,))
        self.write("Qt6WebEngineCore.dll", imports=("Qt6Gui.dll", (PUBLIC_CTOR,)))
        with self.assertRaisesRegex(SystemExit, "Qt6WebEngineCore.dll -> Qt6Gui.dll") as result:
            validate_qt_runtime(self.stage)
        self.assertIn(PUBLIC_CTOR, str(result.exception))

    def test_nested_plugin_with_missing_dependency_fails(self):
        self.write("webview/backend.dll", imports=("Qt6WebEngineCore.dll", ("missing",)))
        with self.assertRaisesRegex(SystemExit, "Qt6WebEngineCore.dll: missing"):
            validate_qt_runtime(self.stage)

    def test_delayed_imports_and_ordinals(self):
        self.write("Qt6Gui.dll", exports=(PUBLIC_CTOR,))
        self.write("orchard.exe", imports=("Qt6Gui.dll", (PUBLIC_CTOR, 1)), delayed=True)
        validate_qt_runtime(self.stage)
        self.write("orchard.exe", imports=("Qt6Gui.dll", (2,)), delayed=True)
        with self.assertRaisesRegex(SystemExit, "Qt6Gui.dll: 2"):
            validate_qt_runtime(self.stage)

    def test_pe32_and_system_imports(self):
        self.write("Qt6Gui.dll", exports=(PUBLIC_CTOR,), wide=False)
        self.write("orchard.exe", imports=("Qt6Gui.dll", (PUBLIC_CTOR,)), wide=False)
        self.write("system.dll", imports=("kernel32.dll", ("SystemFunction",)), wide=False)
        validate_qt_runtime(self.stage)

    def test_malformed_binary_fails(self):
        (self.stage / "orchard.exe").write_bytes(b"broken")
        with self.assertRaisesRegex(SystemExit, "Cannot inspect Windows runtime"):
            validate_qt_runtime(self.stage)


if __name__ == "__main__":
    unittest.main()
