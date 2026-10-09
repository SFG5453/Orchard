/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

//! Beat This and UMX vocals. Desktop runs Beat This on ORT's WebGPU provider; Android runs
//! the INT8 exports on CPU. Pre- and post-processing are shared, so only the weights differ.
use crate::{
    beats::{self, Grid},
    downmix,
};
use earmark::{AudioBuffer, WholeTrackAnalyzer};
use ort::{session::{Session, builder::SessionBuilder}, value::Tensor};

pub struct Models {
    beat: Session,
    vocal: Option<Session>,
    vocal_error: Option<String>,
    frontend: WholeTrackAnalyzer,
}

#[cfg(feature = "desktop")]
fn session(path: &std::path::Path) -> Result<Session, String> {
    let provider = ort::ep::WebGPU::default().build().error_on_failure();
    Session::builder()
        .map_err(|e| e.to_string())?
        .with_execution_providers([provider])
        .map_err(|e| e.to_string())?
        // Otherwise ORT silently assigns unsupported operators to the CPU EP.
        .with_config_entry("session.disable_cpu_ep_fallback", "1")
        .map_err(|e| e.to_string())?
        .commit_from_file(path)
        .map_err(|e| format!("GPU model {}: {e}", path.display()))
}

// Idle pool threads sleep; spinning would steal cycles from playback.
fn cpu_builder(threads: usize) -> Result<SessionBuilder, String> {
    Session::builder()
        .map_err(|e| e.to_string())?
        .with_intra_threads(threads)
        .map_err(|e| e.to_string())?
        .with_inter_threads(1)
        .map_err(|e| e.to_string())?
        .with_config_entry("session.intra_op.allow_spinning", "0")
        .map_err(|e| e.to_string())
}

// The LSTM steps serially: on WebGPU each step is a tiny dispatch, so a slice
// pins the GPU for seconds and starves the compositor. Two CPU threads finish it
// in about 0.15 s.
#[cfg(feature = "desktop")]
fn cpu_session(path: &std::path::Path) -> Result<Session, String> {
    cpu_builder(2)?
        .commit_from_file(path)
        .map_err(|e| format!("CPU model {}: {e}", path.display()))
}

impl Models {
    #[cfg(feature = "desktop")]
    pub fn new(directory: &std::path::Path) -> Result<Self, String> {
        let beat = session(&directory.join("beat-this/beat_this_webgpu.onnx"))?;
        let vocal_result = cpu_session(&directory.join("vocal-separation/vocals_umxhq_fp32.onnx"));
        let (vocal, vocal_error) = match vocal_result {
            Ok(s) => (Some(s), None),
            Err(e) => (None, Some(e)),
        };
        Ok(Self {
            beat,
            vocal,
            vocal_error,
            frontend: WholeTrackAnalyzer::new().map_err(|e| e.to_string())?,
        })
    }

    /// CPU sessions from model bytes, for hosts that ship models inside a package.
    pub fn from_memory(beat: &[u8], vocal: &[u8], threads: usize) -> Result<Self, String> {
        let beat = cpu_builder(threads)?
            .commit_from_memory(beat)
            .map_err(|e| format!("Beat model: {e}"))?;
        let (vocal, vocal_error) = match cpu_builder(threads.min(2))
            .and_then(|mut b| b.commit_from_memory(vocal).map_err(|e| format!("Vocal model: {e}")))
        {
            Ok(s) => (Some(s), None),
            Err(e) => (None, Some(e)),
        };
        Ok(Self {
            beat,
            vocal,
            vocal_error,
            frontend: WholeTrackAnalyzer::new().map_err(|e| e.to_string())?,
        })
    }

