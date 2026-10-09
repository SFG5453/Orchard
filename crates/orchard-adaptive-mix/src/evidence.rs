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

//! Planner evidence measured on decoded audio: bass presence, 808 weight and vocals.
use crate::{models::Models, song};
use earmark::AudioBuffer;
use earmark::audio::resample;
use earmark::analysis::VOCAL_SPECTROGRAM_HOP;
use serde_json::{Value, json};

/// Curve resolution handed to the planner, in seconds.
pub const STEP: f64 = 0.25;

/// RBJ biquad in transposed direct form II.
struct Biquad { b: [f64; 3], a: [f64; 2], z: [f64; 2] }

impl Biquad {
    fn new(rate: f64, frequency: f64, high: bool) -> Self {
        let w = std::f64::consts::TAU * frequency / rate;
        let alpha = w.sin() / std::f64::consts::SQRT_2;
        let cos = w.cos();
        let a0 = 1.0 + alpha;
        let b = if high { [(1.0 + cos) / 2.0, -(1.0 + cos), (1.0 + cos) / 2.0] }
            else { [(1.0 - cos) / 2.0, 1.0 - cos, (1.0 - cos) / 2.0] };
        Self { b: b.map(|v| v / a0), a: [-2.0 * cos / a0, (1.0 - alpha) / a0], z: [0.0; 2] }
    }

    fn run(&mut self, x: f64) -> f64 {
        let y = self.b[0] * x + self.z[0];
        self.z[0] = self.b[1] * x - self.a[0] * y + self.z[1];
        self.z[1] = self.b[2] * x - self.a[1] * y;
        y
    }
}

/// Fourth-order band between `low` and `high` Hz.
pub(crate) fn band(samples: &[f32], rate: f64, low: f64, high: f64) -> Vec<f64> {
    let mut filters = [Biquad::new(rate, low, true), Biquad::new(rate, low, true),
        Biquad::new(rate, high, false), Biquad::new(rate, high, false)];
    samples.iter().map(|&x| filters.iter_mut().fold(x as f64, |v, f| f.run(v))).collect()
}

fn compact(value: f64) -> f64 { (value * 1000.0).round() / 1000.0 }

fn percentile(values: &[f64], ratio: f64) -> f64 {
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    sorted.get(((sorted.len() as f64 - 1.0) * ratio).round() as usize).copied().unwrap_or(0.0)
}

/// Kick and 808 level (35-140 Hz) every `STEP`, where 1.0 is the song's usual level.
/// Beatless intros and outros read near zero, which no frame energy shows reliably.
pub fn bass_curve(mono: &[f32], rate: u32) -> Value {
    let filtered = band(mono, rate as f64, 35.0, 140.0);
    // Boundaries come from exact times so long songs never drift off the analysis timeline.
    let edge = |index: usize| (index as f64 * STEP * rate as f64).round() as usize;
    let levels: Vec<f64> = (0..).take_while(|index| edge(index + 1) <= filtered.len())
        .map(|index| {
            let chunk = &filtered[edge(index)..edge(index + 1)];
            (chunk.iter().map(|v| v * v).sum::<f64>() / chunk.len().max(1) as f64).sqrt()
        })
        .collect();
    // Per-second means keep one-beat gaps from setting the reference.
    let seconds: Vec<f64> = levels.chunks((1.0 / STEP) as usize)
        .map(|chunk| chunk.iter().sum::<f64>() / chunk.len() as f64).collect();
    let reference = percentile(&seconds, 0.75).max(1e-6);
    json!({"start": 0.0, "step": STEP,
        "values": levels.iter().map(|v| compact(v / reference)).collect::<Vec<_>>()})
}

/// log10 of sub (25-60 Hz) over low (60-250 Hz) energy across the song body. Modern 808
/// production reads well above zero; boom-bap and most R&B read below it.
pub fn sub_bass_ratio(mono: &[f32], rate: u32) -> Option<f64> {
    // The middle 60% skips intros and outros that rarely carry the production's low end.
    let body = &mono[mono.len() / 5..mono.len() * 4 / 5];
    let energy = |low, high| band(body, rate as f64, low, high).iter().map(|v| v * v).sum::<f64>();
    let (sub, low) = (energy(25.0, 60.0), energy(60.0, 250.0));
    (sub > 0.0 && low > 0.0).then(|| compact((sub / low).log10()))
}

/// Raw UMX vocal share per model frame for a render window, at 44.1 kHz frame times.
pub fn vocal_frames(models: &mut Models, window: &AudioBuffer) -> Result<Vec<f64>, String> {
    let audio = resample::resample(window, song::RATE).map_err(|e| e.to_string())?;
    models.vocals(&audio)
}

/// Seconds between vocal model frames; frame `i` is centred on `i * VOCAL_FRAME`.
pub const VOCAL_FRAME: f64 = VOCAL_SPECTROGRAM_HOP as f64 / song::RATE as f64;

/// Vocal share every `STEP` from model frames, starting at track time `start`.
pub fn vocal_curve(frames: &[f64], start: f64) -> Value {
    let per = STEP / VOCAL_FRAME;
    let count = (frames.len() as f64 / per).ceil() as usize;
    let values: Vec<f64> = (0..count).map(|index| {
        let from = (index as f64 * per).round() as usize;
        let to = (((index + 1) as f64 * per).round() as usize).min(frames.len()).max(from + 1);
        compact(frames[from..to.min(frames.len())].iter().sum::<f64>() / (to - from) as f64)
    }).collect();
    json!({"start": start, "step": STEP, "values": values})
}

/// Model frames covering `start..end` seconds of the window they were measured on.
pub fn crop(frames: &[f64], start: f64, end: f64) -> &[f64] {
    let from = ((start / VOCAL_FRAME).floor().max(0.0) as usize).min(frames.len());
    let to = ((end / VOCAL_FRAME).ceil().max(0.0) as usize).clamp(from, frames.len());
    &frames[from..to]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(frequency: f64, seconds: f64, rate: u32) -> Vec<f32> {
        (0..(seconds * rate as f64) as usize)
            .map(|i| (std::f64::consts::TAU * frequency * i as f64 / rate as f64).sin() as f32)
            .collect()
    }

    #[test]
    fn bass_curve_separates_a_beatless_intro_from_the_beat() {
        let rate = song::ANALYSIS_RATE;
        // Ten seconds of hi-hat range, then thirty of 808 range.
        let mut mono = tone(3000.0, 10.0, rate);
        mono.extend(tone(60.0, 30.0, rate));
        let curve = bass_curve(&mono, rate);
        let values: Vec<f64> = curve["values"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        assert!(values[4..36].iter().all(|v| *v < 0.05));
        assert!(values[44..].iter().all(|v| (*v - 1.0).abs() < 0.1));
    }

    #[test]
    fn sub_bass_ratio_reads_808_weight() {
        let rate = song::ANALYSIS_RATE;
        assert!(sub_bass_ratio(&tone(40.0, 20.0, rate), rate).unwrap() > 1.0);
        assert!(sub_bass_ratio(&tone(120.0, 20.0, rate), rate).unwrap() < -1.0);
    }

    #[test]
    fn vocal_curve_averages_frames_per_step() {
        let frames = vec![1.0; 100];
        let curve = vocal_curve(&frames, 30.0);
        assert_eq!(curve["start"], json!(30.0));
        assert!(curve["values"].as_array().unwrap().iter().all(|v| v.as_f64() == Some(1.0)));
        assert_eq!(crop(&frames, 0.0, 10.0 * VOCAL_FRAME).len(), 10);
    }
}
