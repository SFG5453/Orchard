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

"""Export the original UMX-HQ vocals checkpoint without INT8 quantization.

Build-time dependencies: torch==2.14.0, torchaudio==2.11.0, openunmix==1.3.0,
onnx==1.23.0, onnxsim==0.7.3. Desktop playback does not depend on Python.
The checkpoint is pinned below; --fp16 also emits an experimental half model
with float32 inputs/outputs, which must pass model_probe before being shipped.
"""
import argparse
import hashlib
from pathlib import Path
import onnx
import onnxsim
import torch
from openunmix.model import OpenUnmix

FRAMES = 960
CHECKPOINT_SHA256 = "b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083"

def load_model(checkpoint):
    if hashlib.sha256(checkpoint.read_bytes()).hexdigest() != CHECKPOINT_SHA256:
        raise ValueError("Unexpected UMX-HQ checkpoint checksum")
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    model = OpenUnmix(nb_bins=state["output_scale"].shape[0], nb_channels=2,
                      hidden_size=512, nb_layers=3, max_bin=state["input_mean"].shape[0])
    missing, unexpected = model.load_state_dict(state, strict=False)
    if missing:
        raise ValueError(f"Missing checkpoint weights: {missing}")
    # The full Separator also stores STFT windows; this core takes magnitudes.
    if any(key not in {"sample_rate", "stft.window", "transform.0.window"} for key in unexpected):
        raise ValueError(f"Unexpected checkpoint weights: {unexpected}")
    return model.eval()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--fp16", action="store_true")
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.manual_seed(0)
    model = load_model(args.checkpoint)
    # Keep the original 960-frame recurrent context. The singer deserves the
    # whole phrase, not a goldfish-sized attention span.
    dummy = torch.rand(1, 2, 2049, FRAMES)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(model, dummy, str(args.output), input_names=["mix_magnitude"],
                      output_names=["target_magnitude"], opset_version=17, dynamo=False)
    graph, valid = onnxsim.simplify(onnx.load(args.output), check_n=0)
    if not valid:
        raise RuntimeError("Vocal graph simplification failed")
    onnx.checker.check_model(graph)
    onnx.save(graph, args.output)
    if args.fp16:
        from onnxruntime.transformers.float16 import convert_float_to_float16
        half = convert_float_to_float16(graph, keep_io_types=True)
        from onnxruntime.transformers.onnx_model import OnnxModel
        OnnxModel(half).topological_sort()
        onnx.checker.check_model(half)
        onnx.save(half, args.output.with_name(args.output.stem + "_fp16.onnx"))
    print(f"Exported {args.output}: {len(graph.graph.node)} nodes, FP32")

if __name__ == "__main__":
    main()
