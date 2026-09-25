#!/usr/bin/env python3
"""Make Beat This's reductions and rotary trigonometry safe for FP16 GPU.

The direct LiteRT export computes normalize(x) * sqrt(512) as
x / sqrt(sum(x*x)) * sqrt(512). The SUM exceeds FP16's finite range in later
transformer blocks on mobile GPU. Replace it with the algebraically equivalent
x / sqrt(mean(x*x)). The GPU also rounds large rotary phases before SIN/COS;
fold their fixed, checkpoint-derived tables into accurate constants.

Run with `uv run --no-project --with ai-edge-litert --with flatbuffers --with numpy`.
"""

import argparse
import math
from pathlib import Path

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    model = schema.ModelT.InitFromPackedBuf(bytearray(args.source.read_bytes()))
    if len(model.subgraphs) != 1:
        parser.error("Expected exactly one subgraph")
    graph = model.subgraphs[0]
    producers = {int(tensor): op for op in graph.operators for tensor in op.outputs}

    def kind(op) -> int:
        return model.operatorCodes[op.opcodeIndex].builtinCode

    scale_tensors = []
    for index, tensor in enumerate(graph.tensors):
        if tensor.type != schema.TensorType.FLOAT32 or tensor.shape is None or len(tensor.shape):
            continue
        if tensor.buffer <= 0 or tensor.buffer >= len(model.buffers):
            continue
        data = model.buffers[tensor.buffer].data
        if data is None or len(data) != 4:
            continue
        value = float(np.frombuffer(data, dtype="<f4")[0])
        if math.isclose(value, math.sqrt(512), rel_tol=1e-6):
            scale_tensors.append(index)
    if len(scale_tensors) != 1:
        raise ValueError(f"Expected one sqrt(512) scale tensor, found {scale_tensors}")
    scale_index = scale_tensors[0]

    def parent(tensor_index: int, expected_kind: int):
        op = producers[int(tensor_index)]
        if kind(op) != expected_kind:
            raise ValueError(f"Expected operator {expected_kind} for tensor {tensor_index}, got {kind(op)}")
        return op

    sums = []
    for op in graph.operators:
        if kind(op) != schema.BuiltinOperator.MUL or scale_index not in op.inputs:
            continue
        normalized = next(int(index) for index in op.inputs if index != scale_index)
        divide = parent(normalized, schema.BuiltinOperator.DIV)
        reshape = parent(divide.inputs[1], schema.BuiltinOperator.RESHAPE)
        maximum = parent(reshape.inputs[0], schema.BuiltinOperator.MAXIMUM)
        square_root = parent(maximum.inputs[0], schema.BuiltinOperator.SQRT)
        reduction = parent(square_root.inputs[0], schema.BuiltinOperator.SUM)
        squared = graph.tensors[reduction.inputs[0]]
        if squared.shape is None or squared.shape[-1] != 512:
            raise ValueError(f"Unexpected reduction input shape: {squared.shape}")
        sums.append(reduction)
    if len(sums) != 13 or len({id(op) for op in sums}) != 13:
        raise ValueError(f"Expected 13 distinct transformer RMS normalizations, found {len(sums)}")

    mean_opcode = len(model.operatorCodes)
    model.operatorCodes.append(schema.OperatorCodeT(
        deprecatedBuiltinCode=schema.BuiltinOperator.MEAN,
        builtinCode=schema.BuiltinOperator.MEAN,
        version=1,
    ))
    for op in sums:
        op.opcodeIndex = mean_opcode
    buffer = model.buffers[graph.tensors[scale_index].buffer]
    buffer.data = np.frombuffer(np.array([1], dtype="<f4").tobytes(), dtype=np.uint8)

    trig_ops = []
    for op in graph.operators:
        if kind(op) not in (schema.BuiltinOperator.SIN, schema.BuiltinOperator.COS):
            continue
        reshape = parent(op.inputs[0], schema.BuiltinOperator.RESHAPE)
        broadcast = parent(reshape.inputs[0], schema.BuiltinOperator.BROADCAST_TO)
        source_index = int(broadcast.inputs[0])
        source = graph.tensors[source_index]
        if source_index in producers:
            dequantize = parent(source_index, schema.BuiltinOperator.DEQUANTIZE)
            source = graph.tensors[int(dequantize.inputs[0])]
        dtype = {
            schema.TensorType.FLOAT32: "<f4",
            schema.TensorType.FLOAT16: "<f2",
        }.get(source.type)
        if dtype is None:
            raise ValueError(f"Unexpected rotary phase dtype: {source.type}")
        phase = np.frombuffer(model.buffers[source.buffer].data, dtype=dtype).reshape(source.shape)
        repeated = np.broadcast_to(phase, graph.tensors[reshape.inputs[0]].shape)
        angles = repeated.reshape(graph.tensors[op.inputs[0]].shape).astype(np.float32)
        result = np.cos(angles) if kind(op) == schema.BuiltinOperator.COS else np.sin(angles)
        output = graph.tensors[int(op.outputs[0])]
        if output.type != schema.TensorType.FLOAT32:
            raise ValueError(f"Unexpected rotary result dtype: {output.type}")
        model.buffers[output.buffer].data = np.frombuffer(result.astype("<f4").tobytes(), dtype=np.uint8)
        trig_ops.append(op)
    if len(trig_ops) != 8:
        raise ValueError(f"Expected four fixed SIN/COS pairs, found {len(trig_ops)} operations")
    graph.operators = [op for op in graph.operators if op not in trig_ops]

    builder = flatbuffers.Builder(0)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(builder.Output())
    print(f"Rewrote {len(sums)} RMS normalizations and folded {len(trig_ops)} rotary operations; "
          f"output {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
