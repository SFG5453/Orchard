#!/usr/bin/env python3
# Copyright (C) 2026 SFG545
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Windows build under Wine: WinHTTP, CreateProcess quoting, registry, shortcuts, exe swap.

Usage: wine_smoke.py <orchard-bootstrapper-test.exe> <orchard_release.py> <x86_64-w64-mingw32-clang>
Uses a throwaway WINEPREFIX; never touches the default one.
"""
import json, os, subprocess, sys, time, shutil
from pathlib import Path
CC = sys.argv[3]
sys.argv = sys.argv[:3]
sys.path.insert(0, str(Path(__file__).parent))
import e2e_test as e
from cryptography import x509
from cryptography.hazmat.primitives import serialization

W = e.WORK / "wine"
W.mkdir()
for name, extra in (("fakeapp", ["-municode"]), ("addca", ["-lcrypt32"])):
    subprocess.run([CC, "-O2", str(Path(__file__).parent / "wine" / f"{name}.c"), *extra, "-o", str(W / f"{name}.exe")], check=True)
def win(p): return "Z:" + str(p).replace("/", "\\")
env = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "WAYLAND_DISPLAY")}
env.update(WINEPREFIX=str(W / "prefix"), WINEDEBUG="-all")

def wine(*args, expect=0):
    r = subprocess.run(["wine", *args], env=env, capture_output=True, text=True, timeout=180)
    if r.returncode != expect:
        print(r.stdout, r.stderr); log = e.ROOT / "logs/bootstrapper.log"
        if log.exists(): print(log.read_text()[-2500:])
        raise AssertionError(f"{args[:3]} exited {r.returncode}")
    return r

e.make_keys()
e.SERVER = e.start_server()
e.ROOT = e.WORK / "install"
subprocess.run(["wineboot", "-i"], env=env, capture_output=True, timeout=300)
der = x509.load_pem_x509_certificate((e.WORK / "ca.pem").read_bytes()).public_bytes(serialization.Encoding.DER)
(e.WORK / "ca.der").write_bytes(der)
print(wine(str(W / "addca.exe"), win(e.WORK / "ca.der")).stdout.strip())

def tree(version, payload):
    t = e.WORK / f"tree-{version}"
    (t / "app").mkdir(parents=True); (t / "qt/lib").mkdir(parents=True); (t / "models").mkdir(parents=True)
    shutil.copy(W / "fakeapp.exe", t / "app/orchard-app.exe")
    (t / "app/data.bin").write_bytes(payload)
    (t / "qt/lib/Qt6Core.dll").write_bytes(QT)
    (t / "models/model.onnx").write_bytes(b"model")
    layout = {"components": {"qt": {"version": "q1", "map": [{"from": "qt", "to": ""}]},
                             "models": {"version": "m1", "map": [{"from": "models", "to": ""}]}},
              "app": {"map": [{"from": "app", "to": ""}]},
              "launch": {"executable": "orchard-app.exe", "env": {"ORCHARD_MODELS_DIR": "{component:models}"},
                         "prependPaths": {"PATH": ["{component:qt}/lib"]}}}
    (e.WORK / "layout.json").write_text(json.dumps(layout))
    e.release("manifest", "--version", version, "--platform", "win-x86_64", "--tree", str(t),
              "--layout", str(e.WORK / "layout.json"), "--chunk-size", "1", "--chunk-threshold", "2")
    e.release("channel", "--channel", "stable", "--version", version, "--platform", "win-x86_64")

QT = os.urandom(3 << 20)
BOOT = sys.argv[1]
common = ["--root", win(e.ROOT), "--server", e.SERVER]
env["ORCHARD_BOOTSTRAP_TEST_KEY"] = win(e.WORK / "sign.pub")
env["ORCHARD_TEST_OUT"] = win(e.WORK / "run.txt")

tree("1.0.0", b"one")
wine(BOOT, "--install", "--no-launch", *common)
s = json.loads((e.ROOT / "install.json").read_text())
e.check(s["current"] == "1.0.0", "install over WinHTTP")
e.check((e.ROOT / "Orchard.exe").exists(), "Orchard.exe in root")
e.check((e.ROOT / "components/qt/q1/lib/Qt6Core.dll").read_bytes() == QT, "chunked component assembled")
e.check("Uninstall" in s["created"][-1] and any(c.endswith("Orchard.lnk") for c in s["created"]), "shortcut and uninstall entry recorded")
r = wine("reg", "query", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard")
e.check("UninstallString" in r.stdout and "--uninstall" in r.stdout, "uninstall registry key written")

boot = str(e.ROOT / "Orchard.exe")
wine(boot, *common, "--", "song file.flac")
out = (e.WORK / "run.txt").read_text(encoding="utf-8", errors="replace")
e.check("arg=song file.flac" in out, "argument with a space survives quoting")
e.check("components\\models\\m1" in out and "ORCHARD_BOOTSTRAPPER=" in out, "launch env expanded with Windows paths")
e.check("components\\qt\\q1\\lib;" in out, "PATH prepended (case-insensitive key)")

tree("1.1.0", b"two")
e.REQUESTS.clear()
wine(boot, "--update", *common)
e.check(len(e.objects_fetched()) == 1, "update fetched only the changed object")
wine(boot, *common)
s = json.loads((e.ROOT / "install.json").read_text())
e.check(s["current"] == "1.1.0" and s["previous"] == "1.0.0", "activated on launch")

(e.ROOT / "versions/1.1.0/data.bin").write_bytes(b"bad")
out = json.loads(wine(boot, "--repair", *common).stdout)
e.check(out["repaired"] == 1, "repair under Windows file semantics")
out = json.loads(wine(boot, "--rollback", *common).stdout)
e.check(out["current"] == "1.0.0", "rollback")

fake = e.WORK / "next.exe"
shutil.copy(BOOT, fake)
with fake.open("ab") as f: f.write(b"next")
e.release("channel", "--channel", "stable", "--version", "1.1.0", "--platform", "win-x86_64",
          "--bootstrapper", f"win-x86_64={fake}", "--bootstrapper-version", "9.9.9")
wine(boot, "--update", *common)
e.check((e.ROOT / "Orchard.exe").read_bytes() == fake.read_bytes(), "running Orchard.exe replaced via rename")
e.check((e.ROOT / "Orchard.old.exe").exists(), "old bootstrapper kept as Orchard.old.exe")

wine(boot, "--uninstall", "--yes", *common)
time.sleep(5)
r = wine("reg", "query", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Orchard", expect=1)
e.check(True, "uninstall registry key removed")
e.check(not e.ROOT.exists(), "install root removed after exit")
shutil.rmtree(e.WORK, ignore_errors=True)
print("wine smoke test passed")
