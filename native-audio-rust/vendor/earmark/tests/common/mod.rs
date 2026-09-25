//! Synthetic signal generators, so the test suite needs no audio files.

// Each integration test binary compiles this module separately and uses only part of it.
#![allow(dead_code)]

use earmark::{AudioBuffer, BeatAnalysis};

pub const SR: u32 = 44_100;

pub fn sine(freq: f32, seconds: f64, sample_rate: u32) -> Vec<f32> {
    let frames = (seconds * sample_rate as f64) as usize;
    (0..frames)
        .map(|i| (i as f32 / sample_rate as f32 * freq * std::f32::consts::TAU).sin())
        .collect()
}

/// Deterministic pseudo-noise. A fixed LCG keeps every run byte-identical, which matters for
/// the determinism tests.
pub fn noise(seed: u64, len: usize) -> Vec<f32> {
    let mut state = seed | 1;
    (0..len)
        .map(|_| {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            ((state >> 33) as f32 / (1u64 << 31) as f32) - 1.0
        })
        .collect()
}

pub fn sweep(from: f32, to: f32, seconds: f64, sample_rate: u32) -> Vec<f32> {
    let frames = (seconds * sample_rate as f64) as usize;
    let mut phase = 0.0f32;
    (0..frames)
        .map(|i| {
            let t = i as f32 / frames as f32;
            let freq = from * (to / from).powf(t);
            phase += std::f32::consts::TAU * freq / sample_rate as f32;
            phase.sin()
        })
        .collect()
}

/// How a synthetic track is voiced, which is what drives the planner's spectral decisions.
#[derive(Debug, Clone, Copy)]
pub struct Voice {
    /// Level of the 55 Hz kick body.
    pub bass: f32,
    /// Level of the sustained mid/high tone.
    pub tone_level: f32,
    pub tone_hz: f32,
    pub hats: f32,
}

impl Voice {
    /// Kick-driven club material: heavy low end, modest top.
    pub fn club() -> Self {
        Self {
            bass: 0.7,
            tone_level: 0.15,
            tone_hz: 440.0,
            hats: 0.05,
        }
    }

    /// Bright, bass-light material that clashes with [`Voice::club`].
    pub fn bright() -> Self {
        Self {
            bass: 0.02,
            tone_level: 0.5,
            tone_hz: 6_000.0,
            hats: 0.25,
        }
    }
}

/// Builds a track with an audible beat grid plus the metadata a caller would supply.
pub fn track(
    bpm: f32,
    seconds: f64,
    voice: Voice,
    channels: usize,
    sample_rate: u32,
) -> (AudioBuffer, BeatAnalysis) {
    let frames = (seconds * sample_rate as f64) as usize;
    let beat_frames = ((60.0 / bpm) * sample_rate as f32) as usize;
    let hiss = noise(0x5EED, frames);

    let mut channel = vec![0.0f32; frames];
    for (i, sample) in channel.iter_mut().enumerate() {
        let t = i as f32 / sample_rate as f32;
        let beat_phase = (i % beat_frames.max(1)) as f32 / sample_rate as f32;

        let kick = (-beat_phase * 14.0).exp() * (t * 55.0 * std::f32::consts::TAU).sin();
        let tone = (t * voice.tone_hz * std::f32::consts::TAU).sin();
        let hat = (-beat_phase * 90.0).exp() * hiss[i];

        *sample = voice.bass * kick + voice.tone_level * tone + voice.hats * hat;
    }

    let audio = AudioBuffer::new(vec![channel; channels], sample_rate).unwrap();
    (audio, beats(bpm, seconds))
}

/// A perfectly regular grid, as a beat tracker would report for the track above.
pub fn beats(bpm: f32, seconds: f64) -> BeatAnalysis {
    let interval = 60.0 / bpm as f64;
    let count = (seconds / interval) as usize;
    let times: Vec<f64> = (0..count).map(|i| i as f64 * interval).collect();
    let downbeats: Vec<f64> = times.iter().step_by(4).copied().collect();
    BeatAnalysis::new(bpm, times, downbeats).unwrap()
}

pub fn rms(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum: f64 = samples.iter().map(|s| (*s as f64) * (*s as f64)).sum();
    (sum / samples.len() as f64).sqrt() as f32
}

/// Energy below `cutoff`, via a one-pole low-pass. Enough to tell a bass swap from a bass sum.
pub fn low_band_energy(samples: &[f32], cutoff: f32, sample_rate: u32) -> f32 {
    let alpha = 1.0 - (-std::f32::consts::TAU * cutoff / sample_rate as f32).exp();
    let mut state = 0.0f32;
    let mut sum = 0.0f64;
    for sample in samples {
        state += alpha * (sample - state);
        sum += (state as f64) * (state as f64);
    }
    (sum / samples.len().max(1) as f64).sqrt() as f32
}

/// Energy above `cutoff`, as the complement of the same one-pole. Enough to tell how far a
/// low-pass ride has actually travelled.
pub fn high_band_energy(samples: &[f32], cutoff: f32, sample_rate: u32) -> f32 {
    let alpha = 1.0 - (-std::f32::consts::TAU * cutoff / sample_rate as f32).exp();
    let mut state = 0.0f32;
    let mut sum = 0.0f64;
    for sample in samples {
        state += alpha * (sample - state);
        let high = (sample - state) as f64;
        sum += high * high;
    }
    (sum / samples.len().max(1) as f64).sqrt() as f32
}
