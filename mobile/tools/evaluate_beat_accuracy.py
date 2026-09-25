#!/usr/bin/env python3
"""Reproduce the GTZAN-mini accuracy check for prepared beat_this models.

Run from the repository root. Requires artifacts/beat-quant/upstream and the
prepared ONNX files, GTZAN mini in artifacts/beat-accuracy/gtzan_mini-main,
and CPJKU/beat_this_annotations in artifacts/beat-accuracy/annotations.
Install numpy, torch, torchaudio, onnxruntime, mir_eval; ffmpeg and adb must
be on PATH. The explicitly selected Android benchmark APK must be installed.
Phone inference is CPU-only, sequential, and restricted to <= INT8.
Scoring can also read archived NPU output files from the historical experiment.
Use --stage prepare, host, phone, then score. Floating references run on host.
"""

import argparse


def prepare():
    from pathlib import Path
    import sys, json, subprocess, hashlib
    import numpy as np, torch

    sys.path.insert(0, str(Path("artifacts/beat-quant/upstream").resolve()))
    from beat_this.preprocessing import LogMelSpect

    p = Path("artifacts/beat-accuracy")
    torch.set_num_threads(4)
    transform = LogMelSpect()
    rows = []
    chunks = []
    for f in sorted((p / "gtzan_mini-main/genres").glob("*/*.wav")):
        genre, num, _ = f.name.split(".")
        name = f"gtzan_{genre}_{num}"
        ann = p / "annotations/gtzan/annotations/beats" / f"{name}.beats"
        if not ann.exists():
            raise RuntimeError(name)
        raw = subprocess.check_output(
            [
                "ffmpeg",
                "-v",
                "error",
                "-i",
                str(f),
                "-f",
                "f32le",
                "-ac",
                "1",
                "-ar",
                "22050",
                "-",
            ]
        )
        with torch.no_grad():
            mel = transform(
                torch.from_numpy(np.frombuffer(raw, dtype="<f4").copy())
            ).numpy()
        np.save(p / f"{name}.npy", mel)
        starts = list(range(-6, len(mel) - 6, 288))
        starts[-1] = len(mel) - 294
        row = dict(
            name=name,
            frames=len(mel),
            starts=starts,
            offset=len(chunks),
            audio_sha256=hashlib.sha256(f.read_bytes()).hexdigest(),
        )
        for s in starts:
            x = np.zeros((300, 128), np.float32)
            a = max(s, 0)
            b = min(s + 300, len(mel))
            x[a - s : b - s] = mel[a:b]
            chunks.append(x)
        rows.append(row)
    np.stack(chunks).astype("<f4").tofile(p / "input.bin")
    (p / "manifest.json").write_text(json.dumps(rows, indent=2))
    print(len(rows), "tracks", len(chunks), "chunks", flush=True)


def host():
    from pathlib import Path
    import numpy as np, onnxruntime as ort, json

    p = Path("artifacts/beat-accuracy")
    q = Path("artifacts/beat-quant")
    rows = json.loads((p / "manifest.json").read_text())
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    for model in ["small0", "final0"]:
        for frames in [300, 1500]:
            out = p / f"{model}-fp32-{frames}.npz"
            if out.exists():
                continue
            s = ort.InferenceSession(
                str(q / f'{model}{"f300" if frames==300 else ""}_source.onnx'),
                opts,
                providers=["CPUExecutionProvider"],
            )
            saved = {}
            for i, r in enumerate(rows):
                mel = np.load(p / f'{r["name"]}.npy')
                n = len(mel)
                starts = list(range(-6, n - 6, frames - 12))
                starts[-1] = n - (frames - 6)
                pred = np.full((2, n), -1000, dtype=np.float32)
                for start in reversed(starts):
                    a = max(start, 0)
                    b = min(start + frames, n)
                    chunk = np.zeros((1, frames, 128), np.float32)
                    chunk[0, a - start : b - start] = mel[a:b]
                    y = np.asarray(
                        s.run(["beat", "downbeat"], {s.get_inputs()[0].name: chunk})
                    )[:, 0, :]
                    pred[:, start + 6 : start + frames - 6] = y[:, 6:-6]
                saved[r["name"]] = pred
                if i % 20 == 0:
                    print(model, frames, i, flush=True)
            np.savez(out, **saved)


