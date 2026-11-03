"""Serialize a logical graph into Neuron operands/operations (JSON + weight blob)."""
import json
import numpy as np
from graph import quant_weight, const_qparams

OP = dict(ADD=0, CONV_2D=3, DEPTHWISE_CONV_2D=4, DEQUANTIZE=6, LOGISTIC=14, MAX_POOL_2D=17, MUL=18,
          RESHAPE=22, TANH=28, QUANTIZE=72, GELU=108)
OP_NAME = {v: k for k, v in OP.items()}
F32S, I32S, TF32, TI32, TQ8, BOOL, TQ8PC = 0, 1, 3, 4, 5, 6, 11


class Emitter:
    def __init__(self):
        self.operands = []
        self.ops = []
        self.blob = bytearray()

    def data(self, arr):
        while len(self.blob) % 16:
            self.blob.append(0)
        off = len(self.blob)
        self.blob += arr.tobytes()
        return [off, arr.nbytes]

    def operand(self, code, dims=(), scale=0.0, zero=0, value=None, half=False, channel=None, scalar=None):
        row = {"t": code, "d": [int(d) for d in dims], "s": float(scale), "z": int(zero)}
        if value is not None:
            if half:
                # fp32 constants ship as fp16 and are widened on device; compute is relaxed to fp16.
                row["v"] = self.data(value.astype("<f2"))
                row["h"] = 1
            else:
                row["v"] = self.data(np.ascontiguousarray(value))
        if channel is not None:
            row["pc"] = [channel[0], self.data(channel[1].astype("<f4"))[0], len(channel[1])]
        if scalar is not None:
            row["scalar"] = scalar
        self.operands.append(row)
        return len(self.operands) - 1

    def i32(self, v):
        return self.operand(I32S, scalar=int(v))

    def op(self, code, ins, outs, label):
        self.ops.append([code, ins, outs, label])


