#!/usr/bin/env python3
"""Compare fixed-window Beat This LiteRT exports on the same saved mel excerpts.

Both graphs use BeatTracker's six-frame borders and reverse stitching, so this
checks the context change from shorter model windows rather than export error.
Run with `uv run --no-project --with ai-edge-litert --with numpy`.
"""

import argparse
import json
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter


def peaks(logits: np.ndarray) -> np.ndarray:
    return np.array([
        i for i in range(len(logits))
        if logits[i] > 0 and all(
            j < 0 or j >= len(logits) or logits[j] <= logits[i]
            for j in range(i - 3, i + 4)
        )
    ], dtype=np.int32)


def stitched(model: Interpreter, mel: np.ndarray) -> dict[str, np.ndarray]:
    frames, bands = mel.shape
    assert bands == 128
    chunk_frames = int(model.get_input_details()[0]["shape"][1])
    border = 6
    stride = chunk_frames - 2 * border
    starts = []
    start = -border
    while start < frames - border:
        starts.append(start)
        start += stride
    if not starts:
        starts.append(-border)
    if frames > stride:
        starts[-1] = frames - (chunk_frames - border)
    while len(starts) > 1 and starts[-1] - starts[-2] < 2 * border:
        starts.pop()

    output = {name: np.full(frames, -1000.0, dtype=np.float32)
              for name in ("beat", "downbeat")}
    input_index = model.get_input_details()[0]["index"]
    for chunk_start in reversed(starts):
        source_start = max(0, chunk_start)
        source_end = min(frames, max(0, chunk_start + chunk_frames))
        left = max(0, -chunk_start)
        right = min(border, max(0, chunk_start + chunk_frames - frames))
        valid = source_end - source_start + left + right
        chunk = np.zeros((1, chunk_frames, bands), dtype=np.float32)
        chunk[0, left:left + source_end - source_start] = mel[source_start:source_end]
        model.set_tensor(input_index, chunk)
        model.invoke()
        for details in model.get_output_details():
            name = "downbeat" if "_downbeat_output" in details["name"] else "beat"
            values = model.get_tensor(details["index"]).ravel()
            assert len(values) == chunk_frames
            margin = 0 if valid < 2 * border else border
            for index in range(margin, valid - margin):
                target = chunk_start + index
                if 0 <= target < frames:
                    output[name][target] = values[index]
    return output


def compare(reference: np.ndarray, candidate: np.ndarray) -> dict:
    reference_peaks = peaks(reference)
    candidate_peaks = peaks(candidate)
    match = lambda first, second: sum(
        bool(np.any(np.abs(second - frame) <= 4)) for frame in first
    )
    return {
        "referencePeaks": len(reference_peaks),
        "candidatePeaks": len(candidate_peaks),
        "referenceMatched": match(reference_peaks, candidate_peaks),
        "candidateMatched": match(candidate_peaks, reference_peaks),
        "logitMae": float(np.abs(reference - candidate).mean()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("mels", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    models = [Interpreter(model_path=str(path), num_threads=4)
              for path in (args.reference, args.candidate)]
    for model in models:
        model.allocate_tensors()

    paths = sorted(args.mels.glob("gtzan_*.npy"))
    if args.limit is not None:
        paths = paths[:args.limit]
    rows = []
    for path in paths:
        mel = np.load(path).astype(np.float32)
        first, second = (stitched(model, mel) for model in models)
        rows.append({"track": path.stem, **{
            name: compare(first[name], second[name]) for name in ("beat", "downbeat")
        }})
        if len(rows) % 10 == 0:
            print(f"Compared {len(rows)}/{len(paths)} excerpts", flush=True)

    summary = {"clips": len(rows), "referenceFrames": int(models[0].get_input_details()[0]["shape"][1]),
               "candidateFrames": int(models[1].get_input_details()[0]["shape"][1])}
    for name in ("beat", "downbeat"):
        totals = {key: sum(row[name][key] for row in rows) for key in
                  ("referencePeaks", "candidatePeaks", "referenceMatched", "candidateMatched")}
        summary[name] = {
            **totals,
            "recallWithin4Frames": totals["referenceMatched"] / max(1, totals["referencePeaks"]),
            "precisionWithin4Frames": totals["candidateMatched"] / max(1, totals["candidatePeaks"]),
            "meanLogitMae": float(np.mean([row[name]["logitMae"] for row in rows])),
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"summary": summary, "perTrack": rows}, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
