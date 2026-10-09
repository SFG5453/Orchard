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

"""Compare playback's CPU ONNX output against the original PyTorch checkpoint.

Build the probe with: cargo build -p orchard-adaptive-mix --example model_probe
"""
import argparse
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import numpy as np
import torch

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--checkpoint", required=True, type=Path)
parser.add_argument("--model", required=True, type=Path)
parser.add_argument("--probe", required=True, type=Path)
parser.add_argument("--audio", type=Path)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location("exporter", Path(__file__).with_name("convert-umx-vocals-to-onnx.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)
torch.set_num_threads(2)
model = exporter.load_model(args.checkpoint)
shape = (1, 2, 2049, 960)
rng = np.random.default_rng(42)
fixtures = {
    "silence": np.zeros(shape, dtype=np.float32),
    "spectral": (rng.random(shape, dtype=np.float32) *
                 np.exp(-np.arange(2049, dtype=np.float32)[None, None, :, None] / 240) * 3).astype(np.float32),
}
if args.audio:
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-stream_loop", "-1", "-i", str(args.audio),
                                   "-t", "23", "-vn", "-ac", "2", "-ar", "44100", "-f", "f32le", "pipe:1"])
    audio = torch.from_numpy(np.frombuffer(raw, dtype="<f4").copy().reshape(-1, 2).T)
    fixtures["music"] = torch.stft(audio, 4096, hop_length=1024, window=torch.hann_window(4096),
                                   center=True, return_complex=True).abs()[:, :, :960].unsqueeze(0).numpy()

def curve(target, mixture):
    mix = mixture[:, :, 18:373, :]
    valid = mix > 1e-6
    counts = valid.sum(axis=(0, 1, 2))
    values = (np.clip(target[:, :, 18:373, :] / np.maximum(mix, 1e-6), 0, 1) * valid).sum(axis=(0, 1, 2))
    return np.divide(values, counts, out=np.zeros(960), where=counts > 0)

with tempfile.TemporaryDirectory(prefix="orchard-model-qa-") as directory:
    directory = Path(directory)
    for name, mixture in fixtures.items():
        with torch.no_grad():
            expected = model(torch.from_numpy(mixture)).numpy()
        source, output = directory / "input.f32", directory / "output.f32"
        mixture.astype("<f4").tofile(source)
        subprocess.run([str(args.probe.resolve()), str(args.model.resolve()), str(source), str(output)], check=True)
        actual = np.fromfile(output, dtype="<f4").reshape(shape)
        if not np.isfinite(actual).all():
            raise AssertionError(f"{name}: nonfinite ONNX output")
        error = np.abs(expected - actual)
        relative_rms = np.linalg.norm(error) / max(float(np.linalg.norm(expected)), 1e-10)
        mask_error = np.abs(curve(actual, mixture) - curve(expected, mixture))
        print(f"{name}: magnitude max={error.max():.8g}; relative RMS={relative_rms:.8g}; "
              f"vocal curve mean/max={mask_error.mean():.8g}/{mask_error.max():.8g}", flush=True)
        assert relative_rms < 1e-4 and mask_error.mean() < 1e-4 and mask_error.max() < 1e-3
