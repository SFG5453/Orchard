/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Nirovi and UMX vocals through onnxruntime-web, for the `web` build. The host suspends
//! wasm (JSPI) while a session runs, so these calls stay synchronous like the native ones.
//! Pre- and post-processing match models.rs.
use crate::{
    beats::{self, Grid},
    downmix,
};
use earmark::{AudioBuffer, WholeTrackAnalyzer};

#[link(wasm_import_module = "orchard")]
unsafe extern "C" {
    // [1, CHUNK, 128] spectrogram in; CHUNK beat and downbeat logits out.
    fn beat_run(input: *const f32, len: usize, beat: *mut f32, downbeat: *mut f32, out_len: usize) -> i32;
    // [1, 2, 2049, WIDTH] magnitudes in; same-shaped vocal magnitudes out.
    fn vocal_run(input: *const f32, len: usize, out: *mut f32) -> i32;
}

pub struct Models {
    frontend: WholeTrackAnalyzer,
}

thread_local! {
    // Beat logits by input window. A scan plans twice over the same analysis audio
    // (splicing only touches the render windows), so the second plan skips the model.
    static LOGITS: std::cell::RefCell<Vec<(u64, Vec<f32>, Vec<f32>)>> = const { std::cell::RefCell::new(Vec::new()) };
}
const LOGIT_ENTRIES: usize = 4;

fn fingerprint(window: &[f32]) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    window.len().hash(&mut hasher);
    for sample in window { sample.to_bits().hash(&mut hasher); }
    hasher.finish()
}

impl Models {
    pub fn new() -> Result<Self, String> {
        Ok(Self { frontend: WholeTrackAnalyzer::new().map_err(|e| e.to_string())? })
    }

    /// Beat grid for one 44.1 kHz mono window.
    pub fn beats(&mut self, window: &[f32]) -> Result<Grid, String> {
        let key = fingerprint(window);
        let cached = LOGITS.with(|logits| {
            logits.borrow().iter().find(|(k, _, _)| *k == key).map(|(_, b, d)| (b.clone(), d.clone()))
        });
        if let Some((beat, downbeat)) = cached {
            return beats::grid(&beat, &downbeat);
        }
        let (beat, downbeat) = self.logits(window)?;
        let grid = beats::grid(&beat, &downbeat);
        LOGITS.with(|logits| {
            let mut logits = logits.borrow_mut();
            if logits.len() == LOGIT_ENTRIES { logits.remove(0); }
            logits.push((key, beat, downbeat));
        });
        grid
    }

    fn logits(&mut self, window: &[f32]) -> Result<(Vec<f32>, Vec<f32>), String> {
        let mono = downmix::beat_mono(window)?;
        let spec = self.frontend.beat_spectrogram(&mono, 22_050.0).map_err(|e| e.to_string())?;
        let mut beat = vec![-1000.0; spec.frames];
        let mut downbeat = beat.clone();
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
            let mut b = vec![0.0f32; beats::CHUNK];
            let mut d = vec![0.0f32; beats::CHUNK];
            if unsafe { beat_run(values.as_ptr(), values.len(), b.as_mut_ptr(), d.as_mut_ptr(), beats::CHUNK) } != 0 {
                return Err("The beat model failed in onnxruntime-web".into());
            }
            if b.iter().chain(&d).any(|v| !v.is_finite()) {
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
        Ok((beat, downbeat))
    }

    pub fn vocals(&mut self, audio: &AudioBuffer) -> Result<Vec<f64>, String> {
        const WIDTH: usize = 960;
        let refs: Vec<_> = audio.planar().iter().map(Vec::as_slice).collect();
        let spec = self.frontend.vocal_spectrogram(&refs, 44_100.0).map_err(|e| e.to_string())?;
        let mut curve = vec![0.0; spec.frames];
        for start in (0..spec.frames).step_by(WIDTH) {
            let used = (spec.frames - start).min(WIDTH);
            let mut mix = vec![0.0f32; 2 * 2049 * WIDTH];
            for band in 0..2 * 2049 {
                mix[band * WIDTH..band * WIDTH + used].copy_from_slice(
                    &spec.values[band * spec.frames + start..band * spec.frames + start + used],
                );
            }
            let mut target = vec![0.0f32; mix.len()];
            if unsafe { vocal_run(mix.as_ptr(), mix.len(), target.as_mut_ptr()) } != 0 {
                return Err("Vocal model failed in onnxruntime-web".into());
            }
            if target.iter().any(|v| !v.is_finite()) {
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
