"""Exports Nirovi and Syvenu as all-int8 Neuron graphs: models/npu/neuron/{nirovi,syvenu}.npug.

Usage: python export.py CALIBRATION.f32  (float32 [N, 1500, 128] beat spectrogram windows)
"""
import sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import graph
import models
from emit import emit
from pack import pack

OUT = Path(__file__).resolve().parents[3] / "models/npu/neuron"


def main(calibration):
    calib = np.fromfile(calibration, "<f4").reshape(-1, models.FRAMES, 128)
    OUT.mkdir(parents=True, exist_ok=True)
    for g in (models.nirovi(), models.syvenu()):
        ranges = {}
        for w in calib:
            graph.run(g, w.reshape(g.shapes[g.input]), record=ranges)
        model, blob = emit(g, {t: "q" for t in g.shapes}, graph.assign_qparams(g, ranges), "op")
        (OUT / f"{g.name}.npug").write_bytes(pack(model, blob))
        print(g.name, len(model["ops"]), "ops", len(blob), "weight bytes")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
