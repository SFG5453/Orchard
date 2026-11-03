"""Nirovi and Syvenu rebuilt as NHWC graphs from their fp32 ONNX exports."""
import numpy as np
import onnx
from onnx import numpy_helper as nh
from graph import Graph

NIROVI_ONNX = "/home/sfg/Projects/beat_detector/artifacts/models/scratch16_tcn384_gl7_qat8_win_fp32weights.onnx"
SYVENU_ONNX = "/home/sfg/Projects/vocal_detector/models/syvenu.onnx"
FRAMES = 1500


class Source:
    def __init__(self, path):
        self.model = onnx.load(path)
        self.init = {i.name: nh.to_array(i) for i in self.model.graph.initializer}
        for n in self.model.graph.node:
            if n.op_type == "Constant":
                self.init[n.output[0]] = nh.to_array(n.attribute[0].t)
        self.nodes = {n.name: n for n in self.model.graph.node}

    def attrs(self, name):
        return {a.name: onnx.helper.get_attribute_value(a) for a in self.nodes[name].attribute}

    def conv(self, name):
        n = self.nodes[name]
        a = self.attrs(name)
        return self.init[n.input[1]], self.init[n.input[2]], a


def conv1d(g, x, src, name, act, w_scale=1.0):
    """ONNX Conv1d over time; time is the NHWC W axis with H=1."""
    w, b, a = src.conv(name)
    w = w * w_scale
    p = a["pads"]
    d = a["dilations"][0]
    if a["group"] == 1:
        wn = w.transpose(0, 2, 1)[:, None]          # [Co,1,K,Ci]
        return g.conv(x, wn, b, (0, 0, p[0], p[1]), (1, d), act, False, name)
    assert a["group"] == w.shape[0] and w.shape[1] == 1
    wn = w[:, 0, :].T[None, None]                   # [1,1,K,C]
    return g.conv(x, wn, b, (0, 0, p[0], p[1]), (1, d), act, True, name)


def nirovi():
    src = Source(NIROVI_ONNX)
    assert float(src.init["/Constant_1_output_0"]) == 10.0
    g = Graph("nirovi", (1, 1, FRAMES, 128))
    # Input [1,1500,128] is NHWC [1,1,1500,128]; the ONNX transpose to NCL is a no-op here.
    x = conv1d(g, g.input, src, "/stem/stem.0/Conv", True, w_scale=0.1)
    stem = conv1d(g, x, src, "/stem/stem.2/Conv", True)
    g.stem = stem

    def block(x, prefix):
        y = conv1d(g, x, src, f"{prefix}/depthwise/Conv", True)
        y = conv1d(g, y, src, f"{prefix}/expand/Conv", True)
        y = conv1d(g, y, src, f"{prefix}/project/Conv", False)
        return g.add(x, y, act=True, label=f"{prefix}/Add")

    local = stem
    for i in range(3):
        local = block(local, f"/local/local.{i}")
    full = stem
    for i in range(9):
        full = block(full, f"/full_tcn/full_tcn.{i}")
    x = g.add(local, full, label="/Add")
    x = conv1d(g, x, src, "/fuse/fuse.0/Conv", True)
    # head channels are [h0, h1]; outputs are beat = h0 + h1, downbeat = h1.
    w, b, _ = src.conv("/head/Conv")
    w = np.stack([w[0] + w[1], w[1]])
    b = np.array([b[0] + b[1], b[1]], np.float32)
    g.output = g.conv(x, w.transpose(0, 2, 1)[:, None], b, label="/head/Conv")
    return g


def syvenu(gelu="op"):
    src = Source(SYVENU_ONNX)
    g = Graph("syvenu", (1, FRAMES, 128, 1))
    mean, std = src.init["model.mean"], src.init["model.std"]
    x = g.addc(g.input, (-mean).reshape(128, 1), label="norm_sub")
    x = g.mulc(x, (1 / std).reshape(128, 1), label="norm_div")
    for conv_i, pool, bn_i in [(0, 4, 2), (4, 4, 6), (8, 2, 10)]:
        w, b, a = src.conv(f"/model/stem/stem.{conv_i}/Conv")
        bn = f"/model/stem/stem.{bn_i}/BatchNormalization"
        eps = src.attrs(bn).get("epsilon", 1e-5)
        gamma, beta, rm, rv = (src.init[f"model.stem.{bn_i}.{k}"] for k in ("weight", "bias", "running_mean", "running_var"))
        scale = gamma / np.sqrt(rv + eps)
        shift = beta - rm * scale
        # max(a*x+b) == a*max(x)+b per channel only for a > 0.
        assert (scale > 0).all()
        wn = (w * scale[:, None, None, None]).transpose(0, 2, 3, 1)
        x = g.conv(x, wn, b * scale + shift, (1, 1, 1, 1), label=f"stem.{conv_i}+bn")
        x = g.maxpool(x, 1, pool, label=f"stem.{conv_i + 1}")
        x = activation(g, x, gelu, f"stem.{conv_i + 3}")
    _, t, f, c = g.shapes[x]
    x = g.reshape(x, (1, 1, t, f * c), label="flatten")
    # ONNX flattens channel-major (c*F+f); NHWC reshape yields f*C+c, so permute proj inputs.
    w, b, a = src.conv("/model/proj/Conv")
    perm = np.array([ci * f + fi for fi in range(f) for ci in range(c)])
    x = g.conv(x, w[:, perm].transpose(0, 2, 1)[:, None], b, label="proj")
    for i in range(14):
        y = conv1d(g, x, src, f"/model/blocks/blocks.{i}/conv/Conv", False)
        y = activation(g, y, gelu, f"blocks.{i}.gelu")
        y = conv1d(g, y, src, f"/model/blocks/blocks.{i}/mix/Conv", False)
        x = g.add(x, y, label=f"blocks.{i}.add")
    x = conv1d(g, x, src, "/model/head/Conv", False)
    g.output = g.sigmoid(x, label="presence")
    return g


def activation(g, x, gelu, label):
    # "op" emits NEURON_GELU; "tanh" is expanded into primitive ops by the emitter.
    return g.node("gelu", [x], g.shapes[x], label=label, form=gelu)
