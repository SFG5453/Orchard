#!/usr/bin/env python3
"""Reject staged Windows binaries with unresolved Qt imports, without loading DLLs."""

import argparse
from contextlib import contextmanager
import mmap
from pathlib import Path
import struct


class PeImage:
    def __init__(self, data):
        self.data = data
        header = self.unpack("I", 0x3c)[0]
        if data[:2] != b"MZ" or data[header:header + 4] != b"PE\0\0":
            raise ValueError("Invalid PE signature")
        sections = self.unpack("H", header + 6)[0]
        optional_size = self.unpack("H", header + 20)[0]
        optional = header + 24
        magic = self.unpack("H", optional)[0]
        if magic not in (0x10b, 0x20b):
            raise ValueError("Unsupported PE optional header")
        self.wide = magic == 0x20b
        self.base = self.unpack("Q" if self.wide else "I",
                                optional + (24 if self.wide else 28))[0]
        directory = optional + (112 if self.wide else 96)
        self.directories = [self.unpack("II", directory + i * 8) for i in range(16)]
        self.sections = []
        for i in range(sections):
            size, rva, raw_size, offset = self.unpack(
                "IIII", optional + optional_size + i * 40 + 8)
            self.sections.append((rva, max(size, raw_size), offset))

    def unpack(self, format, offset):
        return struct.unpack_from("<" + format, self.data, offset)

    def offset(self, rva):
        for start, size, offset in self.sections:
            if start <= rva < start + size:
                return offset + rva - start
        raise ValueError(f"Unmapped PE address: {rva:#x}")

    def string(self, rva):
        offset = self.offset(rva)
        end = self.data.find(b"\0", offset)
        if end < 0:
            raise ValueError("Unterminated PE string")
        return self.data[offset:end].decode("ascii")

    def exports(self):
        rva, _ = self.directories[0]
        if not rva:
            return set()
        fields = self.unpack("IIHHIIIIIII", self.offset(rva))
        base, count, names, functions, name_table, ordinals = fields[5:]
        symbols = {base + i for i in range(count)
                   if self.unpack("I", self.offset(functions) + i * 4)[0]}
        for i in range(names):
            ordinal = self.unpack("H", self.offset(ordinals) + i * 2)[0]
            if base + ordinal in symbols:
                symbols.add(self.string(self.unpack("I", self.offset(name_table) + i * 4)[0]))
        return symbols

    def imports(self):
        for directory, delayed in ((1, False), (13, True)):
            rva, _ = self.directories[directory]
            if not rva:
                continue
            offset = self.offset(rva)
            format = "IIIIIIII" if delayed else "IIIII"
            while True:
                fields = self.unpack(format, offset)
                if not any(fields):
                    break
                adjustment = self.base if delayed and not fields[0] & 1 else 0
                name = fields[1] if delayed else fields[3]
                table = fields[4] if delayed else fields[0] or fields[4]
                dll = self.string(name - adjustment)
                if dll.lower().startswith("qt6"):
                    thunk = self.offset(table - adjustment)
                    size = 8 if self.wide else 4
                    ordinal_bit = 1 << (size * 8 - 1)
                    while value := self.unpack("Q" if self.wide else "I", thunk)[0]:
                        symbol = value & 0xffff if value & ordinal_bit else self.string(
                            value - adjustment + 2)
                        yield dll, symbol
                        thunk += size
                offset += struct.calcsize("<" + format)


@contextmanager
def read_image(path):
    with path.open("rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        yield PeImage(data)


def validate_qt_runtime(stage: Path) -> None:
    libraries = {path.name.lower(): path for path in stage.iterdir()
                 if path.suffix.lower() == ".dll"}
    exports = {}
    failures = []
    for path in sorted(stage.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in (".dll", ".exe"):
            continue
        try:
            with read_image(path) as image:
                imports = list(image.imports())
            for dll, symbol in imports:
                key = dll.lower()
                if key not in exports:
                    if key in libraries:
                        with read_image(libraries[key]) as image:
                            exports[key] = image.exports()
                    else:
                        exports[key] = set()
                if symbol not in exports[key]:
                    failures.append(f"{path.relative_to(stage)} -> {dll}: {symbol}")
        except (ValueError, struct.error) as error:
            raise SystemExit(f"Cannot inspect Windows runtime {path}: {error}") from error
    if failures:
        raise SystemExit("Unresolved Qt imports; use a matching Qt SDK and rebuild:\n" +
                         "\n".join(failures))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()
    validate_qt_runtime(args.stage)
    print("Windows Qt imports verified")
