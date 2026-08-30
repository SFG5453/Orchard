//! Whole-track tempo, phase-locked beat-grid, and downbeat analysis.

use super::fft::{FftWorkspace, symmetric_hann};
use crate::error::Result;

const MAX_ENVELOPE_SECONDS: f64 = 1200.0;
const MAX_TEMPO_SEARCH_SECONDS: f64 = 180.0;
const LOW_BAND_HZ: f64 = 150.0;

#[derive(Debug, Clone, PartialEq, Default)]
pub struct TempoResult {
    pub bpm: f64,
    pub beat_interval: f64,
    pub first_beat: f64,
    pub confidence: f64,
    pub beats: Vec<f64>,
    pub downbeats: Vec<f64>,
}

struct OnsetEnvelopes {
    full: Vec<f64>,
    low: Vec<f64>,
}

pub(crate) struct TempoAnalyzer {
    fft: FftWorkspace,
    window: Vec<f64>,
    previous: Vec<f64>,
}

impl TempoAnalyzer {
    pub(crate) fn new() -> Self {
        const FRAME_SIZE: usize = 512;
        Self {
            fft: FftWorkspace::new(FRAME_SIZE),
            window: symmetric_hann(FRAME_SIZE),
            previous: vec![0.0; FRAME_SIZE / 2],
        }
    }

    fn onset_envelope(
        &mut self,
        samples: &[f32],
        sample_rate: f64,
        hop_size: usize,
    ) -> Result<OnsetEnvelopes> {
        let frame_size = self.fft.size();
        let maximum_samples = samples
            .len()
            .min((sample_rate * MAX_ENVELOPE_SECONDS) as usize);
        if maximum_samples < frame_size {
            return Ok(OnsetEnvelopes {
                full: Vec::new(),
                low: Vec::new(),
            });
        }
        let frame_count = 1 + (maximum_samples - frame_size) / hop_size;
        let low_band_bins = (LOW_BAND_HZ * frame_size as f64 / sample_rate) as usize;
        let low_band_bins = low_band_bins.max(2).min(frame_size / 2);
        let mut full = vec![0.0; frame_count];
        let mut low = vec![0.0; frame_count];
        self.previous.fill(0.0);

        for frame in 0..frame_count {
            let start = frame * hop_size;
            let spectrum = self
                .fft
                .process_windowed(&samples[start..start + frame_size], &self.window)?;
            let mut flux = 0.0;
            let mut low_flux = 0.0;
            for (bin, value) in spectrum.iter().enumerate().take(frame_size / 2).skip(1) {
                let magnitude = value.norm().ln_1p();
                let rise = (magnitude - self.previous[bin]).max(0.0);
                flux += rise;
                if bin < low_band_bins {
                    low_flux += rise;
                }
                self.previous[bin] = magnitude;
            }
            full[frame] = flux;
            low[frame] = low_flux;
        }
        let frames_per_second = sample_rate / hop_size as f64;
        normalize_envelope(&mut full, frames_per_second);
        normalize_envelope(&mut low, frames_per_second);
        Ok(OnsetEnvelopes { full, low })
    }

