#!/usr/bin/env python3
"""Prepare fixed 1500-frame Beat This QDQ and CPU quantization variants.

Inputs in --work-dir: {small0,final0}_source.onnx exported from official
checkpoints and music_mel.npy (>=6000 frames, upstream LogMelSpect frontend).
Calibration uses the first three 30-second chunks; verification uses synthetic
input only to check removal of empty rotary slices. This is a performance
experiment, not production accuracy certification.
Dependencies: onnx, onnxruntime, onnxsim, onnx-ir, numpy.
"""

import argparse
from pathlib import Path
import onnx, numpy as np, onnxruntime as ort
from onnxsim import simplify
from onnx import helper, numpy_helper
from onnxruntime.quantization import (
    CalibrationDataReader,
    QuantType,
    quantize,
    quantize_dynamic,
)
from onnxruntime.quantization.execution_providers.qnn import (
    get_qnn_qdq_config,
    qnn_preprocess_model,
)
from onnxruntime.quantization.matmul_nbits_quantizer import (
    MatMulNBitsQuantizer,
    DefaultWeightOnlyQuantConfig,
)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--work-dir", type=Path, required=True)
parser.add_argument("--models", nargs="+", default=["small0", "final0"])
parser.add_argument("--frames", type=int, default=1500)
args = parser.parse_args()
p = args.work_dir
chunk_frames = args.frames
music = np.load(p / "music_mel.npy")
assert music.ndim == 2 and music.shape[1] == 128
assert 0 < chunk_frames <= 1500 and len(music) >= 3000 + chunk_frames
frames = np.arange(chunk_frames)[:, None]
bands = np.arange(128)[None, :]


def sample(period=24):
    return (
        0.3
        + 6 * np.exp(-bands / 45)
        + ((frames % period) < 3) * 3 * np.exp(-bands / 70)
    ).astype(np.float32)[None, :, :]


class Reader(CalibrationDataReader):
    def __init__(self):
        self.i = 0

    def get_next(self):
        if self.i == 3:
            return None
        start = self.i * 1500
        x = music[start : start + chunk_frames][None, :, :]
        self.i += 1
        return {"input_spectrogram": x}


def expand_gemm(m):
    nodes = []
    for n in m.graph.node:
        if n.op_type != "Gemm":
            nodes.append(n)
            continue
        attr = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        assert attr.get("alpha", 1) == 1 and attr.get("beta", 1) == 1
        a, b = n.input[:2]
        if attr.get("transA", 0):
            nodes.append(
                helper.make_node("Transpose", [a], [n.name + "_aT"], perm=[1, 0])
            )
            a = n.name + "_aT"
        if attr.get("transB", 0):
            weight = next(i for i in m.graph.initializer if i.name == b)
            b = n.name + "_weight"
            m.graph.initializer.append(
                numpy_helper.from_array(numpy_helper.to_array(weight).T.copy(), b)
            )
        out = n.output[0] if len(n.input) == 2 else n.name + "_mm"
        nodes.append(helper.make_node("MatMul", [a, b], [out], name=n.name + "_MatMul"))
        if len(n.input) > 2:
            nodes.append(
                helper.make_node(
                    "Add", [out, n.input[2]], n.output, name=n.name + "_Bias"
                )
            )
    del m.graph.node[:]
    m.graph.node.extend(nodes)
    return m


for name in args.models:
    src = p / f"{name}_source.onnx"
    fixed = p / f"{name}_fixed.onnx"
    pre = p / f"{name}_pre.onnx"
    print("PREPARE", name, flush=True)
    m, ok = simplify(onnx.load(src), check_n=0)
    assert ok
    m = onnx.shape_inference.infer_shapes(m)
    empty = {
        v.name
        for v in m.graph.value_info
        if any(
            d.HasField("dim_value") and d.dim_value == 0
            for d in v.type.tensor_type.shape.dim
        )
    }
    for n in m.graph.node:
        if n.op_type == "Concat":
            keep = [i for i in n.input if i not in empty]
            del n.input[:]
            n.input.extend(keep)
    m, ok = simplify(m, check_n=0)
    assert ok
    onnx.save(m, fixed)
    onnx.checker.check_model(m)
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    x = {"input_spectrogram": sample()}
    a = ort.InferenceSession(str(src), opts).run(None, x)
    b = ort.InferenceSession(str(fixed), opts).run(None, x)
    print(
        "EQUIVALENCE",
        name,
        [float(np.max(np.abs(u - v))) for u, v in zip(a, b)],
        flush=True,
    )
    for u, v in zip(a, b):
        np.testing.assert_allclose(u, v, atol=0.002, rtol=0.002)
    changed = qnn_preprocess_model(fixed, pre)
    if not changed:
        onnx.save(m, pre)
    for variant, act, weight in [
        ("a8w8", QuantType.QUInt8, QuantType.QUInt8),
        ("a16w8", QuantType.QUInt16, QuantType.QUInt8),
        ("a16w16", QuantType.QUInt16, QuantType.QUInt16),
    ]:
        target = p / f"{name}_{variant}.onnx"
        config = get_qnn_qdq_config(
            pre, Reader(), activation_type=act, weight_type=weight
        )
        quantize(pre, target, config)
        onnx.checker.check_model(onnx.load(target))
        print("READY", target, flush=True)
    quantize_dynamic(fixed, p / f"{name}_int8.onnx", weight_type=QuantType.QInt8)
    q = MatMulNBitsQuantizer(
        expand_gemm(onnx.load(fixed)),
        algo_config=DefaultWeightOnlyQuantConfig(
            block_size=32, is_symmetric=True, bits=4
        ),
    )
    q.process()
    q.model.save_model_to_file(str(p / f"{name}_int4.onnx"), False)
    print("CPU READY", name, flush=True)
