# Copyright (C) 2026 SFG545
# 
# This file is part of Orchard.
# 
# Orchard is free software: you can redistribute it and/or modify it under the
# terms of the GNU Affero General Public License as published by the Free
# Software Foundation, either version 3 of the License, or (at your option) any
# later version.
# 
# Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
# 
# You should have received a copy of the GNU Affero General Public License
# along with Orchard. If not, see <https://www.gnu.org/licenses/>.

"""Bake shape arithmetic out of Beat This so ORT needs no CPU shape operators.

Build-time only: pip install onnx==1.23.0 onnxsim==0.7.3
This is build-time graph specialization; the desktop worker never enables the CPU EP.
"""
import hashlib
import urllib.request
from pathlib import Path
import onnx
import onnxsim

repo = Path(__file__).resolve().parents[1]
root = repo / "models" / "beat-this"
source = repo / ".cache" / "beat-this" / "beat_this.onnx"
url = ("https://github.com/mosynthkey/beat_this_cpp/raw/"
       "07ab790a9ec2eda8093d52d249e3ec4f0510ee72/onnx/beat_this.onnx")
expected = "c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd"
# The fp32 source is 83 MB and only feeds this script, so it lives in the ignored cache.
# Git remembers every byte forever; the cache forgets on request.
if not source.exists():
    source.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(url, source)
if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
    raise SystemExit("Unexpected Beat This source model checksum")
model, valid = onnxsim.simplify(
    onnx.load(source), overwrite_input_shapes={"input_spectrogram": [1, 1500, 128]}, check_n=0
)
if not valid:
    raise SystemExit("ONNX shape simplification failed")
onnx.checker.check_model(model)
onnx.save(model, root / "beat_this_webgpu.onnx")
