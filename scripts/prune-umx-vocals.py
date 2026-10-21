# Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD.

"""Cut the FP32 UMX-HQ vocals export down to what vocal presence reads.

Input is the output of convert-umx-vocals-to-onnx.py. The model only reads the first
1487 bins, and the presence curve only reads output bins 18..=372 (200 Hz to 4 kHz), so
both tensors shrink and the output layer keeps 710 of its 4098 rows. Values in the kept
bins are unchanged. --int8 also writes the shipped INT8 copy: per-channel dynamic INT8
LSTMs, and fully connected layers that store INT8 weights but multiply in FP32. Dynamic
quantization of fc1's input biased the presence curve high (+0.01, 3% of frames across the
0.6 activity threshold); weight-only layers keep it within 0.0044 mean, 0.9% of frames.
Ship it converted to ORT format; see models/vocal-separation/README.md.

Build-time dependencies: onnx==1.23.2, onnxruntime==1.31.0, numpy.
"""
import argparse
import hashlib
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper

SOURCE_SHA256 = "1ff89359f6d93507fd05d95159efd56d05b8e566bcef3c3ff11fb5893ff6503d"
FRAMES = 960
BINS = 2049
INPUT_BINS = 1487
FIRST_BIN = 18
KEPT_BINS = 355


def replace(graph, name, array):
    for index, tensor in enumerate(graph.initializer):
        if tensor.name == name:
            graph.initializer[index].CopyFrom(numpy_helper.from_array(array, name))
            return
    raise KeyError(name)


def prune(model):
    graph = model.graph
    init = {tensor.name: numpy_helper.to_array(tensor) for tensor in graph.initializer}
    nodes = {node.name: node for node in graph.node}
    kept = slice(FIRST_BIN, FIRST_BIN + KEPT_BINS)
    rows = np.concatenate([np.arange(BINS)[kept] + channel * BINS for channel in range(2)])

    fc3 = nodes["/fc3/MatMul"].input[1]
    replace(graph, fc3, init[fc3][:, rows])
    for name in nodes["/bn3/BatchNormalization"].input[1:]:
        replace(graph, name, init[name][rows])
    replace(graph, nodes["/Reshape_3"].input[1], np.array([FRAMES, 1, 2, KEPT_BINS], np.int64))
    for node in ("/Mul_1", "/Add_1"):
        name = nodes[node].input[1]
        replace(graph, name, init[name][kept])

    # The mask multiplies the mix, so the mix is cut to the same bins.
    multiply = nodes["/Mul_2"]
    mix = multiply.input[1]
    constants = {"/kept/starts": [FIRST_BIN], "/kept/ends": [FIRST_BIN + KEPT_BINS], "/kept/axes": [3]}
    for name, value in constants.items():
        graph.initializer.append(numpy_helper.from_array(np.array(value, np.int64), name))
    cut = helper.make_node("Slice", [mix, *constants], ["/kept/mix"], name="/kept/Slice")
    graph.node.insert(list(graph.node).index(multiply), cut)
    multiply.input[1] = "/kept/mix"

    graph.input[0].type.tensor_type.shape.dim[2].dim_value = INPUT_BINS
    graph.output[0].type.tensor_type.shape.dim[2].dim_value = KEPT_BINS
    # Intermediate shapes recorded at export still carry the full bin counts.
    del graph.value_info[:]
    model = onnx.shape_inference.infer_shapes(model, strict_mode=True)
    onnx.checker.check_model(model)
    return model


FULLY_CONNECTED = ["/fc1/MatMul", "/fc2/MatMul", "/fc3/MatMul"]


def weight_only(model, node_names):
    """INT8 weights per output column, dequantized to FP32 before the MatMul."""
    graph = model.graph
    init = {tensor.name: tensor for tensor in graph.initializer}
    for node in [node for node in graph.node if node.name in node_names]:
        name = node.input[1]
        weights = numpy_helper.to_array(init[name]).astype(np.float32)
        scale = (np.maximum(np.abs(weights).max(axis=0), 1e-12) / 127.0).astype(np.float32)
        quantized = np.clip(np.round(weights / scale), -127, 127).astype(np.int8)
        graph.initializer.remove(init[name])
        graph.initializer.extend([
            numpy_helper.from_array(quantized, f"{name}_quantized"),
            numpy_helper.from_array(scale, f"{name}_scale"),
            numpy_helper.from_array(np.zeros_like(quantized[0]), f"{name}_zero"),
        ])
        dequantize = helper.make_node(
            "DequantizeLinear", [f"{name}_quantized", f"{name}_scale", f"{name}_zero"], [name],
            name=f"{node.name}_dequantize", axis=1)
        graph.node.insert(list(graph.node).index(node), dequantize)
    onnx.checker.check_model(model)
    return model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="vocals_umxhq_fp32.onnx")
    parser.add_argument("output", type=Path)
    parser.add_argument("--int8", type=Path, help="also write a dynamic int8 copy here")
    args = parser.parse_args()
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Unexpected UMX-HQ export checksum")
    onnx.save(prune(onnx.load(args.source)), args.output)
    print(f"Pruned {args.output}: {args.output.stat().st_size / 1e6:.1f} MB")
    if args.int8:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        quantize_dynamic(args.output, args.int8, weight_type=QuantType.QInt8, per_channel=True,
                         nodes_to_exclude=FULLY_CONNECTED)
        onnx.save(weight_only(onnx.load(args.int8), FULLY_CONNECTED), args.int8)
        print(f"Quantized {args.int8}: {args.int8.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