def emit(g, prec, qp, gelu_form="op"):
    e = Emitter()
    idx = {}

    def tensor(name, as_q=None):
        q = prec.get(name) == "q" if as_q is None else as_q
        if q:
            s, z = qp[name]
            return e.operand(TQ8, g.shapes[name], s, z)
        return e.operand(TF32, g.shapes[name])

    def fp_const(arr, dims=None):
        arr = np.asarray(arr, np.float32)
        return e.operand(TF32, dims or arr.shape, value=arr, half=True)

    def get(name, want_q):
        """Operand for `name` in the requested precision, inserting a conversion if needed."""
        have_q = prec.get(name) == "q"
        key = (name, want_q)
        if key in idx:
            return idx[key]
        src = idx[(name, have_q)]
        if want_q and not have_q:
            out = tensor(name, True)
            e.op(OP["QUANTIZE"], [src], [out], f"quantize {name}")
        else:
            out = tensor(name, False)
            e.op(OP["DEQUANTIZE"], [src], [out], f"dequantize {name}")
        idx[key] = out
        return out

    idx[(g.input, prec.get(g.input) == "q")] = tensor(g.input)
    for n in g.nodes:
        q = prec.get(n["out"]) == "q"
        ins = [get(i, q) for i in n["inputs"]]
        out = tensor(n["out"])
        idx[(n["out"], q)] = out
        label = n["label"]
        if n["op"] == "conv":
            w, b, dw = n["w"], n["b"], n["depthwise"]
            if q:
                wq, ws, _ = quant_weight(w, dw)
                wi = e.operand(TQ8PC, w.shape, value=wq, channel=(3 if dw else 0, ws))
                bias_scale = qp[n["inputs"][0]][0] * ws
                bi = e.operand(TI32, b.shape, value=np.round(b / bias_scale).astype("<i4"))
            else:
                wi, bi = fp_const(w), fp_const(b)
            p = n["pads"]
            pads = [e.i32(p[2]), e.i32(p[3]), e.i32(p[0]), e.i32(p[1]), e.i32(1), e.i32(1)]
            tail = [e.i32(1 if n["act"] else 0), e.operand(BOOL, scalar=0), e.i32(n["dil"][1]), e.i32(n["dil"][0])]
            if dw:
                e.op(OP["DEPTHWISE_CONV_2D"], ins + [wi, bi] + pads + [e.i32(1)] + tail, [out], label)
            else:
                e.op(OP["CONV_2D"], ins + [wi, bi] + pads + tail, [out], label)
        elif n["op"] == "maxpool":
            args = [e.i32(0)] * 4 + [e.i32(n["kw"]), e.i32(n["kh"]), e.i32(n["kw"]), e.i32(n["kh"]), e.i32(0), e.operand(BOOL, scalar=0)]
            e.op(OP["MAX_POOL_2D"], ins + args, [out], label)
        elif n["op"] == "add":
            e.op(OP["ADD"], ins + [e.i32(1 if n["act"] else 0)], [out], label)
        elif n["op"] in ("mulc", "addc"):
            c = n["c"]
            if q:
                s, z = const_qparams(c)
                ci = e.operand(TQ8, c.shape, s, z, value=np.clip(np.round(c / s) + z, 0, 255).astype(np.uint8))
            else:
                ci = fp_const(c)
            e.op(OP["MUL" if n["op"] == "mulc" else "ADD"], ins + [ci, e.i32(0)], [out], label)
        elif n["op"] == "gelu":
            if gelu_form == "op":
                e.op(OP["GELU"], ins, [out], label)
            else:
                assert not q, "tanh GELU expansion is float only"
                gelu_tanh(e, g.shapes[n["out"]], ins[0], out, fp_const, label)
        elif n["op"] == "sigmoid":
            e.op(OP["LOGISTIC"], ins, [out], label)
        elif n["op"] == "reshape":
            shape = e.operand(TI32, [4], value=np.array(g.shapes[n["out"]], "<i4"))
            e.op(OP["RESHAPE"], ins + [shape], [out], label)
        else:
            raise ValueError(n["op"])
    out_q = prec.get(g.output) == "q"
    io = {
        "input": io_desc(g, g.input, prec, qp),
        "output": io_desc(g, g.output, prec, qp),
    }
    model = {
        "operands": e.operands,
        "ops": [[c, i, o] for c, i, o, _ in e.ops],
        "labels": [l for *_, l in e.ops],
        "inputs": [idx[(g.input, prec.get(g.input) == "q")]],
        "outputs": [idx[(g.output, out_q)]],
        "relax_fp16": any(prec.get(t) != "q" for t in g.shapes),
        "io": io,
    }
    return model, bytes(e.blob)


def io_desc(g, name, prec, qp):
    d = {"dims": list(g.shapes[name])}
    if prec.get(name) == "q":
        d.update(type="u8", scale=qp[name][0], zero=qp[name][1])
    else:
        d.update(type="f32")
    return d


def gelu_tanh(e, shape, x, out, fp_const, label):
    """0.5*x*(1+tanh(0.79788456*(x+0.044715*x^3)))."""
    def t():
        return e.operand(TF32, shape)
    one = [1]
    x2, x3, a, s, u, th, v, h = (t() for _ in range(8))
    z = e.i32(0)
    e.op(OP["MUL"], [x, x, z], [x2], label)
    e.op(OP["MUL"], [x2, x, z], [x3], label)
    e.op(OP["MUL"], [x3, fp_const([0.044715], one), z], [a], label)
    e.op(OP["ADD"], [x, a, z], [s], label)
    e.op(OP["MUL"], [s, fp_const([0.7978845608], one), z], [u], label)
    e.op(OP["TANH"], [u], [th], label)
    e.op(OP["ADD"], [th, fp_const([1.0], one), z], [v], label)
    e.op(OP["MUL"], [x, fp_const([0.5], one), z], [h], label)
    e.op(OP["MUL"], [h, v, z], [out], label)


def write(model, blob, path_json, path_bin):
    with open(path_json, "w") as f:
        json.dump(model, f, separators=(",", ":"))
    with open(path_bin, "wb") as f:
        f.write(blob)