def phone():
    from pathlib import Path
    import subprocess, json

    p = Path("artifacts/beat-accuracy")
    subprocess.run(
        ["adb", "shell", "mkdir", "-p", "/data/local/tmp/beat-accuracy"], check=True
    )
    subprocess.run(
        [
            "adb",
            "push",
            str(p / "input.bin"),
            "/data/local/tmp/beat-accuracy/input.bin",
        ],
        check=True,
    )
    for model in ["small0", "final0"]:
        for backend, suffix in [
            ("cpu", "a8w8"),
            ("cpu", "int8"),
        ]:
            variant = f"{model}f300_{suffix}"
            name = f"{variant}-{backend}"
            dest = p / f"{name}.bin"
            if dest.exists():
                continue
            print("START", name, flush=True)
            with (p / f"{name}.log").open("w") as log:
                result = subprocess.run(
                    [
                        "adb",
                        "shell",
                        "am",
                        "instrument",
                        "-w",
                        "-r",
                        "-e",
                        "class",
                        "dev.sfg.orchard.mobile.playback.smart.BeatQuantBenchmark",
                        "-e",
                        "variant",
                        variant,
                        "-e",
                        "backend",
                        backend,
                        "-e",
                        "accuracy",
                        "true",
                        "dev.sfg.orchard.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner",
                    ],
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    timeout=600,
                )
            result.check_returncode()
            assert "OK (1 test)" in (p / f"{name}.log").read_text(), name
            raw = subprocess.check_output(
                [
                    "adb",
                    "exec-out",
                    "run-as",
                    "dev.sfg.orchard.mobile.debug",
                    "cat",
                    f"cache/accuracy-{name}.bin",
                ]
            )
            expected = (
                sum(
                    len(r["starts"])
                    for r in json.loads((p / "manifest.json").read_text())
                )
                * 2
                * 300
                * 4
            )
            assert len(raw) == expected, (name, len(raw), expected)
            dest.write_bytes(raw)
            print("DONE", name, len(raw), flush=True)


def score():
    from pathlib import Path
    import sys, json
    import numpy as np, torch, mir_eval

    sys.path.insert(0, str(Path("artifacts/beat-quant/upstream").resolve()))
    from beat_this.model.postprocessor import Postprocessor

    p = Path("artifacts/beat-accuracy")
    rows = json.loads((p / "manifest.json").read_text())
    post = Postprocessor()
    torch.set_num_threads(2)
    all_scores = []
    predictions = {}
    summary = []
    for model in ["small0", "final0"]:
        variants = [
            (f"{model}-fp32-{n}", p / f"{model}-fp32-{n}.npz") for n in [1500, 300]
        ] + [
            (f"{model}f300_{v}-{b}", p / f"{model}f300_{v}-{b}.bin")
            for v, b in [("int8", "cpu"), ("a8w8", "cpu"), ("a8w8_cached", "npu")]
        ]
        for name, file in variants:
            if not file.exists():
                continue
            if file.suffix == ".npz":
                values = dict(np.load(file))
            else:
                data = np.fromfile(file, dtype="<f4").reshape(-1, 2, 300)
                assert len(data) == sum(len(r["starts"]) for r in rows)
                values = {}
                for r in rows:
                    pred = np.full((2, r["frames"]), np.nan, np.float32)
                    for i, s in reversed(list(enumerate(r["starts"]))):
                        pred[:, s + 6 : s + 294] = data[r["offset"] + i, :, 6:-6]
                    assert np.isfinite(pred).all()
                    values[r["name"]] = pred
            predictions[name] = values
            for r in rows:
                truth = np.loadtxt(
                    p / "annotations/gtzan/annotations/beats" / f'{r["name"]}.beats',
                    ndmin=2,
                )
                detected = post(*[torch.from_numpy(a) for a in values[r["name"]]])
                for label, ref, est in zip(
                    ["beat", "downbeat"],
                    [truth[:, 0], truth[truth[:, 1] == 1, 0]],
                    detected,
                ):
                    ref = ref[(ref >= 5) & (ref < r["frames"] / 50)]
                    est = est[(est >= 5) & (est < r["frames"] / 50)]
                    hits = len(mir_eval.util.match_events(ref, est, 0.07))
                    precision = hits / len(est) if len(est) else 0
                    recall = hits / len(ref) if len(ref) else 0
                    f = mir_eval.beat.f_measure(ref, est)
                    all_scores.append(
                        dict(
                            model=model,
                            variant=name,
                            track=r["name"],
                            kind=label,
                            f1=f,
                            precision=precision,
                            recall=recall,
                            hits=hits,
                            reference=len(ref),
                            estimated=len(est),
                        )
                    )
            scores = [s for s in all_scores if s["variant"] == name]
            result = {"variant": name}
            for kind in ["beat", "downbeat"]:
                ss = [s for s in scores if s["kind"] == kind]
                result[kind] = {
                    k: float(np.mean([s[k] for s in ss]))
                    for k in ["f1", "precision", "recall"]
                }
                result[kind]["tracks"] = len(ss)
            summary.append(result)
    comparisons = []
    for model in ["small0", "final0"]:
        cpu = f"{model}f300_a8w8-cpu"
        npu = f"{model}f300_a8w8_cached-npu"
        if cpu in predictions and npu in predictions:
            d = np.concatenate(
                [
                    predictions[npu][r["name"]] - predictions[cpu][r["name"]]
                    for r in rows
                ],
                axis=1,
            )
            comparisons.append(
                dict(
                    model=model,
                    mae=np.mean(abs(d), axis=1).tolist(),
                    max_abs=np.max(abs(d), axis=1).tolist(),
                )
            )
    (p / "scores.json").write_text(json.dumps(all_scores, indent=2))
    (p / "summary.json").write_text(
        json.dumps(dict(scores=summary, cpu_npu_logit_difference=comparisons), indent=2)
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--stage", required=True, choices=("prepare", "host", "phone", "score")
    )
    globals()[parser.parse_args().stage]()