    /// Beat grid for one 44.1 kHz mono window.
    pub fn beats(&mut self, window: &[f32]) -> Result<Grid, String> {
        let mono = downmix::beat_mono(window)?;
        let spec = self
            .frontend
            .beat_spectrogram(&mono, 22_050.0)
            .map_err(|e| e.to_string())?;
        let mut beat = vec![-1000.0; spec.frames];
        let mut downbeat = beat.clone();
        // Reverse stitching preserves the earliest prediction in overlapping windows.
        for start in beats::chunks(spec.frames).into_iter().rev() {
            let from = start.max(0) as usize;
            let to = ((start + beats::CHUNK as isize).max(0) as usize).min(spec.frames);
            let left = (-start).max(0) as usize;
            let right = ((start + beats::CHUNK as isize - spec.frames as isize).max(0) as usize)
                .min(beats::BORDER);
            let frames = to - from + left + right;
            let mut values = vec![0.0; beats::CHUNK * 128];
            values[left * 128..(left + to - from) * 128]
                .copy_from_slice(&spec.values[from * 128..to * 128]);
            let input =
                Tensor::from_array(([1, beats::CHUNK, 128], values)).map_err(|e| e.to_string())?;
            let output = self
                .beat
                .run(ort::inputs!["input_spectrogram"=>input])
                .map_err(|e| e.to_string())?;
            let (_, b) = output["beat"]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            let (_, d) = output["downbeat"]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            if b.len() != beats::CHUNK
                || d.len() != beats::CHUNK
                || b.iter().chain(d).any(|v| !v.is_finite())
            {
                return Err("Invalid Beat This output".into());
            }
            let border = if frames < 12 { 0 } else { beats::BORDER };
            for i in border..frames - border {
                let target = start + i as isize;
                if target >= 0 && (target as usize) < spec.frames {
                    beat[target as usize] = b[i];
                    downbeat[target as usize] = d[i];
                }
            }
        }
        beats::grid(&beat, &downbeat)
    }

    // V2 treats vocal masking as optional. A missing model leaves Earmark's
    // flat filter ride in place.
    pub fn vocals(&mut self, audio: &AudioBuffer) -> Result<Vec<f64>, String> {
        let session = self
            .vocal
            .as_mut()
            .ok_or_else(|| self.vocal_error.clone().unwrap_or_default())?;
        const WIDTH: usize = 960;
        let refs: Vec<_> = audio.planar().iter().map(Vec::as_slice).collect();
        let spec = self
            .frontend
            .vocal_spectrogram(&refs, 44_100.0)
            .map_err(|e| e.to_string())?;
        let mut curve = vec![0.0; spec.frames];
        // Cover the entire search slice, not just the first possible overlap.
        for start in (0..spec.frames).step_by(WIDTH) {
            let used = (spec.frames - start).min(WIDTH);
            let mut mix = vec![0.0; 2 * 2049 * WIDTH];
            for band in 0..2 * 2049 {
                mix[band * WIDTH..band * WIDTH + used].copy_from_slice(
                    &spec.values[band * spec.frames + start..band * spec.frames + start + used],
                );
            }
            let input = Tensor::from_array(([1, 2, 2049, WIDTH], mix.clone()))
                .map_err(|e| e.to_string())?;
            let outputs = session
                .run(ort::inputs!["mix_magnitude"=>input])
                .map_err(|e| e.to_string())?;
            let (_, target) = outputs["target_magnitude"]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            if target.len() != mix.len() || target.iter().any(|v| !v.is_finite()) {
                return Err("Invalid vocal model output".into());
            }
            for frame in 0..used {
                let mut sum = 0.0;
                let mut count = 0;
                for channel in 0..2 {
                    for bin in 18..=372 {
                        let i = (channel * 2049 + bin) * WIDTH + frame;
                        if mix[i] > 1e-6 {
                            sum += (target[i] / mix[i]).clamp(0.0, 1.0) as f64;
                            count += 1;
                        }
                    }
                }
                curve[start + frame] = if count > 0 { sum / count as f64 } else { 0.0 };
            }
        }
        Ok(curve)
    }
}
