#!/usr/bin/env python3
"""Pack an FP32 LiteRT graph's constant weights as FP16, keeping FP32 math.

Run with `uv run --no-project --with ai-edge-litert --with flatbuffers --with numpy`.
The FP16 tensors feed DEQUANTIZE nodes, leaving the original operator graph and
its FP32 input/output interface intact. This is a size experiment, not an FP16
activation/compute conversion.
"""

import argparse
import re
from pathlib import Path

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--min-bytes", type=int, default=1024)
    parser.add_argument("--include", help="Only pack tensors whose names match this regular expression")
    parser.add_argument("--exclude", help="Keep matching tensor names in FP32")
    args = parser.parse_args()

    model = schema.ModelT.InitFromPackedBuf(bytearray(args.source.read_bytes()))
    assert len(model.subgraphs) == 1, "Expected one Beat This subgraph"
    graph = model.subgraphs[0]
    window_frames = int(graph.tensors[graph.inputs[0]].shape[1])
    produced = {int(index) for op in graph.operators for index in op.outputs}
    inputs = {int(index) for op in graph.operators for index in op.inputs if index >= 0}

    opcode = next(
        (i for i, code in enumerate(model.operatorCodes)
         if code.builtinCode == schema.BuiltinOperator.DEQUANTIZE),
        None,
    )
    if opcode is None:
        opcode = len(model.operatorCodes)
        model.operatorCodes.append(schema.OperatorCodeT(
            deprecatedBuiltinCode=schema.BuiltinOperator.DEQUANTIZE,
            builtinCode=schema.BuiltinOperator.DEQUANTIZE,
            version=2,
        ))

    conversions = []
    preserved_positions = []
    saved = 0
    for index, tensor in enumerate(list(graph.tensors)):
        if tensor.type != schema.TensorType.FLOAT32 or index in produced or index not in inputs:
            continue
        if tensor.buffer <= 0 or tensor.buffer >= len(model.buffers):
            continue
        buffer = model.buffers[tensor.buffer]
        if buffer.data is None or len(buffer.data) < args.min_bytes:
            continue
        name = tensor.name.decode() if isinstance(tensor.name, bytes) else tensor.name or ""
        if args.include and not re.search(args.include, name):
            continue
        if args.exclude and re.search(args.exclude, name):
            continue
        assert len(buffer.data) % 4 == 0
        original = np.frombuffer(buffer.data, dtype="<f4")
        # Keep the rotary position table in FP32 even for shorter exports.
        # FP16 rounds large phases before SIN/COS, shifting beat peaks.
        is_rotary_position_table = (
            tensor.shape is not None and list(tensor.shape) == [window_frames, 16, 1]
            and np.isclose(original.min(), 0.0)
            and np.isclose(original.max(), window_frames - 1.0)
        )
        if is_rotary_position_table:
            preserved_positions.append(name)
            continue
        packed = original.astype("<f2")
        if not np.isfinite(packed).all():
            raise ValueError(f"Weight {index} overflows FP16")
        saved += len(buffer.data) - packed.nbytes
        buffer.data = np.frombuffer(packed.tobytes(), dtype=np.uint8)

        half_index = len(graph.tensors)
        graph.tensors.append(schema.TensorT(
            shape=tensor.shape,
            type=schema.TensorType.FLOAT16,
            buffer=tensor.buffer,
            name=(tensor.name or b"weight") + b"_fp16",
        ))
        tensor.buffer = 0
        conversions.append(schema.OperatorT(
            opcodeIndex=opcode,
            inputs=[half_index],
            outputs=[index],
            builtinOptionsType=schema.BuiltinOptions.DequantizeOptions,
            builtinOptions=schema.DequantizeOptionsT(),
        ))

    if not conversions or len(preserved_positions) != 1:
        raise ValueError(
            "Expected packed weights and exactly one preserved rotary angle table; "
            f"got {len(conversions)} weights and {preserved_positions}"
        )
    graph.operators = conversions + graph.operators
    builder = flatbuffers.Builder(0)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(builder.Output())
    print(f"Packed {len(conversions)} weights, saved {saved:,} bytes; output {args.output.stat().st_size:,} bytes")
    print(f"Preserved FP32 position tensors: {preserved_positions}")


if __name__ == "__main__":
    main()
