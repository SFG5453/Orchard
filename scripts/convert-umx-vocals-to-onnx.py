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

# Converts the umxhq "vocals" checkpoint (MIT-licensed weights, Inria/SigSep,
# trained on MUSDB18-HQ) to ONNX at a fixed frame count. Not run automatically
# by any build step -- the derived int8 model is committed at
# models/vocal-separation/vocals_umxhq_int8.onnx, so ordinary builds and CI
# never need PyTorch. This script exists so the conversion is reproducible
# rather than a one-off artifact, and to document exactly how the shipped
# model was produced.
#
# Usage (from a venv with torch, torchaudio, onnx, onnxruntime installed, and
# PYTHONPATH pointed at a checkout of github.com/sigsep/open-unmix-pytorch):
#
#   curl -sL -o vocals-umxhq.pth \
#     https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth
#   # sha256: b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083
#   PYTHONPATH=./open-unmix-pytorch python convert-umx-vocals-to-onnx.py \
#     vocals-umxhq.pth vocals_umxhq.onnx
#   python -c "from onnxruntime.quantization import quantize_dynamic, QuantType; \
#     quantize_dynamic('vocals_umxhq.onnx', 'vocals_umxhq_int8.onnx', weight_type=QuantType.QInt8)"
#
# Dynamic frame axes do not survive TorchScript tracing here: OpenUnmix's
# forward() unpacks `nb_frames, nb_samples, ... = x.data.shape` as plain Python
# ints and reshapes against them directly, so tracing bakes in the dummy
# input's frame count as a graph constant regardless of what `dynamic_axes`
# claims -- attempting it produces a model that only runs at exactly the traced
# frame count anyway, just without that being explicit. A fixed shape sidesteps
# the bug outright, and Orchard's use is always a short, bounded overlap slice
# (<=16 beats plus padding), so a fixed budget -- like Beat This's fixed
# 1500-frame chunks -- is a natural fit rather than a workaround.
import sys
import torch
from openunmix.model import OpenUnmix

# Covers MAX_OVERLAP_SECONDS (16, in wsolaPlanner.js) plus slice padding and
# margin, at the model's native hop of 1024 samples / 44100 Hz (~43 fps).
FIXED_FRAMES = 960

ckpt_path, out_path = sys.argv[1], sys.argv[2]
state = torch.load(ckpt_path, map_location='cpu', weights_only=False)

# The checkpoint's own tensors carry the bandwidth restriction the model was
# trained with; reading it from the state dict rather than hard-coding it
# keeps this script correct if a different checkpoint is ever substituted.
max_bin = state['input_mean'].shape[0]
nb_output_bins = state['output_scale'].shape[0]
model = OpenUnmix(
    nb_bins=nb_output_bins,
    nb_channels=2,
    hidden_size=512,
    nb_layers=3,
    max_bin=max_bin,
)
# The checkpoint also carries a couple of buffers (stft/transform windows)
# that belong to the full Separator wrapper, not this module; strict=False
# skips them without missing anything OpenUnmix itself needs.
missing, unexpected = model.load_state_dict(state, strict=False)
if missing:
    raise RuntimeError(f'checkpoint is missing required parameters: {missing}')
print('unexpected (expected, belong to the Separator wrapper):', unexpected)
model.eval()

dummy = torch.randn(1, 2, nb_output_bins, FIXED_FRAMES).abs()
with torch.no_grad():
    out = model(dummy)
print('output shape:', tuple(out.shape))

torch.onnx.export(
    model, dummy, out_path,
    input_names=['mix_magnitude'],
    output_names=['target_magnitude'],
    opset_version=17,
    # The new torch.export-based exporter (torch.onnx.export's default as of
    # PyTorch 2.9) failed on this model's rearrange/reshape pattern the same
    # way it did on Beat This's small checkpoint; the legacy TorchScript
    # tracer handles it correctly and is what parity was measured against.
    dynamo=False,
)
print('exported', out_path, 'at fixed frames =', FIXED_FRAMES)
