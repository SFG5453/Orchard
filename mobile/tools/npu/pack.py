"""Packs an emitted Neuron graph (emit.py JSON + weights) into the app's .npug format, read by npu/neuron_graph.cpp."""
import struct

MAGIC = b"NPUG"
VERSION = 1


def _io(d):
    n = 1
    for x in d["dims"]:
        n *= x
    return struct.pack("<BfiI", d["type"] == "u8", d.get("scale", 1.0), d.get("zero", 0), n)


def pack(model, blob):
    out = bytearray(MAGIC + struct.pack("<I", VERSION))
    ops = model["operands"]
    out += struct.pack("<I", len(ops))
    for o in ops:
        if o.get("h"):
            raise ValueError("fp16 constants are not supported")
        dims = o["d"]
        out += struct.pack(f"<iI{len(dims)}Ifi", o["t"], len(dims), *dims, o["s"], o["z"])
        if "scalar" in o:
            out += struct.pack("<Bi", 2 if o["t"] == 6 else 1, int(o["scalar"]))
        elif "v" in o:
            out += struct.pack("<BQQ", 3, *o["v"])
        else:
            out += struct.pack("<B", 0)
        pc = o.get("pc")
        out += struct.pack("<BIIQ", 1, pc[0], pc[2], pc[1]) if pc else struct.pack("<B", 0)
    out += struct.pack("<I", len(model["ops"]))
    for code, ins, outs in model["ops"]:
        out += struct.pack(f"<iI{len(ins)}II{len(outs)}I", code, len(ins), *ins, len(outs), *outs)
    for ids in (model["inputs"], model["outputs"]):
        out += struct.pack(f"<I{len(ids)}I", len(ids), *ids)
    out += _io(model["io"]["input"]) + _io(model["io"]["output"])
    out += struct.pack("<Q", len(blob)) + bytes(blob)
    return bytes(out)
