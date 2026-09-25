#!/usr/bin/env python3
"""Compare two 1500-frame Beat This LiteRT graphs on local GTZAN mel files.

Run with `uv run --no-project --with ai-edge-litert --with numpy`.
This measures agreement with the FP32 graph, not annotation-based accuracy.
"""

import argparse
import json
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter


def peaks(logits: np.ndarray) -> np.ndarray:
    return np.array([
        i for i in range(3, len(logits) - 3)
        if logits[i] > 0 and logits[i] >= max(logits[i - 3:i + 4])
    ], dtype=int)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("mels", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    models = [Interpreter(model_path=str(path), num_threads=4)
              for path in (args.reference, args.candidate)]
    for model in models:
        model.allocate_tensors()

    def run(model: Interpreter, sample: np.ndarray) -> list[np.ndarray]:
        model.set_tensor(model.get_input_details()[0]["index"], sample)
        model.invoke()
        return [model.get_tensor(item["index"]).ravel()
                for item in model.get_output_details()]

    rows = []
    for path in sorted(args.mels.glob("gtzan_*.npy")):
        sample = np.load(path)[:1500][None].astype(np.float32)
        if sample.shape != (1, 1500, 128):
            continue
        reference = run(models[0], sample)
        candidate = run(models[1], sample)
        row = {"track": path.stem}
        for label, first, second in zip(("beat", "downbeat"), reference, candidate):
            original_peaks, candidate_peaks = peaks(first), peaks(second)
            matched = sum(bool(np.any(np.abs(candidate_peaks - index) <= 4))
                          for index in original_peaks)
            row[label] = {
                "mae": float(np.abs(first - second).mean()),
                "max_error": float(np.abs(first - second).max()),
                "reference_peaks": len(original_peaks),
                "candidate_peaks": len(candidate_peaks),
                "matched_peaks": matched,
            }
        rows.append(row)
        if len(rows) % 20 == 0:
            print(f"Compared {len(rows)} clips", flush=True)

    summary = {"clips": len(rows), "candidate_bytes": args.candidate.stat().st_size}
    for label in ("beat", "downbeat"):
        summary[label] = {
            "mean_mae": float(np.mean([row[label]["mae"] for row in rows])),
            "worst_logit_error": max(row[label]["max_error"] for row in rows),
            "reference_peaks": sum(row[label]["reference_peaks"] for row in rows),
            "candidate_peaks": sum(row[label]["candidate_peaks"] for row in rows),
            "matched_peaks": sum(row[label]["matched_peaks"] for row in rows),
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"summary": summary, "per_track": rows}, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
