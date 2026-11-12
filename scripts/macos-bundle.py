#!/usr/bin/env python3
"""Assemble a signed development Orchard.app from the build directory.

The bundle links Qt from the SDK through an added rpath rather than copying the
frameworks, so it only runs on the machine that built it.
"""
import argparse
import hashlib
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--executable", type=Path, required=True)
p.add_argument("--helper", type=Path, action="append", default=[])
p.add_argument("--info-plist", type=Path, required=True)
p.add_argument("--version", required=True)
p.add_argument("--min-macos", required=True)
p.add_argument("--profile", type=Path, required=True)
p.add_argument("--identity", required=True)
p.add_argument("--qmake", required=True)
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()


def run(*command, capture=False):
    return subprocess.run(command, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def fail(message):
    sys.exit(f"macos-bundle: {message}")


def is_macho(path):
    with open(path, "rb") as f:
        return f.read(4) in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe")


def rpaths(path):
    lines = run("otool", "-l", str(path), capture=True).splitlines()
    return [lines[i + 2].split()[1] for i, line in enumerate(lines)
            if line.strip() == "cmd LC_RPATH"]


def rpath_libraries(path):
    lines = run("otool", "-L", str(path), capture=True).splitlines()[1:]
    return [line.split()[0] for line in lines if line.strip().startswith("@rpath/")]


profile = plistlib.loads(run("security", "cms", "-D", "-i", str(a.profile), capture=True).encode())
team = profile["TeamIdentifier"][0]
app_id = profile["Entitlements"]["com.apple.application-identifier"]
bundle_id = app_id.split(".", 1)[1]
if "*" in bundle_id:
    fail(f"{a.profile} is a wildcard profile; the bundle needs an explicit App ID")
if profile["ExpirationDate"].replace(tzinfo=timezone.utc) < datetime.now(timezone.utc):
    fail(f"{a.profile} expired on {profile['ExpirationDate']:%Y-%m-%d}")

# AMFI kills a binary whose signing certificate is not in its embedded profile.
identities = run("security", "find-identity", "-v", "-p", "codesigning", capture=True)
hashes = {line.split()[1] for line in identities.splitlines()
          if f'"{a.identity}' in line or line.split()[1:2] == [a.identity]}
allowed = {hashlib.sha1(cert).hexdigest().upper() for cert in profile["DeveloperCertificates"]}
if not hashes & allowed:
    fail(f'no "{a.identity}" identity in the keychain matches a certificate in {a.profile}')
identity = sorted(hashes & allowed)[0]

app = a.output
contents = app / "Contents"
macos = contents / "MacOS"
if app.exists():
    shutil.rmtree(app)
macos.mkdir(parents=True)
(contents / "Resources").mkdir()

info = a.info_plist.read_text()
for key, value in {"BUNDLE_ID": bundle_id, "SHORT_VERSION": a.version.split("-")[0],
                   "MIN_MACOS": a.min_macos}.items():
    info = info.replace(f"@{key}@", value)
plistlib.loads(info.encode())
(contents / "Info.plist").write_text(info)
(contents / "PkgInfo").write_text("APPL????")
shutil.copy2(a.profile, contents / "embedded.provisionprofile")

build_dir = a.executable.parent
origins = {}
for source in [a.executable, *a.helper]:
    if not source.exists():
        fail(f"missing {source}")
    shutil.copy2(source, macos / source.name)
    origins[macos / source.name] = source
# Staged runtime libraries load from beside the executables.
for library in build_dir.glob("*.dylib"):
    shutil.copy2(library, macos / library.name, follow_symlinks=False)
    origins.setdefault(macos / library.name, library)
# Contents/MacOS is sealed as code, so data lives in Resources.
if (build_dir / "models").is_dir():
    shutil.copytree(build_dir / "models", contents / "Resources/models", symlinks=True)

qt_libs = run(a.qmake, "-query", "QT_INSTALL_LIBS", capture=True).strip()
pending = [path for path in origins if not path.is_symlink() and is_macho(path)]
while pending:
    path = pending.pop()
    origin = origins[path]
    references = rpath_libraries(path)
    for reference in references:
        name = reference.removeprefix("@rpath/")
        if "/" in name or (macos / name).exists():
            continue
        for directory in rpaths(origin):
            candidate = Path(directory.replace("@loader_path", str(origin.parent))
                             .replace("@executable_path", str(a.executable.parent))) / name
            if candidate.exists():
                shutil.copy2(candidate, macos / name)
                origins[macos / name] = candidate
                pending.append(macos / name)
                break
    if any(r.startswith("@rpath/Qt") for r in references) and qt_libs not in rpaths(path):
        run("install_name_tool", "-add_rpath", qt_libs, str(path))

signed = [path for path in sorted(macos.iterdir())
          if path.name != a.executable.name and not path.is_symlink() and is_macho(path)]
for path in signed:
    run("codesign", "--force", "--timestamp=none", "--sign", identity, str(path))

entitlements = {"com.apple.application-identifier": app_id,
                "com.apple.developer.team-identifier": team}
with tempfile.NamedTemporaryFile("wb", suffix=".entitlements", delete=False) as f:
    plistlib.dump(entitlements, f)
try:
    run("codesign", "--force", "--timestamp=none", "--sign", identity,
        "--entitlements", f.name, str(app))
finally:
    os.unlink(f.name)
run("codesign", "--verify", "--strict", "--deep", str(app))
print(f"macos-bundle: signed {app} as {bundle_id} (team {team})")
