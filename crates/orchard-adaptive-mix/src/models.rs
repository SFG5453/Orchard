/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Nirovi beat and Syvenu vocal models. Desktop runs Nirovi's fp16 graph on ORT's WebGPU provider, or
//! its int8 graph on CPU; Android passes its extracted int8 exports to `from_files`. Pre- and
//! post-processing are shared, so only the weights differ.
use crate::{
    beats::{self, Grid},
    downmix,
    npu::Npu,
};
use earmark::{AudioBuffer, WholeTrackAnalyzer};
use ort::{session::{Session, builder::SessionBuilder}, value::Tensor};

/// Nirovi exports; both take Beat This's `[1, 1500, 128]` spectrogram and return its logits.
pub const GPU_BEAT: &str = "nirovi/nirovi_fp16.onnx";
pub const CPU_BEAT: &str = "nirovi/nirovi_int8.ort";
/// Syvenu, vocal presence distilled from UMX-HQ; reads the beat spectrogram `[1, T, 128]`.
pub const VOCAL: &str = "syvenu/syvenu.ort";

pub struct Models {
    beat: Session,
    vocal: Option<Session>,
    vocal_error: Option<String>,
    frontend: WholeTrackAnalyzer,
    /// Runs both models when set; the CPU sessions take over if it ever fails.
    npu: Option<Npu>,
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
        .map_err(|e| e.to_string())?
        // The arena and memory patterns keep each run's largest buffers for the worker's lifetime.
        .with_execution_providers([ort::ep::CPU::default().with_arena_allocator(false).build()])
        .map_err(|e| e.to_string())?
        .with_memory_pattern(false)
        .map_err(|e| e.to_string())
}

// Two CPU threads cover a 60 s window in about 45 ms; the GPU stays free for the compositor.
#[cfg(feature = "desktop")]
fn cpu_session(path: &std::path::Path) -> Result<Session, String> {
    cpu_builder(2)?
        .commit_from_file(path)
        .map_err(|e| format!("CPU model {}: {e}", path.display()))
}

impl Models {
    #[cfg(feature = "desktop")]
    pub fn new(directory: &std::path::Path) -> Result<Self, String> {
        // int8 on two CPU threads costs about 0.17 s a window where WebGPU is unavailable.
        let beat = session(&directory.join(GPU_BEAT))
            .or_else(|gpu| cpu_session(&directory.join(CPU_BEAT)).map_err(|cpu| format!("{gpu}; {cpu}")))?;
        let vocal_result = cpu_session(&directory.join(VOCAL));
        let (vocal, vocal_error) = match vocal_result {
            Ok(s) => (Some(s), None),
            Err(e) => (None, Some(e)),
        };
        Ok(Self {
            beat,
            vocal,
            vocal_error,
            frontend: WholeTrackAnalyzer::new().map_err(|e| e.to_string())?,
            npu: None,
        })
    }

    /// CPU sessions from model files, including extracted package assets.
    pub fn from_files(beat: &std::path::Path, vocal: &std::path::Path, threads: usize) -> Result<Self, String> {
        let beat = cpu_builder(threads)?
            .commit_from_file(beat)
            .map_err(|e| format!("Beat model: {e}"))?;
        let (vocal, vocal_error) = match cpu_builder(threads.min(2))
            .and_then(|mut b| b.commit_from_file(vocal).map_err(|e| format!("Vocal model: {e}")))
        {
            Ok(s) => (Some(s), None),
            Err(e) => (None, Some(e)),
        };
        Ok(Self {
            beat,
            vocal,
            vocal_error,
            frontend: WholeTrackAnalyzer::new().map_err(|e| e.to_string())?,
            npu: None,
        })
    }

    pub fn set_npu(&mut self, npu: Option<Npu>) {
        self.npu = npu;
    }

    /// Beat and downbeat logits for one spectrogram chunk.
    fn beat_logits(&mut self, values: Vec<f32>) -> Result<(Vec<f32>, Vec<f32>), String> {
        if let Some(npu) = &self.npu {
            match npu.beat(&values) {
                Ok(logits) => return Ok(logits),
                Err(_) => self.npu = None,
            }
        }
        let input = Tensor::from_array(([1, beats::CHUNK, 128], values)).map_err(|e| e.to_string())?;
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
        Ok((b.to_vec(), d.to_vec()))
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
            let (b, d) = self.beat_logits(values)?;
            if b.len() != beats::CHUNK
                || d.len() != beats::CHUNK
                || b.iter().chain(&d).any(|v| !v.is_finite())
            {
                return Err("Invalid beat model output".into());
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
    /// Vocal share per beat spectrogram frame (`evidence::VOCAL_FRAME`) of a 44.1 kHz window.
    pub fn vocals(&mut self, audio: &AudioBuffer) -> Result<Vec<f64>, String> {
        let channels = audio.planar();
        // Trained on ffmpeg's `-ac 1` fold, (L + R) / sqrt(2).
        let gain = if channels.len() > 1 { std::f32::consts::FRAC_1_SQRT_2 } else { 1.0 };
        let mono: Vec<f32> = (0..audio.frames())
            .map(|i| channels.iter().map(|c| c[i]).sum::<f32>() * gain)
            .collect();
        let spec = self
            .frontend
            .beat_spectrogram(&downmix::beat_mono(&mono)?, 22_050.0)
            .map_err(|e| e.to_string())?;
        if spec.frames == 0 {
            return Err("Vocal window is too short".into());
        }
        if let Some(npu) = &self.npu {
            match npu.vocal(&spec.values, spec.frames) {
                Ok(presence) => return Ok(presence.iter().map(|v| f64::from(*v)).collect()),
                Err(_) => self.npu = None,
            }
        }
        let session = self
            .vocal
            .as_mut()
            .ok_or_else(|| self.vocal_error.clone().unwrap_or_default())?;
        let input = Tensor::from_array(([1, spec.frames, 128], spec.values)).map_err(|e| e.to_string())?;
        let outputs = session
            .run(ort::inputs!["mel"=>input])
            .map_err(|e| e.to_string())?;
        let (_, presence) = outputs["presence"]
            .try_extract_tensor::<f32>()
            .map_err(|e| e.to_string())?;
        if presence.len() != spec.frames || presence.iter().any(|v| !v.is_finite()) {
            return Err("Invalid vocal model output".into());
        }
        Ok(presence.iter().map(|v| f64::from(*v)).collect())
    }
}
