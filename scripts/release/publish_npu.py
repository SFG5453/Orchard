#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Publish verified NPU add-ons to R2, switching the download manifest last."""

import argparse
import hashlib
import json
import os
from pathlib import Path


ARCHES = ("v68", "v69", "v73", "v75", "v79", "v81")
PREFIX = "android/npu"
BASE_URL = "https://depot.sfg545.dev"
MANIFEST_KEY = f"{PREFIX}/npu-addons.json"


def prepare(directory: Path) -> tuple[dict, list[tuple[Path, str]]]:
    source = json.loads((directory / "npu-addons.json").read_text())
    if not isinstance(source, dict) or set(source) != set(ARCHES):
        raise ValueError("Manifest must contain all six supported Hexagon generations")
    manifest = {}
    uploads = []
    versions = set()
    for arch in ARCHES:
        entry = source[arch]
        apk = directory / f"orchard-npu-{arch}.apk"
        with apk.open("rb") as data:
            digest = hashlib.file_digest(data, "sha256").hexdigest()
        size = apk.stat().st_size
        if size == 0 or entry["sha256"] != digest or entry["size"] != size:
            raise ValueError(f"{apk.name}: checksum or size does not match manifest")
        version = entry["versionCode"]
        if type(version) is not int or version < 1:
            raise ValueError(f"{apk.name}: invalid versionCode")
        versions.add(version)
        key = f"{PREFIX}/objects/{digest}/{apk.name}"
        manifest[arch] = {"url": f"{BASE_URL}/{key}", "sha256": digest,
                          "size": size, "versionCode": version}
        uploads.append((apk, key))
    if len(versions) != 1:
        raise ValueError("All NPU packages must have the same versionCode")
    return manifest, uploads


def publish(s3, bucket: str, directory: Path) -> None:
    manifest, uploads = prepare(directory)
    for apk, key in uploads:
        with apk.open("rb") as data:
            s3.put_object(Bucket=bucket, Key=key, Body=data,
                          ContentType="application/vnd.android.package-archive",
                          CacheControl="public, max-age=31536000, immutable")
        print(f"Uploaded {apk.name}")
    # Existing manifests keep pointing at unchanged bytes while uploads are in flight.
    s3.put_object(Bucket=bucket, Key=MANIFEST_KEY,
                  Body=(json.dumps(manifest, indent=2) + "\n").encode(),
                  ContentType="application/json", CacheControl="no-cache, max-age=0, must-revalidate")
    print(f"Published {BASE_URL}/{MANIFEST_KEY}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="build.sh output containing APKs and npu-addons.json")
    parser.add_argument("--dry-run", action="store_true", help="validate packages and print the manifest without uploading")
    args = parser.parse_args()
    if args.dry_run:
        manifest, _ = prepare(args.directory)
        print(json.dumps(manifest, indent=2))
        return

    import boto3

    account = os.environ["R2_ACCOUNT_ID"]
    s3 = boto3.client("s3", endpoint_url=f"https://{account}.us.r2.cloudflarestorage.com",
                      aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                      aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"], region_name="auto")
    publish(s3, os.environ["R2_BUCKET"], args.directory)


if __name__ == "__main__":
    main()
