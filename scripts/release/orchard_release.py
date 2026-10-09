#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build signed Orchard releases for the bootstrapper.

  keygen     create an ECDSA P-256 release key
  manifest   split a packaged tree into components, write objects + signed manifest
  channel    publish a version (and optionally a bootstrapper) on a channel

Output mirrors the server: releases/<channel>.json, manifests/<v>-<platform>.json,
objects/<sha256>. Upload the directory as-is; objects are immutable.
See docs/BOOTSTRAPPER.md for the formats.
"""

import argparse
import base64
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import stat
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

SCHEMA = 1
MIB = 1 << 20


def sign(payload: bytes, key_path: Path, key_id: str) -> bytes:
    key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise SystemExit(f"{key_path} is not an ECDSA P-256 key")
    signature = key.sign(payload, ec.ECDSA(hashes.SHA256()))
    envelope = {
        "format": "orchard-signed-v1",
        "keyId": key_id,
        "algorithm": "ecdsa-p256-sha256",
        "payload": base64.b64encode(payload).decode(),
        "signature": base64.b64encode(signature).decode(),
    }
    return (json.dumps(envelope, indent=1) + "\n").encode()


def unwrap(envelope_path: Path) -> dict:
    """Payload of an envelope we wrote earlier (not verified; local files only)."""
    envelope = json.loads(envelope_path.read_text())
    return json.loads(base64.b64decode(envelope["payload"]))


def store_object(objects: Path, data: bytes) -> str:
    digest = hashlib.sha256(data).hexdigest()
    target = objects / digest
    if not target.exists():
        temp = target.with_suffix(".tmp")
        temp.write_bytes(data)
        temp.replace(target)
    return digest


def file_entry(path: Path, rel: str, objects: Path, chunk_size: int, threshold: int) -> dict:
    """One manifest entry. Files above `threshold` become fixed-size chunks.

    The client only sees a list of chunk hashes, so switching to
    content-defined chunking later needs no client change."""
    whole = hashlib.sha256()
    chunks = []
    size = path.stat().st_size
    with path.open("rb") as f:
        while True:
            block = f.read(chunk_size if size > threshold else max(size, 1))
            if not block:
                break
            whole.update(block)
            chunks.append({"sha256": store_object(objects, block), "size": len(block)})
    entry = {"path": rel, "size": size, "sha256": whole.hexdigest()}
    if size > threshold:
        entry["chunks"] = chunks
    if os.access(path, os.X_OK) and not path.is_dir():
        entry["executable"] = True
    return entry


def component_digest(files: list) -> str:
    """Must match componentDigest() in bootstrapper/src/update/Manifest.cpp."""
    h = hashlib.sha256()
    for f in sorted(files, key=lambda f: f["path"].encode()):
        h.update(f"{f['path']}\n{f['size']}\n{f['sha256']}\n{'x' if f.get('executable') else '-'}\n".encode())
    return h.hexdigest()


def map_path(rel: str, rules: list):
    """First rule whose `from` covers `rel` decides the destination path."""
    for rule in rules:
        source = rule["from"].rstrip("/")
        if any(c in source for c in "*?["):
            if fnmatch.fnmatchcase(rel, source):
                return (rule.get("to", "") + "/" + rel.rsplit("/", 1)[-1]).lstrip("/")
        elif rel == source:
            return rule.get("to") or rel.rsplit("/", 1)[-1]
        elif rel.startswith(source + "/") or source == "":
            tail = rel[len(source) + 1:] if source else rel
            return (rule.get("to", "") + "/" + tail).lstrip("/")
    return None


def collect(tree: Path) -> list:
    files = []
    for root, dirs, names in os.walk(tree, followlinks=False):
        dirs.sort()
        for name in sorted(names):
            path = Path(root) / name
            # Symlinks become real files; the client only writes regular files.
            if path.is_file():
                files.append(path.relative_to(tree).as_posix())
    return files


def cmd_manifest(a) -> None:
    layout = json.loads(Path(a.layout).read_text())
    out = Path(a.out)
    objects = out / "objects"
    manifests = out / "manifests"
    objects.mkdir(parents=True, exist_ok=True)
    manifests.mkdir(parents=True, exist_ok=True)
    tree = Path(a.tree)
    excluded = layout.get("exclude", [])
    groups = {name: [] for name in layout.get("components", {})}
    app = []
    for rel in collect(tree):
        if any(fnmatch.fnmatchcase(rel, pattern) for pattern in excluded):
            continue
        for name, spec in layout.get("components", {}).items():
            dest = map_path(rel, spec["map"])
            if dest is not None:
                groups[name].append((rel, dest))
                break
        else:
            dest = map_path(rel, layout["app"]["map"])
            if dest is None:
                raise SystemExit(f"{rel} is not covered by the layout; map or exclude it")
            app.append((rel, dest))

    def entries(pairs):
        seen = set()
        result = []
        for rel, dest in pairs:
            if dest.lower() in seen:
                raise SystemExit(f"two files map to {dest}")
            seen.add(dest.lower())
            result.append(file_entry(tree / rel, dest, objects, a.chunk_size * MIB, a.chunk_threshold * MIB))
        return result

    components = {}
    for name, spec in layout.get("components", {}).items():
        files = entries(groups[name])
        if not files:
            raise SystemExit(f"component {name} matched no files")
        components[name] = {"version": spec["version"], "files": files}
    payload = {
        "schema": SCHEMA,
        "version": a.version,
        "platform": a.platform,
        "files": entries(app),
        "components": components,
        "launch": layout["launch"],
    }
    if layout.get("icon"):
        payload["icon"] = layout["icon"]
    if a.minimum_bootstrapper:
        payload["minimumBootstrapperVersion"] = a.minimum_bootstrapper

    # A component version names one exact set of files, forever.
    for other in manifests.glob(f"*-{a.platform}.json"):
        if other.name == f"{a.version}-{a.platform}.json":
            continue
        for name, comp in unwrap(other).get("components", {}).items():
            mine = components.get(name)
            if mine and mine["version"] == comp["version"] and \
                    component_digest(mine["files"]) != component_digest(comp["files"]):
                raise SystemExit(f"component {name} {comp['version']} differs from {other.name}; bump its version")

    target = manifests / f"{a.version}-{a.platform}.json"
    target.write_bytes(sign(json.dumps(payload, separators=(",", ":")).encode(), Path(a.key), a.key_id))
    total = sum(f["size"] for f in payload["files"]) + sum(
        f["size"] for c in components.values() for f in c["files"])
    print(f"{target}: {len(payload['files'])} app files, {len(components)} components, {total / MIB:.1f} MiB")


def cmd_channel(a) -> None:
    out = Path(a.out)
    releases = out / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    target = releases / f"{a.channel}.json"
    sequence = a.sequence
    if sequence is None:
        sequence = unwrap(target)["sequence"] + 1 if target.exists() else 1
    platforms = {}
    for platform in a.platform:
        manifest = out / "manifests" / f"{a.version}-{platform}.json"
        data = manifest.read_bytes()
        platforms[platform] = {"manifest": f"manifests/{manifest.name}",
                               "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
    payload = {"schema": SCHEMA, "channel": a.channel, "sequence": sequence, "version": a.version,
               "platforms": platforms}
    if a.minimum_bootstrapper:
        payload["minimumBootstrapperVersion"] = a.minimum_bootstrapper
    if a.bootstrapper:
        if not a.bootstrapper_version:
            raise SystemExit("--bootstrapper needs --bootstrapper-version")
        boot = {}
        for spec in a.bootstrapper:
            platform, path = spec.split("=", 1)
            name = "Orchard.exe" if platform.startswith("win") else "orchard"
            entry = file_entry(Path(path), name, out / "objects", 4 * MIB, 8 * MIB)
            entry["executable"] = True
            boot[platform] = entry
        payload["bootstrapper"] = {"version": a.bootstrapper_version, "platforms": boot}
    target.write_bytes(sign(json.dumps(payload, separators=(",", ":")).encode(), Path(a.key), a.key_id))
    print(f"{target}: {a.channel} -> {a.version} (sequence {sequence})")


def cmd_keygen(a) -> None:
    path = Path(a.out)
    if path.exists():
        raise SystemExit(f"{path} exists; refusing to overwrite a signing key")
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, stat.S_IRUSR | stat.S_IWUSR)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    sys.stdout.write(key.public_key().public_bytes(serialization.Encoding.PEM,
                     serialization.PublicFormat.SubjectPublicKeyInfo).decode())


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    k = sub.add_parser("keygen")
    k.add_argument("--out", required=True)
    k.set_defaults(run=cmd_keygen)

    m = sub.add_parser("manifest")
    m.add_argument("--version", required=True)
    m.add_argument("--platform", required=True)
    m.add_argument("--tree", required=True, help="packaged Orchard directory")
    m.add_argument("--layout", required=True, help="component layout JSON")
    m.add_argument("--out", required=True)
    m.add_argument("--key", required=True)
    m.add_argument("--key-id", default="orchard-release-1")
    m.add_argument("--chunk-size", type=int, default=4, help="MiB")
    m.add_argument("--chunk-threshold", type=int, default=8, help="chunk files above this many MiB")
    m.add_argument("--minimum-bootstrapper")
    m.set_defaults(run=cmd_manifest)

    c = sub.add_parser("channel")
    c.add_argument("--channel", required=True)
    c.add_argument("--version", required=True)
    c.add_argument("--platform", action="append", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--key", required=True)
    c.add_argument("--key-id", default="orchard-release-1")
    c.add_argument("--sequence", type=int)
    c.add_argument("--minimum-bootstrapper")
    c.add_argument("--bootstrapper", action="append", help="PLATFORM=PATH")
    c.add_argument("--bootstrapper-version")
    c.set_defaults(run=cmd_channel)

    a = p.parse_args()
    a.run(a)


if __name__ == "__main__":
    main()
