# Orchard Packages

Orchard Packages is a small Neutralino desktop installer backed by a compiled
Go extension. Bun and Go are build dependencies only; release bundles and the
Arch package contain a standalone backend executable.

## Development

```bash
bun install
bun run dev
```

The backend streams downloads to `.part` files, verifies the manifest byte size
and SHA-256, extracts Zstandard tar archives itself into a same-filesystem
staging directory, validates the composed Orchard installation, and swaps it
into place only after validation. It rejects absolute paths, parent traversal,
and archive links.

## Builds

Compile every Go extension target:

```bash
bun run build:backend all
```

Or compile one normalized target:

```bash
bun run build:backend linux-x64
bun run build:backend linux-arm64
bun run build:backend win32-x64
bun run build:backend win32-arm64
bun run build:backend darwin-x64
bun run build:backend darwin-arm64
```

Build portable Neutralino bundles for every shell published by Neutralino:

```bash
bun run build:release all
```

The resulting bundles are under `dist/releases/`. Neutralino currently does not
publish a Windows ARM64 shell, so `win32-arm64` has a standalone backend build
but no claimed desktop bundle. No substitute or fabricated shell is emitted.

## Orchard payloads

From the repository root:

```bash
bun run package:orchard 5.0.0
```

This rebuilds Orchard, installs a clean production dependency tree, separates
native binaries by inspected binary format, and writes only uploadable files to
`artifacts/r2/`. The manifest's `electronVersion` comes from the exact Electron
version in Orchard's root `package.json`.

Electron itself is downloaded from Electron's official GitHub release, checked
against the official `SHASUMS256.txt`, and stored once under Orchard's reusable
runtime directory. Orchard `5.x` releases install into the `versions/5.0.0`
slot and reuse the same matching Electron runtime.

## Arch package

Build the current x86_64 test package from the repository root:

```bash
bun run package:orchard-packages:arch
```

The finished package is copied to `artifacts/arch/`. The generated local source
archive remains beside `packaging/arch/PKGBUILD`, so the same build can be run
again from that directory with:

```bash
makepkg -si
```
