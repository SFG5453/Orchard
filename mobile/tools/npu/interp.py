"""Reference interpreter for emitted Neuron graphs (NNAPI semantics), used to check the emitter."""
import numpy as np
from scipy.special import erf
from emit import OP, F32S, I32S, TF32, TI32, TQ8, BOOL, TQ8PC
from graph import conv_nhwc


def load(model, blob):
    vals = {}
    for i, o in enumerate(model["operands"]):
        if "scalar" in o:
            vals[i] = o["scalar"]
        elif "v" in o:
            off, n = o["v"]
            raw = blob[off:off + n]
            dims = o["d"]
            if o.get("h"):
                vals[i] = np.frombuffer(raw, "<f2").astype(np.float32).reshape(dims)
            elif o["t"] == TF32:
                vals[i] = np.frombuffer(raw, "<f4").reshape(dims)
            elif o["t"] == TI32:
                vals[i] = np.frombuffer(raw, "<i4").reshape(dims)
            elif o["t"] == TQ8:
                vals[i] = np.frombuffer(raw, np.uint8).reshape(dims)
            elif o["t"] == TQ8PC:
                vals[i] = np.frombuffer(raw, np.int8).reshape(dims)
    return vals


def deq(model, blob, i, v):
    o = model["operands"][i]
    if o["t"] == TQ8:
        return (v.astype(np.float32) - o["z"]) * o["s"]
    if o["t"] == TQ8PC:
        ch, off, n = o["pc"]
        s = np.frombuffer(blob[off:off + 4 * n], "<f4")
        shape = [1] * v.ndim
        shape[ch] = -1
        return v.astype(np.float32) * s.reshape(shape)
    return np.asarray(v, np.float32)


def quant(o, x):
    if o["t"] == TQ8:
        return np.clip(np.round(x / o["s"]) + o["z"], 0, 255).astype(np.uint8)
    return x.astype(np.float32)


def run(model, blob, x):
    ops_ = model["operands"]
    env = load(model, blob)
    env[model["inputs"][0]] = x
    for code, ins, outs in model["ops"]:
        f = lambda k: deq(model, blob, ins[k], env[ins[k]])
        s = lambda k: env[ins[k]]
        if code in (OP["CONV_2D"], OP["DEPTHWISE_CONV_2D"]):
            dw = code == OP["DEPTHWISE_CONV_2D"]
            w = f(1)
            b = env[ins[2]]
            if ops_[ins[2]]["t"] == TI32:
                ch, off, n = ops_[ins[1]]["pc"]
                b = b * ops_[ins[0]]["s"] * np.frombuffer(blob[off:off + 4 * n], "<f4")
            pl, pr, pt, pb, sw, sh = (s(k) for k in range(3, 9))
            assert sw == sh == 1
            k = 10 if dw else 9
            if dw:
                assert s(9) == 1
            act, layout, dilw, dilh = s(k), s(k + 1), s(k + 2), s(k + 3)
            assert layout == 0 and len(ins) == k + 4
            y = conv_nhwc(f(0), w, b, (pt, pb, pl, pr), (dilh, dilw), dw)
            if act == 1:
                y = np.maximum(y, 0)
        elif code == OP["MAX_POOL_2D"]:
            pl, pr, pt, pb, sw, sh, fw, fh, act, layout = (s(k) for k in range(1, 11))
            assert (pl, pr, pt, pb, act, layout) == (0, 0, 0, 0, 0, 0) and (sw, sh) == (fw, fh)
            t = f(0)
            n, h, wd, c = t.shape
            y = t[:, :h // fh * fh, :wd // fw * fw].reshape(n, h // fh, fh, wd // fw, fw, c).max(axis=(2, 4))
        elif code in (OP["ADD"], OP["MUL"]):
            y = f(0) + f(1) if code == OP["ADD"] else f(0) * f(1)
            if s(2) == 1:
                y = np.maximum(y, 0)
        elif code == OP["GELU"]:
            a = f(0)
            y = 0.5 * a * (1 + erf(a / np.sqrt(2)))
        elif code == OP["TANH"]:
            y = np.tanh(f(0))
        elif code == OP["LOGISTIC"]:
            y = 1 / (1 + np.exp(-f(0)))
        elif code == OP["RESHAPE"]:
            y = f(0).reshape(env[ins[1]])
        elif code in (OP["QUANTIZE"], OP["DEQUANTIZE"]):
            y = f(0)
        else:
            raise ValueError(code)
        o = ops_[outs[0]]
        assert tuple(y.shape) == tuple(o["d"]), (code, y.shape, o["d"])
        env[outs[0]] = quant(o, y.astype(np.float32))
    out = model["outputs"][0]
    return deq(model, blob, out, env[out])
