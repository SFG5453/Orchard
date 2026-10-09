#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Publish both desktop builds to R2, switching the signed channel last."""

import argparse
import ast
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from cryptography.hazmat.primitives import serialization


ROOT = Path(__file__).resolve().parents[2]
RELEASE_TOOL = ROOT / "scripts/release/orchard_release.py"
KEY_ID = "orchard-release-1"


def project_version(path: Path) -> str:
    match = re.search(r"\bproject\([^\n]*\bversion:\s*'([^']+)'", path.read_text())
    if not match:
        raise ValueError(f"Cannot read project version from {path}")
    return match.group(1)


def check_signing_key(key_path: Path) -> None:
    """Reject a release key that the shipped bootstrapper cannot trust."""
    source = (ROOT / "bootstrapper/src/security/TrustedKeys.cpp").read_text()
    match = re.search(r'\{"' + KEY_ID + r'",\s*((?:"(?:\\.|[^"\\])*"\s*)+)\}', source)
    if not match:
        raise ValueError(f"{KEY_ID} is missing from TrustedKeys.cpp")
    public_pem = "".join(ast.literal_eval(part) for part in
                         re.findall(r'"(?:\\.|[^"\\])*"', match.group(1)))
    trusted = serialization.load_pem_public_key(public_pem.encode())
    private = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    actual = private.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    expected = trusted.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if actual != expected:
        raise ValueError("The release signing key does not match the bootstrapper's trusted key")


def run_release(*args: str) -> None:
    subprocess.run(["python3", str(RELEASE_TOOL), *args], check=True)


def missing(error) -> bool:
    return error.response["Error"]["Code"] in ("404", "NoSuchKey", "NotFound")


def remote_bytes(s3, bucket: str, key: str) -> bytes | None:
    from botocore.exceptions import ClientError

    try:
        return s3.get_object(Bucket=bucket, Key=key)["Body"].read()
    except ClientError as error:
        if missing(error):
            return None
        raise


def remote_keys(s3, bucket: str, prefix: str) -> set[str]:
    pages = s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix)
    return {item["Key"] for page in pages for item in page.get("Contents", [])}


def put_file(s3, bucket: str, key: str, path: Path, cache_control: str) -> None:
    metadata = {
        "ContentType": "application/json" if key.endswith(".json") else "application/octet-stream",
        "CacheControl": cache_control,
    }
    if path.stat().st_size > 8 * 1024 * 1024:
        s3.upload_file(str(path), bucket, key, ExtraArgs=metadata)
    else:
        with path.open("rb") as stream:
            s3.put_object(Bucket=bucket, Key=key, Body=stream, **metadata)


