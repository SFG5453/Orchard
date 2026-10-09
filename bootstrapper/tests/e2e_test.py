#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""End-to-end test: real HTTPS server, real signatures, real installs.

Usage: e2e_test.py <orchard-bootstrapper-test> <orchard_release.py>
"""

import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

BOOT = Path(sys.argv[1]).resolve()
RELEASE = Path(sys.argv[2]).resolve()
WORK = Path(tempfile.mkdtemp(prefix="orchard-e2e-"))
SITE = WORK / "site"
REQUESTS = []
OVERRIDES = {}

FAKE_APP = """#!/bin/sh
{ echo "args=$*"; env | grep -E '^(ORCHARD_|LD_LIBRARY_PATH=|TEST_)'; } > "$ORCHARD_TEST_OUT.tmp"
mv "$ORCHARD_TEST_OUT.tmp" "$ORCHARD_TEST_OUT"
exit %d
"""


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print(f"  ok: {message}")


# --- keys and certificates -------------------------------------------------

def make_keys():
    sign_key = ec.generate_private_key(ec.SECP256R1())
    (WORK / "sign.pem").write_bytes(sign_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    (WORK / "sign.pub").write_bytes(sign_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    rogue = ec.generate_private_key(ec.SECP256R1())
    (WORK / "rogue.pem").write_bytes(rogue.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))

    now = datetime.datetime.now(datetime.timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Orchard Test CA")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
          .public_key(ca_key.public_key()).serial_number(1)
          .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=2))
          .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
          .sign(ca_key, hashes.SHA256()))
    key = ec.generate_private_key(ec.SECP256R1())
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")]))
            .issuer_name(ca_name).public_key(key.public_key()).serial_number(2)
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=2))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    (WORK / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (WORK / "server.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM) + key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


# --- HTTPS server with Range support ----------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        path = self.path.split("?")[0]
        REQUESTS.append((path, self.headers.get("Range")))
        if path in OVERRIDES:
            data = OVERRIDES[path]
        else:
            file = SITE / path.lstrip("/")
            if not file.is_file():
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            data = file.read_bytes()
        start = 0
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            start = int(rng[6:].split("-")[0])
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{len(data) - 1}/{len(data)}")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(len(data) - start))
        self.end_headers()
        self.wfile.write(data[start:])

    def log_message(self, *args):
        pass


def start_server():
    server = ThreadingHTTPServer(("localhost", 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(WORK / "server.pem")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"https://localhost:{server.server_address[1]}/"


# --- release helpers ---------------------------------------------------------

def release(*args, key="sign.pem"):
    subprocess.run([sys.executable, str(RELEASE), *args, "--out", str(SITE), "--key", str(WORK / key),
                    "--key-id", "orchard-test"], check=True, stdout=subprocess.DEVNULL)


def build_tree(version, qt_version, qt_bytes, data, exit_code=0):
    tree = WORK / f"tree-{version}"
    shutil.rmtree(tree, ignore_errors=True)
    (tree / "app").mkdir(parents=True)
    (tree / "qt" / "lib").mkdir(parents=True)
    (tree / "models").mkdir(parents=True)
    script = tree / "app" / "orchard"
    script.write_text(FAKE_APP % exit_code)
    script.chmod(0o755)
    (tree / "app" / "data.bin").write_bytes(data)
    (tree / "app" / "icon.png").write_bytes(b"\x89PNG fake icon")
    (tree / "qt" / "lib" / "libQt6Core.so").write_bytes(qt_bytes)
    (tree / "models" / "model.onnx").write_bytes(MODEL)
    layout = {
        "components": {
            "qt": {"version": qt_version, "map": [{"from": "qt", "to": ""}]},
            "models": {"version": "m1", "map": [{"from": "models", "to": ""}]},
        },
        "app": {"map": [{"from": "app", "to": ""}]},
        "launch": {"executable": "orchard", "args": ["--from-manifest"],
                   "env": {"ORCHARD_MODELS_DIR": "{component:models}", "TEST_QT": "{component:qt}/lib"},
                   "prependPaths": {"LD_LIBRARY_PATH": ["{component:qt}/lib"]}},
        "icon": "icon.png",
    }
    (WORK / "layout.json").write_text(json.dumps(layout))
    release("manifest", "--version", version, "--platform", "linux-x86_64", "--tree", str(tree),
            "--layout", str(WORK / "layout.json"), "--chunk-size", "1", "--chunk-threshold", "2")


def publish(version, **extra):
    args = ["channel", "--channel", "stable", "--version", version, "--platform", "linux-x86_64"]
    for key, value in extra.items():
        args += [f"--{key.replace('_', '-')}", value]
    release(*args)


def boot(*args, expect=0, env=None):
    # No zenity windows popping up on the developer's desktop.
    full_env = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "WAYLAND_DISPLAY")}
    full_env.update(SSL_CERT_FILE=str(WORK / "ca.pem"), ORCHARD_BOOTSTRAP_TEST_KEY=str(WORK / "sign.pub"),
                    XDG_DATA_HOME=str(WORK / "xdg"), ORCHARD_TEST_OUT=str(WORK / "app-run.txt"), **(env or {}))
    result = subprocess.run([str(ROOT / "orchard") if (ROOT / "orchard").exists() and args[:1] != ("--install",)
                             else str(BOOT), "--root", str(ROOT), "--server", SERVER, *args],
                            env=full_env, capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=120)
    if result.returncode != expect:
        print(result.stdout, result.stderr)
        log = ROOT / "logs" / "bootstrapper.log"
        if log.exists():
            print(log.read_text()[-3000:])
        raise AssertionError(f"{args} exited {result.returncode}, expected {expect}")
    return result


def state():
    return json.loads((ROOT / "install.json").read_text())


def objects_fetched():
    return {p.rsplit("/", 1)[1] for p, _ in REQUESTS if p.startswith("/objects/")}


def wait_for_app_run():
    out = WORK / "app-run.txt"
    for _ in range(100):
        if out.exists():
            text = out.read_text()
            out.unlink()
            return text
        time.sleep(0.05)
    raise AssertionError("fake Orchard never ran")


# --- scenarios -----------------------------------------------------------------

def main():
    global SERVER, ROOT, MODEL
    make_keys()
    SERVER = start_server()
    ROOT = WORK / "install"
    MODEL = os.urandom(3 << 20)
    qt1 = bytearray(os.urandom(6 << 20))
    data1 = os.urandom(100_000)

    print("fresh install")
    build_tree("1.0.0", "q1", bytes(qt1), data1)
    publish("1.0.0")
    boot("--install", "--no-launch")
    s = state()
    check(s["current"] == "1.0.0" and s["components"] == {"qt": "q1", "models": "m1"}, "install.json records 1.0.0")
    check((ROOT / "versions/1.0.0/data.bin").read_bytes() == data1, "app files assembled")
    check((ROOT / "components/qt/q1/lib/libQt6Core.so").read_bytes() == bytes(qt1), "chunked file reassembled")
    check(os.access(ROOT / "versions/1.0.0/orchard", os.X_OK), "executable bit kept")
    check((ROOT / "orchard").exists(), "bootstrapper copied into the root")
    check((WORK / "xdg/applications/orchard.desktop").exists(), "desktop entry created")
    check(not any((ROOT / "cache/objects").rglob("*")) if (ROOT / "cache/objects").exists() else True,
          "object cache emptied after staging")

    print("launch")
    boot("--", "song.flac")
    run = wait_for_app_run()
    check("args=--from-manifest song.flac" in run, "manifest and user arguments passed")
    check(f"ORCHARD_MODELS_DIR={ROOT}/components/models/m1" in run, "component placeholder expanded")
    check(f"LD_LIBRARY_PATH={ROOT}/components/qt/q1/lib" in run, "library path prepended")
    check(f"ORCHARD_BOOTSTRAPPER={ROOT}/orchard" in run, "app can find the bootstrapper")
    time.sleep(0.3)
    check(state()["versions"]["1.0.0"]["healthy"], "clean exit marks the version healthy")

    print("incremental update")
    qt2 = bytearray(qt1)
    qt2[(3 << 20) + 10] ^= 0xFF  # touch one 1 MiB chunk
    data2 = data1[:-1] + b"!"
    build_tree("1.1.0", "q2", bytes(qt2), data2)
    publish("1.1.0")
    REQUESTS.clear()
    out = json.loads(boot("--check-update").stdout)
    check(out["available"] and out["latest"] == "1.1.0", "check-update sees 1.1.0")
    REQUESTS.clear()
    boot("--update")
    fetched = objects_fetched()
    changed_chunk = hashlib.sha256(bytes(qt2[3 << 20:4 << 20])).hexdigest()
    check(fetched == {hashlib.sha256(data2).hexdigest(), changed_chunk},
          f"only the 2 changed objects were downloaded (got {len(fetched)})")
    check(state()["pending"] == "1.1.0" and state()["current"] == "1.0.0", "update staged, not active")
    status = json.loads((ROOT / "update-state.json").read_text())
    check(status["state"] == "ready", "update-state.json says ready")
    boot()
    wait_for_app_run()
    s = state()
    check(s["current"] == "1.1.0" and s["previous"] == "1.0.0" and "pending" not in s, "launch activated 1.1.0")
    check((ROOT / "components/models/m1").is_dir() and not (ROOT / "components/models/m2").exists(),
          "unchanged component shared")

    print("repair")
    lib = ROOT / "components/qt/q2/lib/libQt6Core.so"
    damaged = bytearray(lib.read_bytes())
    damaged[(3 << 20) + 100] ^= 1  # the chunk only q2 has; shared chunks would come from q1
    lib.write_bytes(bytes(damaged))
    (ROOT / "versions/1.1.0/data.bin").unlink()
    REQUESTS.clear()
    out = json.loads(boot("--repair").stdout)
    check(out["repaired"] == 2, "repair found 2 damaged files")
    check(len(objects_fetched()) == 2, "repair downloaded only the bad chunk and the missing file")
    check(lib.read_bytes() == bytes(qt2), "damaged file restored")

    print("rollback")
    out = json.loads(boot("--rollback").stdout)
    check(out["current"] == "1.0.0" and "1.1.0" in state()["skippedVersions"], "rolled back and skipped 1.1.0")
    out = json.loads(boot("--update").stdout)
    check(out["staged"] == "", "skipped version not restaged")

    print("crash rollback")
    build_tree("1.2.0", "q2", bytes(qt2), data2 + b"crash", exit_code=7)
    publish("1.2.0")
    boot("--update")
    boot(expect=7)
    wait_for_app_run()
    boot()  # second failed start rolls back and starts 1.0.0 instead
    wait_for_app_run()
    s = state()
    check(s["current"] == "1.0.0" and "1.2.0" in s["skippedVersions"], "crashing version rolled back")

    print("resume and cleanup")
    data3 = os.urandom(300_000)
    build_tree("1.3.0", "q2", bytes(qt2), data3)
    publish("1.3.0")
    obj = hashlib.sha256(data3).hexdigest()
    part = ROOT / "cache/objects" / obj[:2] / (obj + ".part")
    part.parent.mkdir(parents=True, exist_ok=True)
    part.write_bytes(data3[:120_000])
    REQUESTS.clear()
    boot("--update")
    check((f"/objects/{obj}", "bytes=120000-") in REQUESTS, "partial object resumed with a Range request")
    boot()
    wait_for_app_run()
    time.sleep(0.3)
    boot("--background-update")
    left = sorted(p.name for p in (ROOT / "versions").iterdir())
    check(left == ["1.0.0", "1.3.0"], f"cleanup kept current and previous only ({left})")

    print("self-update")
    fake = WORK / "orchard-next"
    shutil.copy(BOOT, fake)
    with fake.open("ab") as f:
        f.write(b"next")  # different hash, still runs
    publish("1.3.0", bootstrapper=f"linux-x86_64={fake}", bootstrapper_version="9.9.9")
    boot("--update")
    check((ROOT / "orchard").read_bytes() == fake.read_bytes(), "bootstrapper replaced")
    check((ROOT / "orchard.old").exists() and state()["bootstrapperVersion"] == "9.9.9", "fallback kept, version recorded")

    print("security")
    good_channel = (SITE / "releases/stable.json").read_bytes()
    env = json.loads(good_channel)
    payload = json.loads(base64.b64decode(env["payload"]))
    payload["version"] = "6.6.6"
    env["payload"] = base64.b64encode(json.dumps(payload).encode()).decode()
    OVERRIDES["/releases/stable.json"] = json.dumps(env).encode()
    check("invalid signature" in boot("--check-update", expect=1).stderr, "tampered channel rejected")
    env["keyId"] = "someone-else"
    OVERRIDES["/releases/stable.json"] = json.dumps(env).encode()
    check("unknown key" in boot("--check-update", expect=1).stderr, "unknown signing key rejected")
    OVERRIDES.clear()

    release("channel", "--channel", "stable", "--version", "1.3.0", "--platform", "linux-x86_64", "--sequence", "1")
    check("older than one already seen" in boot("--check-update", expect=1).stderr, "replayed channel rejected")
    (SITE / "releases/stable.json").write_bytes(good_channel)

    tree = WORK / "tree-1.3.0"
    (WORK / "layout.json").write_text(json.dumps({
        "app": {"map": [{"from": "app", "to": "../escape"}]}, "exclude": ["qt/*", "models/*"],
        "launch": {"executable": "../escape/orchard"}}))
    release("manifest", "--version", "1.4.0", "--platform", "linux-x86_64", "--tree", str(tree),
            "--layout", str(WORK / "layout.json"))
    publish("1.4.0")
    check("unsafe manifest path" in boot("--update", expect=1).stderr, "path traversal rejected")

    build_tree("1.5.0", "q2", bytes(qt2), os.urandom(5000))
    publish("1.5.0")
    bad = next(p for p in SITE.glob("manifests/1.5.0-*.json"))
    obj = json.loads(base64.b64decode(json.loads(bad.read_text())["payload"]))["files"][0]["sha256"]
    OVERRIDES[f"/objects/{obj}"] = bytes(5000)
    check("hash verification" in boot("--update", expect=1).stderr, "corrupted object rejected after retries")
    check(not (ROOT / "cache/objects" / obj[:2] / obj).exists(), "corrupted object never committed")
    OVERRIDES.clear()

    release("manifest", "--version", "1.6.0", "--platform", "linux-x86_64", "--tree", str(WORK / "tree-1.5.0"),
            "--layout", str(WORK / "layout.json"), "--minimum-bootstrapper", "99.0.0")
    release("channel", "--channel", "stable", "--version", "1.6.0", "--platform", "linux-x86_64",
            "--minimum-bootstrapper", "99.0.0")
    shutil.copy(BOOT, ROOT / "orchard")  # back to the real version so the gate applies
    boot("--update", expect=4)
    check(True, "too-old bootstrapper refuses newer releases")

    print("uninstall")
    boot("--uninstall", "--yes")
    check(not ROOT.exists() and not (WORK / "xdg/applications/orchard.desktop").exists(), "install removed")
    shutil.rmtree(WORK)
    print("all end-to-end checks passed")


if __name__ == "__main__":
    main()