    pub(crate) fn analyze(
        &mut self,
        samples: &[f32],
        sample_rate: f64,
        duration: f64,
        audible_start: f64,
    ) -> Result<TempoResult> {
        const FRAME_SIZE: usize = 512;
        const HOP_SIZE: usize = 128;
        let envelopes = self.onset_envelope(samples, sample_rate, HOP_SIZE)?;
        let envelope = &envelopes.full;
        let mut result = TempoResult::default();
        if envelope.len() < 64 {
            return Ok(result);
        }

        let frames_per_second = sample_rate / HOP_SIZE as f64;
        let search_limit = envelope
            .len()
            .min((frames_per_second * MAX_TEMPO_SEARCH_SECONDS) as usize);
        let minimum_lag = (frames_per_second * 60.0 / 200.0).floor().max(2.0) as i32;
        let maximum_lag = (frames_per_second * 60.0 / 70.0).ceil() as i32;
        let mut scores = vec![0.0; maximum_lag as usize + 1];
        let mut best_lag = minimum_lag;
        for lag in minimum_lag..=maximum_lag {
            let bpm = frames_per_second * 60.0 / lag as f64;
            let tempo_prior = (-((bpm - 118.0) / 75.0).powi(2)).exp();
            scores[lag as usize] = correlation(envelope, lag, search_limit)
                + 0.42 * correlation(envelope, lag * 2, search_limit)
                + 0.08 * tempo_prior;
            if scores[lag as usize] > scores[best_lag as usize] {
                best_lag = lag;
            }
        }

        let mut best_metrical = -1.0;
        let mut metrical_lag = best_lag;
        for ratio in [0.5, 1.0, 2.0] {
            let candidate = (best_lag as f64 * ratio).round() as i32;
            if candidate < minimum_lag || candidate > maximum_lag {
                continue;
            }
            let bpm = frames_per_second * 60.0 / candidate as f64;
            let score = correlation(envelope, candidate, search_limit) * metrical_prior(bpm);
            if score > best_metrical {
                best_metrical = score;
                metrical_lag = candidate;
            }
        }
        best_lag = metrical_lag;

        let mut refined_lag = best_lag as f64;
        if best_lag > minimum_lag && best_lag < maximum_lag {
            let left = correlation(envelope, best_lag - 1, search_limit);
            let center = correlation(envelope, best_lag, search_limit);
            let right = correlation(envelope, best_lag + 1, search_limit);
            let denominator = left - 2.0 * center + right;
            if denominator.abs() > 1e-9 {
                refined_lag += (0.5 * (left - right) / denominator).clamp(-0.5, 0.5);
            }
        }

        let estimate_phase = |lag: f64, limit: usize| {
            let phase_count = (lag.round() as usize).max(1);
            let end = limit.min(envelope.len()) as f64;
            let mut best_score = -1.0;
            let mut best = 0;
            for phase in 0..phase_count {
                let mut score = 0.0;
                let mut count = 0;
                let mut position = phase as f64;
                while position < end {
                    score += sample_envelope(envelope, position);
                    count += 1;
                    position += lag;
                }
                score /= count.max(1) as f64;
                if score > best_score {
                    best_score = score;
                    best = phase;
                }
            }
            (best, best_score)
        };

        let (mut best_phase, mut best_phase_score) =
            estimate_phase(refined_lag, (frames_per_second * 30.0) as usize);
        let anchor = |phase: usize, lag: f64| {
            let interval_seconds = lag / frames_per_second;
            let mut first = phase as f64 / frames_per_second;
            while first + interval_seconds < audible_start - 0.15 {
                first += interval_seconds;
            }
            while first > audible_start + interval_seconds {
                first -= interval_seconds;
            }
            first.max(0.0)
        };
        result.first_beat = anchor(best_phase, refined_lag);

        struct TrackedGrid {
            beats: Vec<f64>,
            intervals: Vec<f64>,
        }
        let track = |start_lag: f64, first_beat: f64| {
            let mut grid = TrackedGrid {
                beats: Vec::new(),
                intervals: Vec::new(),
            };
            let search_radius = start_lag * 0.25;
            let max_interval_drift = start_lag * 0.03;
            let envelope_end = envelope.len() as f64 - 1.0;
            let last_frame = duration * frames_per_second;
            let mut position = first_beat * frames_per_second;
            let mut interval = start_lag;
            while position <= last_frame + 1e-6 {
                grid.beats.push(position.max(0.0));
                let predicted = position + interval;
                if predicted > last_frame + 1e-6 {
                    break;
                }
                if predicted + search_radius < envelope_end {
                    let low = (predicted - search_radius).floor() as i32;
                    let high = (predicted + search_radius).ceil() as i32;
                    let mut best_value = -1.0;
                    let mut best_offset = 0.0;
                    for frame in low.max(0)..=high.min(envelope.len() as i32 - 1) {
                        if envelope[frame as usize] > best_value {
                            best_value = envelope[frame as usize];
                            best_offset = frame as f64;
                        }
                    }
                    if best_value > 0.15 {
                        let index = best_offset as usize;
                        if index > 0 && index + 1 < envelope.len() {
                            let left = envelope[index - 1];
                            let center = envelope[index];
                            let right = envelope[index + 1];
                            let denominator = left - 2.0 * center + right;
                            if denominator.abs() > 1e-9 {
                                best_offset +=
                                    (0.5 * (left - right) / denominator).clamp(-0.5, 0.5);
                            }
                        }
                        let error = best_offset - predicted;
                        position = predicted + 0.20 * error;
                        interval = (interval + 0.01 * error).clamp(
                            start_lag - max_interval_drift,
                            start_lag + max_interval_drift,
                        );
                        grid.intervals.push(interval);
                        continue;
                    }
                }
                position = predicted;
            }
            grid
        };

        let learning = track(refined_lag, result.first_beat);
        let mut grid = TrackedGrid {
            beats: learning.beats.clone(),
            intervals: learning.intervals.clone(),
        };
        if learning.intervals.len() >= 8 {
            let mut sorted = learning.intervals;
            sorted.sort_by(f64::total_cmp);
            let locked = sorted[sorted.len() / 2];
            if locked > 0.0 {
                refined_lag = locked;
            }
            (best_phase, best_phase_score) = estimate_phase(refined_lag, envelope.len());
            result.first_beat = anchor(best_phase, refined_lag);
            grid = track(refined_lag, result.first_beat);
        }

        result.beats = grid
            .beats
            .iter()
            .map(|frame| frame / frames_per_second)
            .collect();
        result.bpm = frames_per_second * 60.0 / refined_lag;
        result.beat_interval = 60.0 / result.bpm;

        let mut downbeat_offset = 0;
        let mut downbeat_score = -1.0;
        for offset in 0..4 {
            let mut low_score = 0.0;
            let mut full_score = 0.0;
            let mut count = 0;
            for beat in (offset..result.beats.len().min(256)).step_by(4) {
                let position = result.beats[beat] * frames_per_second;
                low_score += sample_envelope(&envelopes.low, position);
                full_score += sample_envelope(envelope, position);
                count += 1;
            }
            let score = (low_score + 0.4 * full_score) / count.max(1) as f64;
            if score > downbeat_score {
                downbeat_score = score;
                downbeat_offset = offset;
            }
        }
        result.downbeats = result
            .beats
            .iter()
            .skip(downbeat_offset)
            .step_by(4)
            .copied()
            .collect();

        let frame_centre_seconds = FRAME_SIZE as f64 / (2.0 * sample_rate);
        result.first_beat += frame_centre_seconds;
        for beat in &mut result.beats {
            *beat += frame_centre_seconds;
        }
        for beat in &mut result.downbeats {
            *beat += frame_centre_seconds;
        }

        let mut runner_up: f64 = 0.0;
        for lag in minimum_lag..=maximum_lag {
            if (lag - best_lag).abs() > 2 {
                runner_up = runner_up.max(scores[lag as usize]);
            }
        }
        let separation =
            (scores[best_lag as usize] - runner_up) / scores[best_lag as usize].max(0.05);
        result.confidence = (0.35 * scores[best_lag as usize]
            + 0.35 * best_phase_score
            + 0.3 * separation.max(0.0))
        .clamp(0.0, 1.0);
        if !result.bpm.is_finite() || !(60.0..=220.0).contains(&result.bpm) {
            return Ok(TempoResult::default());
        }
        Ok(result)
    }
}

