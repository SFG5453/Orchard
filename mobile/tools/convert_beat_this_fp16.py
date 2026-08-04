#!/usr/bin/env -S uv run --script
# Copyright (C) 2026 SFG545
#
# This file is part of Orchard.
#
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
#
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
# A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy",
#     "onnx",
#     "onnxruntime",
#     "onnxconverter-common",
# ]
# ///
"""Converts the Beat This! beat/downbeat model from fp32 to fp16, and verifies it.

Why fp16 and not a heavier int8: the shipping desktop model is *dynamically*
quantized int8, which was measured at roughly half the fp32 inference time on
x86 -- where AVX512-VNNI does much of that work. On ARM the picture differs.
ARMv8.2 has native fp16 arithmetic, dynamic-quantization ops carry per-tensor
quantize/dequantize overhead that eats into the win on a transformer, and the
execution providers that accelerate quantized graphs (XNNPACK, QNN) want
*static* QDQ int8 rather than the dynamic form. fp16 is the cheap thing to try
first: half the size of fp32, no calibration set, and far less accuracy risk
than re-quantizing.

I/O is deliberately kept at fp32 (`keep_io_types`). The graph runs in fp16
internally but the caller still hands it float32 and reads float32 back, so
nothing downstream has to know. The cost is two cast nodes at the boundary.

Verification mirrors what models/beat-this/README.md did for int8: the fp16
graph is compared against the fp32 original on a synthetic spectrogram shaped
like real program material -- spectral tilt, a periodic beat grid, sustained
harmonics, a per-band noise floor -- rather than uniform noise, which is far
easier than real audio and would flatter the result.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnxconverter_common import float16

# The model contract, from models/beat-this/README.md.
MELS = 128
FRAME_RATE = 50.0
# The window the model was trained on, and what upstream inference feeds it.
CHUNK_FRAMES = 1500
# Peak picking, from electron/audio/beatThisTracker.js: a frame is a beat when
# it is the maximum of a seven-frame window and its logit is positive.
PEAK_WINDOW = 7

# Ops that must keep fp32 inputs on top of the converter's own defaults.
#
# `Range` is the one this model actually trips: the attention blocks build
# position indices with it, and the ONNX spec gives Range no float16 overload at
# all, so converting its inputs produces a graph that will not load. These are
# index and shape arithmetic rather than activations -- there is no precision or
# speed argument for having them in fp16 in the first place.
BLOCKED_OPS = list(float16.DEFAULT_OP_BLOCK_LIST) + ["Range"]

# A second constraint lives at the *session* rather than in this file, and is
# recorded here because it is invisible from the graph: the model has no Gelu
# node -- it has 16 `Erf`s -- but ORT's extended optimizations fuse those into
# `com.microsoft.Gelu` when the session is created. Desktop ORT ships an fp16
# kernel for the fused op; onnxruntime-android does not, so an fp16 graph loaded
# at ORT_ENABLE_ALL fails with ORT_NOT_IMPLEMENTED. Nothing can be done about
# that from the converter; the fp16 session has to run below the level that
# performs the fusion. See BeatModelBenchmark for how that is handled.


def repair_blocked_ops(model: onnx.ModelProto, blocked: set[str]) -> int:
    """Restores fp32 around ops the converter refused to convert.

    `convert_float_to_float16` keeps blocked ops out of fp16 but does not fix
    what feeds them: the `Constant` nodes supplying this model's attention
    `Range` bounds get rewritten to fp16 anyway, leaving `Range` with fp16
    start/delta and an fp32 limit, which will not load.

    Two repairs, per blocked node: any fp16 constant input is replaced with an
    fp32 copy, and the node's output is renamed and re-exposed through a Cast
    back to fp16, so every downstream consumer keeps the dtype it was converted
    to expect without having to be rewired individually.
    """
    graph = model.graph
    initializers = {tensor.name: tensor for tensor in graph.initializer}
    constants = {
        node.output[0]: node
        for node in graph.node
        if node.op_type == "Constant" and node.output
    }

    repaired = 0
    added: list[onnx.NodeProto] = []
    for node in graph.node:
        if node.op_type not in blocked:
            continue

        for index, name in enumerate(node.input):
            if not name:
                continue
            source = constants.get(name)
            if source is not None:
                tensor = next(
                    (a.t for a in source.attribute if a.name == "value"), None
                )
                if tensor is None or tensor.data_type != onnx.TensorProto.FLOAT16:
                    continue
                values = onnx.numpy_helper.to_array(tensor).astype(np.float32)
                replacement = f"{name}_fp32_for_{node.name.rsplit('/', 1)[-1]}"
                added.append(
                    onnx.helper.make_node(
                        "Constant",
                        inputs=[],
                        outputs=[replacement],
                        name=f"{replacement}_node",
                        value=onnx.numpy_helper.from_array(values, replacement),
                    )
                )
                node.input[index] = replacement
                repaired += 1
                continue

            tensor = initializers.get(name)
            if tensor is not None and tensor.data_type == onnx.TensorProto.FLOAT16:
                values = onnx.numpy_helper.to_array(tensor).astype(np.float32)
                replacement = f"{name}_fp32"
                if replacement not in initializers:
                    copy = onnx.numpy_helper.from_array(values, replacement)
                    graph.initializer.append(copy)
                    initializers[replacement] = copy
                node.input[index] = replacement
                repaired += 1

        # The node now emits fp32, but everything downstream was converted to
        # expect fp16. Re-expose it under its original name through a Cast.
        for index, name in enumerate(node.output):
            if not name:
                continue
            produced = f"{name}_fp32_src"
            node.output[index] = produced
            added.append(
                onnx.helper.make_node(
                    "Cast",
                    inputs=[produced],
                    outputs=[name],
                    name=f"{name}_to_fp16",
                    to=onnx.TensorProto.FLOAT16,
                )
            )
            repaired += 1

    if added:
        # Order matters: ONNX requires a node's producers to precede it, and the
        # graph is already topologically sorted, so re-sorting is the safe way to
        # place both the new constants and the new casts.
        graph.node.extend(added)
        model = onnx.helper.make_model(graph, opset_imports=model.opset_import)
    return repaired


FLOAT = onnx.TensorProto.FLOAT
FLOAT16 = onnx.TensorProto.FLOAT16


def _float_types(model: onnx.ModelProto) -> dict[str, int]:
    """Best-effort dtype for every tensor, from inference plus what we can trace.

    Inference runs against a value_info-free copy: `infer_shapes` never overwrites
    entries that already exist, and the converter leaves stale ones behind, so
    reusing them would just re-read the types we are trying to correct.
    """
    scratch = onnx.ModelProto()
    scratch.CopyFrom(model)
    del scratch.graph.value_info[:]
    inferred = onnx.shape_inference.infer_shapes(scratch, strict_mode=False)
    types: dict[str, int] = {}

    for group in (inferred.graph.value_info, inferred.graph.input, inferred.graph.output):
        for value in group:
            if value.type.HasField("tensor_type"):
                types[value.name] = value.type.tensor_type.elem_type
    for tensor in inferred.graph.initializer:
        types[tensor.name] = tensor.data_type

    # Inference gives up in places; a Cast states its own output dtype and a
    # Constant carries its tensor, so both are recoverable without it.
    for node in inferred.graph.node:
        if node.op_type == "Cast":
            target = next((a.i for a in node.attribute if a.name == "to"), None)
            if target is not None and node.output:
                types[node.output[0]] = target
        elif node.op_type == "Constant" and node.output:
            tensor = next((a.t for a in node.attribute if a.name == "value"), None)
            if tensor is not None:
                types[node.output[0]] = tensor.data_type
    return types


def harmonize_float_types(model: onnx.ModelProto, blocked: set[str]) -> int:
    """Casts fp32 stragglers up to fp16 wherever a node is fed both.

    The converter leaves genuine fp32 islands behind: this model's attention
    blocks build relative-position terms through a hardcoded `Cast` to float,
    which has to stay float for the `Range` it feeds but then flows on into an
    `Einsum` whose other operand is now fp16. ONNX requires both operands of a
    type-parameterised op to agree, so the graph will not load.

    Since the graph is fp16 everywhere else, the fp32 side is the straggler and
    gets cast up. Blocked ops are skipped -- they are fp32 on purpose.
    """
    graph = model.graph
    types = _float_types(model)
    casts: dict[str, str] = {}
    added: list[onnx.NodeProto] = []
    fixed = 0

    for node in graph.node:
        if node.op_type in blocked or node.op_type == "Cast":
            continue
        present = {types.get(name) for name in node.input if name}
        if FLOAT16 not in present or FLOAT not in present:
            continue
        for index, name in enumerate(node.input):
            if not name or types.get(name) != FLOAT:
                continue
            promoted = casts.get(name)
            if promoted is None:
                promoted = f"{name}_fp16"
                casts[name] = promoted
                added.append(
                    onnx.helper.make_node(
                        "Cast",
                        inputs=[name],
                        outputs=[promoted],
                        name=f"{promoted}_cast",
                        to=FLOAT16,
                    )
                )
                types[promoted] = FLOAT16
            node.input[index] = promoted
            fixed += 1

    graph.node.extend(added)
    return fixed


def topologically_sort(model: onnx.ModelProto) -> onnx.ModelProto:
    """Re-orders nodes so every producer precedes its consumers."""
    graph = model.graph
    ready = {tensor.name for tensor in graph.initializer}
    ready.update(value.name for value in graph.input)
    pending = list(graph.node)
    ordered: list[onnx.NodeProto] = []

    while pending:
        progressed = False
        remaining = []
        for node in pending:
            if all(not name or name in ready for name in node.input):
                ordered.append(node)
                ready.update(name for name in node.output if name)
                progressed = True
            else:
                remaining.append(node)
        if not progressed:
            # A genuine cycle, or an input nothing produces. Leave the rest as
            # they are and let the ONNX checker report it properly.
            ordered.extend(remaining)
            break
        pending = remaining

    del graph.node[:]
    graph.node.extend(ordered)
    return model


def synthetic_spectrogram(frames: int, bpm: float = 126.0, seed: int = 7) -> np.ndarray:
    """A log-mel spectrogram shaped like music rather than like noise.

    The absolute values do not need to be a real song -- they need to occupy the
    same range and have the same structure the network was trained on, so that
    fp16's reduced mantissa is exercised where it actually matters.
    """
    rng = np.random.default_rng(seed)
    bands = np.arange(MELS)

    # Real program material is loudest low and rolls off high.
    tilt = np.exp(-bands / 45.0) * 6.0
    # A per-band noise floor, not a flat one.
    floor = rng.uniform(0.15, 0.5, size=MELS)

    spectrogram = np.tile(tilt + floor, (frames, 1))
    spectrogram += rng.normal(0.0, 0.12, size=(frames, MELS))

    # Sustained harmonics: a few bands carrying steady content.
    for band in (12, 19, 26, 38, 51):
        spectrogram[:, band] += 1.8 + 0.4 * np.sin(np.arange(frames) / 37.0)

    # The beat grid: broadband transients on the beat, stronger on the downbeat.
    beat_frames = FRAME_RATE * 60.0 / bpm
    for index in range(int(frames / beat_frames) + 1):
        onset = int(round(index * beat_frames))
        if onset >= frames:
            break
        downbeat = index % 4 == 0
        strength = 4.5 if downbeat else 2.6
        # Transients decay over a few frames rather than living in one.
        for offset, decay in enumerate((1.0, 0.6, 0.35, 0.18)):
            frame = onset + offset
            if frame >= frames:
                break
            weight = np.exp(-bands / 70.0) if not downbeat else np.exp(-bands / 90.0)
            spectrogram[frame] += strength * decay * weight

    # log1p(1000 * magnitude) is non-negative by construction.
    return np.maximum(spectrogram, 0.0).astype(np.float32)[None, :, :]


def run(model: Path, spectrogram: np.ndarray) -> dict[str, np.ndarray]:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])
    name = session.get_inputs()[0].name
    outputs = session.run(None, {name: spectrogram})
    return {out.name: value for out, value in zip(session.get_outputs(), outputs)}


def peaks(logits: np.ndarray) -> set[int]:
    """Frames the tracker would call events, so the comparison is on decisions."""
    flat = logits.reshape(-1)
    half = PEAK_WINDOW // 2
    found = set()
    for index in range(len(flat)):
        if flat[index] <= 0:
            continue
        low = max(0, index - half)
        high = min(len(flat), index + half + 1)
        if flat[index] >= flat[low:high].max():
            found.add(index)
    return found


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="fp32 beat_this.onnx")
    parser.add_argument("target", type=Path, help="where to write the fp16 model")
    parser.add_argument("--frames", type=int, default=CHUNK_FRAMES)
    args = parser.parse_args()

    if not args.source.is_file():
        print(f"missing fp32 source: {args.source}", file=sys.stderr)
        return 1

    print(f"loading {args.source} ({args.source.stat().st_size / 1e6:.1f} MB)")
    model = onnx.load(str(args.source))

    print("converting to fp16 (io kept at fp32)")
    # The graph ships with inferred value_info asserting fp32 on every
    # intermediate. The converter rewrites the Cast nodes inside the attention
    # blocks but leaves those assertions behind, and the result fails to load
    # with a type conflict on the first attention Cast. Dropping the inferred
    # types before converting and re-inferring afterwards is the fix: they are
    # derivable, so nothing is lost by regenerating them against the new dtypes.
    del model.graph.value_info[:]
    converted = float16.convert_float_to_float16(
        model,
        keep_io_types=True,
        disable_shape_infer=True,
        op_block_list=BLOCKED_OPS,
    )
    blocked = set(BLOCKED_OPS)
    repaired = repair_blocked_ops(converted, blocked)
    print(f"repaired {repaired} edges around block-listed ops")
    converted = topologically_sort(converted)

    # Harmonizing changes dtypes, which can expose the next mismatch downstream,
    # so it runs to a fixed point rather than once.
    for _ in range(8):
        promoted = harmonize_float_types(converted, blocked)
        converted = topologically_sort(converted)
        if promoted == 0:
            break
        print(f"promoted {promoted} fp32 inputs to fp16")

    # Same staleness at the boundary: keep_io_types appends output casts after
    # the converter has already recorded fp16 for the tensors they consume, so
    # the recorded types contradict the nodes actually producing them. They are
    # all derivable, so the honest move is to drop them and infer once, cleanly.
    del converted.graph.value_info[:]
    onnx.checker.check_model(converted)
    converted = onnx.shape_inference.infer_shapes(converted)
    args.target.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(converted, str(args.target))
    size = args.target.stat().st_size
    print(f"wrote {args.target} ({size / 1e6:.1f} MB)  sha256 {sha256(args.target)}")

    print(f"verifying on a synthetic {args.frames}-frame spectrogram")
    spectrogram = synthetic_spectrogram(args.frames)
    reference = run(args.source, spectrogram)
    candidate = run(args.target, spectrogram)

    ok = True
    for name, expected in reference.items():
        actual = candidate[name]
        difference = np.abs(expected - actual)
        spread = float(expected.max() - expected.min())
        expected_peaks = peaks(expected)
        actual_peaks = peaks(actual)
        missed = expected_peaks - actual_peaks
        extra = actual_peaks - expected_peaks
        print(
            f"  {name}: max|diff| {difference.max():.5f}  mean {difference.mean():.6f}"
            f"  (logit range {spread:.2f})"
        )
        print(
            f"  {name}: peaks fp32 {len(expected_peaks)}  fp16 {len(actual_peaks)}"
            f"  missed {len(missed)}  spurious {len(extra)}"
        )
        # The decisions are what ship. A logit that moves without moving a peak
        # costs nothing; a peak that appears or vanishes moves the beat grid.
        if missed or extra:
            ok = False

    print("PASS: fp16 reproduces every fp32 peak" if ok else "FAIL: peak picking diverged")
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