def publish(s3, bucket: str, channel: str, linux_tree: Path, windows_tree: Path,
            linux_appimage: Path, windows_archive: Path, linux_boot: Path,
            windows_boot: Path, windows_setup: Path,
            key_path: Path) -> None:
    version = project_version(ROOT / "meson.build")
    bootstrapper_version = project_version(ROOT / "bootstrapper/meson.build")
    notes = json.loads((ROOT / "app/release-notes/releases.json").read_text())
    if not notes or notes[0]["version"] != version:
        raise ValueError("The newest bundled release notes must match the app version")
    if (channel == "canary") != ("-canary" in version):
        raise ValueError(f"Version {version} does not belong on the {channel} channel")
    for path in (linux_tree, windows_tree, linux_appimage, windows_archive,
                 linux_boot, windows_boot, windows_setup):
        if not path.exists():
            raise FileNotFoundError(path)
    check_signing_key(key_path)

    with tempfile.TemporaryDirectory(prefix="orchard-publish-") as workspace:
        out = Path(workspace) / "site"
        (out / "manifests").mkdir(parents=True)
        current_key = f"releases/{channel}.json"
        current = remote_bytes(s3, bucket, current_key)
        if current:
            old_payload = json.loads(base64.b64decode(json.loads(current)["payload"]))
            if old_payload["version"] == version:
                raise ValueError(f"{channel} already points to {version}; bump the version before publishing")
            (out / current_key).parent.mkdir(parents=True)
            (out / current_key).write_bytes(current)

        # Old manifests catch accidental changes to a supposedly immutable component.
        existing_manifests = remote_keys(s3, bucket, "manifests/")
        for remote in sorted(existing_manifests):
            if not re.fullmatch(r"manifests/[^/]+\.json", remote):
                continue
            if remote in (f"manifests/{version}-linux-x86_64.json",
                          f"manifests/{version}-win-x86_64.json"):
                raise ValueError(f"Release manifest already exists: {remote}")
            (out / remote).write_bytes(s3.get_object(Bucket=bucket, Key=remote)["Body"].read())

        for platform, tree in (("linux-x86_64", linux_tree), ("win-x86_64", windows_tree)):
            run_release("manifest", "--version", version, "--platform", platform,
                        "--tree", str(tree), "--layout",
                        str(ROOT / f"scripts/release/layouts/{platform}.json"),
                        "--out", str(out), "--key", str(key_path),
                        "--minimum-bootstrapper", bootstrapper_version)
        run_release("channel", "--channel", channel, "--version", version,
                    "--platform", "linux-x86_64", "--platform", "win-x86_64",
                    "--bootstrapper", f"linux-x86_64={linux_boot}",
                    "--bootstrapper", f"win-x86_64={windows_boot}",
                    "--bootstrapper-version", bootstrapper_version,
                    "--minimum-bootstrapper", bootstrapper_version,
                    "--out", str(out), "--key", str(key_path))

        # The channel file is the ribbon; cut it after all the boxes arrive.
        existing_objects = remote_keys(s3, bucket, "objects/")
        objects = [(f"objects/{path.name}", path) for path in (out / "objects").iterdir()
                   if f"objects/{path.name}" not in existing_objects]
        with ThreadPoolExecutor(max_workers=12) as pool:
            list(pool.map(lambda pair: put_file(s3, bucket, pair[0], pair[1],
                                               "public, max-age=31536000, immutable"), objects))
        for platform in ("linux-x86_64", "win-x86_64"):
            key = f"manifests/{version}-{platform}.json"
            put_file(s3, bucket, key, out / key, "public, max-age=31536000, immutable")
        for name, path in (("orchard", linux_boot), ("Orchard.exe", windows_boot),
                           ("OrchardSetup.exe", windows_setup),
                           ("Orchard-Linux-x86_64.AppImage", linux_appimage),
                           ("Orchard-Windows-x86_64.7z", windows_archive)):
            key = f"downloads/{channel}/{version}/{name}"
            put_file(s3, bucket, key, path, "public, max-age=31536000, immutable")
        put_file(s3, bucket, current_key, out / current_key, "no-store, max-age=0")
        print(f"Published {channel} {version}: {len(objects)} new objects and both manifests")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=("stable", "canary"), required=True)
    parser.add_argument("--linux-tree", type=Path, required=True)
    parser.add_argument("--windows-tree", type=Path, required=True)
    parser.add_argument("--linux-appimage", type=Path, required=True)
    parser.add_argument("--windows-archive", type=Path, required=True)
    parser.add_argument("--linux-bootstrapper", type=Path, required=True)
    parser.add_argument("--windows-bootstrapper", type=Path, required=True)
    parser.add_argument("--windows-setup", type=Path, required=True)
    args = parser.parse_args()
    import boto3

    account = os.environ["R2_ACCOUNT_ID"]
    bucket = os.environ["R2_BUCKET"]
    secret = os.environ["ORCHARD_RELEASE_PRIVATE_KEY"]
    s3 = boto3.client("s3", endpoint_url=f"https://{account}.us.r2.cloudflarestorage.com",
                      aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                      aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"], region_name="auto")
    with tempfile.TemporaryDirectory(prefix="orchard-release-key-") as private_dir:
        key = Path(private_dir) / "signing.pem"
        key.write_text(secret)
        key.chmod(0o600)
        publish(s3, bucket, args.channel, args.linux_tree, args.windows_tree,
                args.linux_appimage, args.windows_archive, args.linux_bootstrapper,
                args.windows_bootstrapper, args.windows_setup, key)


if __name__ == "__main__":
    main()
