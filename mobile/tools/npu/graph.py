"""Logical NHWC conv graph: numpy reference, calibration, fake-quant simulation."""
import numpy as np
from scipy.special import erf

class Graph:
    def __init__(self, name, input_shape):
        self.name = name
        self.nodes = []
        self.shapes = {}
        self.count = 0
        self.input = self.tensor(input_shape)
        self.output = None

    def tensor(self, shape):
        name = f"t{self.count}"
        self.count += 1
        self.shapes[name] = tuple(shape)
        return name

    def node(self, op, inputs, shape, **attrs):
        out = self.tensor(shape)
        self.nodes.append(dict(op=op, inputs=list(inputs), out=out, label=attrs.pop("label", op), **attrs))
        return out

    def conv(self, x, w, b, pads=(0, 0, 0, 0), dil=(1, 1), act=False, depthwise=False, label="conv"):
        n, h, wd, c = self.shapes[x]
        kh, kw = w.shape[1], w.shape[2]
        ho = h + pads[0] + pads[1] - dil[0] * (kh - 1)
        wo = wd + pads[2] + pads[3] - dil[1] * (kw - 1)
        co = w.shape[3] if depthwise else w.shape[0]
        assert depthwise or w.shape[3] == c, (label, w.shape, c)
        return self.node("conv", [x], (n, ho, wo, co), w=w.astype(np.float32), b=b.astype(np.float32),
                         pads=pads, dil=dil, act=act, depthwise=depthwise, label=label)

    def maxpool(self, x, kh, kw, label="maxpool"):
        n, h, w, c = self.shapes[x]
        return self.node("maxpool", [x], (n, h // kh, w // kw, c), kh=kh, kw=kw, label=label)

    def add(self, a, b, act=False, label="add"):
        return self.node("add", [a, b], self.shapes[a], act=act, label=label)

    def mulc(self, x, c, label="mulc"):
        return self.node("mulc", [x], self.shapes[x], c=np.asarray(c, np.float32), label=label)

    def addc(self, x, c, label="addc"):
        return self.node("addc", [x], self.shapes[x], c=np.asarray(c, np.float32), label=label)

    def gelu(self, x, label="gelu"):
        return self.node("gelu", [x], self.shapes[x], label=label)

    def sigmoid(self, x, label="sigmoid"):
        return self.node("sigmoid", [x], self.shapes[x], label=label)

    def reshape(self, x, shape, label="reshape"):
        assert np.prod(shape) == np.prod(self.shapes[x])
        return self.node("reshape", [x], shape, label=label)


def conv_nhwc(x, w, b, pads, dil, depthwise):
    xp = np.pad(x, ((0, 0), (pads[0], pads[1]), (pads[2], pads[3]), (0, 0)))
    kh, kw = w.shape[1], w.shape[2]
    ho = xp.shape[1] - dil[0] * (kh - 1)
    wo = xp.shape[2] - dil[1] * (kw - 1)
    out = np.zeros((x.shape[0], ho, wo, w.shape[3] if depthwise else w.shape[0]), np.float32)
    for i in range(kh):
        for j in range(kw):
            tap = xp[:, i * dil[0]:i * dil[0] + ho, j * dil[1]:j * dil[1] + wo, :]
            out += tap * w[0, i, j, :] if depthwise else tap @ w[:, i, j, :].T
    return out + b


def fake_quant(x, scale, zero, lo=0, hi=255):
    return ((np.clip(np.round(x / scale) + zero, lo, hi) - zero) * scale).astype(np.float32)


def quant_weight(w, depthwise):
    """Symmetric per-output-channel int8; returns (int8 data, scales, dequantized)."""
    axis = 3 if depthwise else 0
    other = tuple(i for i in range(4) if i != axis)
    scale = np.maximum(np.abs(w).max(axis=other), 1e-12) / 127
    shape = [1, 1, 1, 1]
    shape[axis] = -1
    q = np.clip(np.round(w / scale.reshape(shape)), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32), (q * scale.reshape(shape)).astype(np.float32)


def run(graph, x, prec=None, qparams=None, record=None):
    """Execute in fp32; 'q' tensors are fake-quantized with qparams."""
    prec = prec or {}
    env = {graph.input: x.astype(np.float32)}

    def q(name, value):
        if prec.get(name) == "q":
            s, z = qparams[name]
            value = fake_quant(value, s, z)
        if record is not None:
            lo, hi = record.get(name, (np.inf, -np.inf))
            record[name] = (min(lo, float(value.min())), max(hi, float(value.max())))
        return value

    env[graph.input] = q(graph.input, env[graph.input])
    for n in graph.nodes:
        a = [env[i] for i in n["inputs"]]
        quant = prec.get(n["out"]) == "q"
        if n["op"] == "conv":
            w = quant_weight(n["w"], n["depthwise"])[2] if quant else n["w"]
            y = conv_nhwc(a[0], w, n["b"], n["pads"], n["dil"], n["depthwise"])
            if n["act"]:
                y = np.maximum(y, 0)
        elif n["op"] == "maxpool":
            t = a[0]
            nb, h, wd, c = t.shape
            y = t[:, :h // n["kh"] * n["kh"], :wd // n["kw"] * n["kw"]].reshape(
                nb, h // n["kh"], n["kh"], wd // n["kw"], n["kw"], c).max(axis=(2, 4))
        elif n["op"] == "add":
            y = a[0] + a[1]
            if n["act"]:
                y = np.maximum(y, 0)
        elif n["op"] == "mulc":
            c = n["c"]
            if quant:
                c = fake_quant(c, *const_qparams(c))
            y = a[0] * c
        elif n["op"] == "addc":
            c = n["c"]
            if quant:
                c = fake_quant(c, *const_qparams(c))
            y = a[0] + c
        elif n["op"] == "gelu":
            y = 0.5 * a[0] * (1 + erf(a[0] / np.sqrt(2)))
        elif n["op"] == "sigmoid":
            y = 1 / (1 + np.exp(-a[0]))
        elif n["op"] == "reshape":
            y = a[0].reshape(graph.shapes[n["out"]])
        else:
            raise ValueError(n["op"])
        env[n["out"]] = q(n["out"], y.astype(np.float32))
    return env[graph.output]


def range_params(lo, hi):
    lo, hi = min(lo, 0.0), max(hi, 0.0)
    scale = max(hi - lo, 1e-8) / 255
    return float(scale), int(np.clip(round(-lo / scale), 0, 255))


def const_qparams(c):
    return range_params(float(c.min()), float(c.max()))


def assign_qparams(graph, ranges):
    """uint8 asymmetric params; ops whose output params Neuron fixes inherit or override them."""
    qp = {t: range_params(*ranges[t]) for t in ranges}
    for n in graph.nodes:
        if n["op"] in ("maxpool", "gelu", "reshape"):
            qp[n["out"]] = qp[n["inputs"][0]]
        elif n["op"] == "sigmoid":
            qp[n["out"]] = (1 / 256, 0)
    return qp