fn normalize_envelope(envelope: &mut [f64], frames_per_second: f64) {
    if envelope.is_empty() {
        return;
    }
    let radius = ((frames_per_second * 0.35) as usize).max(2);
    let mut prefix = vec![0.0; envelope.len() + 1];
    for (index, value) in envelope.iter().enumerate() {
        prefix[index + 1] = prefix[index] + value;
    }
    for index in 0..envelope.len() {
        let left = index.saturating_sub(radius);
        let right = envelope.len().min(index + radius + 1);
        let local_mean = (prefix[right] - prefix[left]) / (right - left).max(1) as f64;
        envelope[index] = (envelope[index] - local_mean * 1.08).max(0.0);
    }
    let peak = envelope.iter().copied().fold(0.0, f64::max);
    if peak > 0.0 {
        for value in envelope {
            *value = (*value / peak).sqrt();
        }
    }
}

fn correlation(values: &[f64], lag: i32, limit: usize) -> f64 {
    let length = limit.min(values.len());
    if lag <= 0 || lag as usize >= length {
        return 0.0;
    }
    let lag = lag as usize;
    let mut cross = 0.0;
    let mut left_energy = 0.0;
    let mut right_energy = 0.0;
    for index in lag..length {
        let left = values[index];
        let right = values[index - lag];
        cross += left * right;
        left_energy += left * left;
        right_energy += right * right;
    }
    cross / (left_energy * right_energy).max(1e-12).sqrt()
}

fn sample_envelope(values: &[f64], position: f64) -> f64 {
    if position < 0.0 || position >= values.len().saturating_sub(1) as f64 {
        return 0.0;
    }
    let left = position as usize;
    let fraction = position - left as f64;
    values[left] * (1.0 - fraction) + values[left + 1] * fraction
}

fn metrical_prior(bpm: f64) -> f64 {
    if bpm <= 0.0 {
        return 0.0;
    }
    let octaves = (bpm / 120.0).log2() / 0.7;
    (-0.5 * octaves * octaves).exp()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn click_track(bpm: f64, seconds: f64, first: f64) -> Vec<f32> {
        const RATE: f64 = 11_025.0;
        let interval = 60.0 / bpm;
        (0..(seconds * RATE) as usize)
            .map(|index| {
                let time = index as f64 / RATE;
                if time < first {
                    return 0.0;
                }
                let phase = (time - first) % interval;
                if phase < 0.035 {
                    ((std::f64::consts::TAU * 55.0 * time).sin() * (1.0 - phase / 0.035)) as f32
                } else {
                    0.0
                }
            })
            .collect()
    }

    #[test]
    fn phase_locked_grid_tracks_known_tempo() {
        let mut analyzer = TempoAnalyzer::new();
        let samples = click_track(128.0, 90.0, 1.0);
        let result = analyzer.analyze(&samples, 11_025.0, 90.0, 1.0).unwrap();
        assert!((result.bpm - 128.0).abs() < 0.08, "{}", result.bpm);
        let target = 75.0;
        let nearest = result
            .beats
            .iter()
            .min_by(|a, b| ((*a - target).abs()).total_cmp(&(*b - target).abs()))
            .copied()
            .unwrap();
        let index = ((nearest - 1.0) / (60.0 / 128.0)).round();
        assert!((nearest - (1.0 + index * 60.0 / 128.0)).abs() < 0.02);
    }
}
