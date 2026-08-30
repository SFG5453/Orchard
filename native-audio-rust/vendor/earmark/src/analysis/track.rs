//! Behavior-compatible whole-track analysis ported from Orchard's offline analyzer.

use std::cmp::Ordering;

use super::fft::{FftWorkspace, symmetric_hann};
use super::model_frontends::{BeatSpectrogram, ModelFrontends, VocalSpectrogram};
use super::tempo::{TempoAnalyzer, TempoResult};
use crate::error::{CrossfadeError, Result};
use crate::types::beat::BeatAnalysis;

#[derive(Debug, Clone, PartialEq, Default)]
pub struct EnergyPoint {
    pub time: f64,
    pub energy: f64,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Phrase {
    pub start: f64,
    pub end: f64,
    pub kind: String,
    pub confidence: f64,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct MixCuePoint {
    pub time: f64,
    pub score: f64,
    pub kind: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MeterEvidence {
    pub beats_per_bar: u32,
    pub confidence: f64,
    pub source: String,
}

impl Default for MeterEvidence {
    fn default() -> Self {
        Self {
            beats_per_bar: 4,
            confidence: 0.15,
            source: "assumed-4-4".into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct TransitionFeatureFrame {
    pub time: f64,
    pub energy: f64,
    pub low: f64,
    pub mid: f64,
    pub high: f64,
    pub vocal: f64,
    pub novelty: f64,
    pub transient_density: f64,
    pub stability: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StructuralBoundaryCandidate {
    pub time: f64,
    pub observed_time: f64,
    pub confidence: f64,
    pub source: String,
    pub novelty_peak: f64,
    pub energy_delta: f64,
    pub low_delta: f64,
    pub vocal_delta: f64,
    pub stability_before: f64,
    pub stability_after: f64,
    pub downbeat_distance: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct WholeTrackAnalysis {
    pub duration: f64,
    pub bpm: f64,
    pub beat_interval: f64,
    pub first_beat: f64,
    pub beat_confidence: f64,
    pub beats: Vec<f64>,
    pub downbeats: Vec<f64>,
    pub phrase_boundaries: Vec<f64>,
    pub phrases: Vec<Phrase>,
    pub key: String,
    pub key_confidence: f64,
    pub chroma: Vec<f64>,
    pub audible_start_time: f64,
    pub pickup_time: f64,
    pub pickup_confidence: f64,
    pub mix_in_time: f64,
    pub mix_in_confidence: f64,
    pub intro_end_time: f64,
    pub outro_start_time: f64,
    pub content_end_time: f64,
    pub mix_out_time: f64,
    pub loudness_lufs: f64,
    pub peak_dbfs: f64,
    pub dynamic_range_db: f64,
    pub energy_curve: Vec<EnergyPoint>,
    pub low_energy_curve: Vec<EnergyPoint>,
    pub mid_energy_curve: Vec<EnergyPoint>,
    pub high_energy_curve: Vec<EnergyPoint>,
    pub vocal_activity_mask: Vec<f64>,
    pub vocal_probability: f64,
    pub instrumental_probability: f64,
    pub mix_in_candidates: Vec<MixCuePoint>,
    pub mix_out_candidates: Vec<MixCuePoint>,
    pub meter: MeterEvidence,
    pub transition_feature_frames: Vec<TransitionFeatureFrame>,
    pub structural_boundary_candidates: Vec<StructuralBoundaryCandidate>,
}

impl Default for WholeTrackAnalysis {
    fn default() -> Self {
        Self {
            duration: 0.0,
            bpm: 0.0,
            beat_interval: 0.0,
            first_beat: 0.0,
            beat_confidence: 0.0,
            beats: Vec::new(),
            downbeats: Vec::new(),
            phrase_boundaries: Vec::new(),
            phrases: Vec::new(),
            key: String::new(),
            key_confidence: 0.0,
            chroma: Vec::new(),
            audible_start_time: 0.0,
            pickup_time: 0.0,
            pickup_confidence: 0.0,
            mix_in_time: 0.0,
            mix_in_confidence: 0.0,
            intro_end_time: 0.0,
            outro_start_time: 0.0,
            content_end_time: 0.0,
            mix_out_time: 0.0,
            loudness_lufs: -70.0,
            peak_dbfs: -70.0,
            dynamic_range_db: 0.0,
            energy_curve: Vec::new(),
            low_energy_curve: Vec::new(),
            mid_energy_curve: Vec::new(),
            high_energy_curve: Vec::new(),
            vocal_activity_mask: Vec::new(),
            vocal_probability: 0.0,
            instrumental_probability: 1.0,
            mix_in_candidates: Vec::new(),
            mix_out_candidates: Vec::new(),
            meter: MeterEvidence::default(),
            transition_feature_frames: Vec::new(),
            structural_boundary_candidates: Vec::new(),
        }
    }
}

impl WholeTrackAnalysis {
    pub fn beat_analysis(&self) -> Result<BeatAnalysis> {
        BeatAnalysis::new(self.bpm as f32, self.beats.clone(), self.downbeats.clone())
    }
}

#[derive(Default)]
struct EnvelopeResult {
    window_seconds: f64,
    noise_floor: f64,
    reference: f64,
    threshold: f64,
    audible_start: f64,
    pickup_confidence: f64,
    content_end: f64,
    levels: Vec<f64>,
}

/// Reusable whole-track analyzer, distinct from the transition-window [`super::Analyzer`].
pub struct WholeTrackAnalyzer {
    tempo: TempoAnalyzer,
    timbre_fft: FftWorkspace,
    timbre_window: Vec<f64>,
    previous_spectrum: Vec<f64>,
    frontends: ModelFrontends,
}

impl WholeTrackAnalyzer {
    pub fn new() -> Result<Self> {
        const TIMBRE_FFT: usize = 4096;
        Ok(Self {
            tempo: TempoAnalyzer::new(),
            timbre_fft: FftWorkspace::new(TIMBRE_FFT),
            timbre_window: symmetric_hann(TIMBRE_FFT),
            previous_spectrum: vec![0.0; TIMBRE_FFT / 2],
            frontends: ModelFrontends::new(),
        })
    }

    pub fn analyze(
        &mut self,
        samples: &[f32],
        sample_rate: f64,
        supplied_duration: f64,
    ) -> Result<WholeTrackAnalysis> {
        if samples.is_empty() || !sample_rate.is_finite() || sample_rate < 1000.0 {
            return Err(CrossfadeError::audio(
                "samples must be non-empty and sample rate must be at least 1000 Hz",
            ));
        }
        if !supplied_duration.is_finite() {
            return Err(CrossfadeError::audio("duration must be finite"));
        }
        let duration = if supplied_duration > 0.0 {
            supplied_duration
        } else {
            samples.len() as f64 / sample_rate
        };
        if duration <= 0.0 {
            return Err(CrossfadeError::audio("duration must be positive"));
        }

        let mut result = WholeTrackAnalysis {
            duration,
            ..WholeTrackAnalysis::default()
        };
        let envelope = analyze_envelope(samples, sample_rate, duration);
        result.audible_start_time = envelope.audible_start;
        result.pickup_time = envelope.audible_start;
        result.pickup_confidence = envelope.pickup_confidence;
        result.content_end_time = envelope.content_end;
        result.mix_out_time = find_mix_out_time(samples, sample_rate, duration, &envelope);

        let tempo = self
            .tempo
            .analyze(samples, sample_rate, duration, envelope.audible_start)?;
        copy_tempo(&mut result, tempo);

        let content_start = samples
            .len()
            .min((envelope.audible_start * sample_rate) as usize);
        let content_end = samples
            .len()
            .min((envelope.content_end * sample_rate) as usize);
        let mut square_sum = 0.0;
        let mut peak: f64 = 0.0;
        for sample in &samples[content_start..content_end] {
            square_sum += *sample as f64 * *sample as f64;
            peak = peak.max((*sample as f64).abs());
        }
        let rms = (square_sum / (content_end - content_start).max(1) as f64).sqrt();
        result.loudness_lufs = (to_db(rms) - 0.691).max(-70.0);
        result.peak_dbfs = to_db(peak);
        result.dynamic_range_db = (to_db(percentile(&envelope.levels, 0.95))
            - to_db(percentile(&envelope.levels, 0.2)))
        .clamp(0.0, 70.0);

        let curve_stride = envelope.levels.len().div_ceil(240).max(1);
        for index in (0..envelope.levels.len()).step_by(curve_stride) {
            result.energy_curve.push(EnergyPoint {
                time: index as f64 * envelope.window_seconds,
                energy: (envelope.levels[index] / envelope.reference.max(1e-6)).clamp(0.0, 1.5),
            });
        }

        let mut low_frames = Vec::new();
        let mut mid_frames = Vec::new();
        let mut high_frames = Vec::new();
        let mut transient_frames = Vec::new();
        let mut vocal_frames = Vec::new();
        self.analyze_key_and_timbre(
            samples,
            sample_rate,
            envelope.audible_start,
            envelope.content_end,
            &mut result,
            &mut low_frames,
            &mut mid_frames,
            &mut high_frames,
            &mut transient_frames,
            &mut vocal_frames,
        )?;
        result.instrumental_probability = (1.0 - result.vocal_probability).clamp(0.0, 1.0);
        result.low_energy_curve = resample_band(&result.energy_curve, &low_frames);
        result.mid_energy_curve = resample_band(&result.energy_curve, &mid_frames);
        result.high_energy_curve = resample_band(&result.energy_curve, &high_frames);

        let mut frame_cursor = 0;
        for point in &result.energy_curve {
            while frame_cursor + 1 < vocal_frames.len()
                && (vocal_frames[frame_cursor + 1].time - point.time).abs()
                    < (vocal_frames[frame_cursor].time - point.time).abs()
            {
                frame_cursor += 1;
            }
            let probability = if vocal_frames.is_empty() {
                result.vocal_probability
            } else {
                vocal_frames[frame_cursor].energy
            };
            result
                .vocal_activity_mask
                .push((probability * if point.energy > 0.25 { 1.0 } else { 0.3 }).clamp(0.0, 1.0));
        }
        build_structure(&envelope, &mut result);
        build_transition_evidence(&mut result, &transient_frames);
        Ok(result)
    }

    pub fn beat_spectrogram(
        &mut self,
        samples: &[f32],
        sample_rate: f64,
    ) -> Result<BeatSpectrogram> {
        self.frontends.beat_spectrogram(samples, sample_rate)
    }

    pub fn vocal_spectrogram(
        &mut self,
        channels: &[&[f32]],
        sample_rate: f64,
    ) -> Result<VocalSpectrogram> {
        self.frontends.vocal_spectrogram(channels, sample_rate)
    }

    #[allow(clippy::too_many_arguments)]
    fn analyze_key_and_timbre(
        &mut self,
        samples: &[f32],
        sample_rate: f64,
        start_time: f64,
        end_time: f64,
        result: &mut WholeTrackAnalysis,
        low_frames: &mut Vec<EnergyPoint>,
        mid_frames: &mut Vec<EnergyPoint>,
        high_frames: &mut Vec<EnergyPoint>,
        transient_frames: &mut Vec<EnergyPoint>,
        vocal_frames: &mut Vec<EnergyPoint>,
    ) -> Result<()> {
        let frame_size = self.timbre_fft.size();
        let hop_size = frame_size.max((sample_rate * 0.65) as usize);
        let first_sample = samples.len().min((start_time * sample_rate) as usize);
        let final_sample = samples.len().min((end_time * sample_rate) as usize);
        let mut chroma = [0.0; 12];
        self.previous_spectrum.fill(0.0);
        let mut has_previous = false;
        let mut chroma_weight = 0.0;
        let mut low_energy = 0.0;
        let mut vocal_energy = 0.0;
        let mut high_energy = 0.0;
        let mut flatness_total = 0.0;
        let mut accepted_frames = 0;

        for start in (first_sample..final_sample.saturating_sub(frame_size) + 1).step_by(hop_size) {
            if start + frame_size > final_sample {
                break;
            }
            let frame = &samples[start..start + frame_size];
            let square_sum: f64 = frame
                .iter()
                .map(|value| *value as f64 * *value as f64)
                .sum();
            let rms = (square_sum / frame_size as f64).sqrt();
            if rms < 0.0025 {
                continue;
            }
            let spectrum = self
                .timbre_fft
                .process_windowed(frame, &self.timbre_window)?;
            let mut frame_chroma = 0.0;
            let mut log_sum = 0.0;
            let mut arithmetic_sum = 0.0;
            let mut flatness_bins = 0;
            let mut frame_low = 0.0;
            let mut frame_vocal = 0.0;
            let mut frame_high = 0.0;
            let mut positive_flux = 0.0;
            let mut spectral_total = 0.0;
            for (bin, value) in spectrum.iter().enumerate().take(frame_size / 2).skip(1) {
                let frequency = bin as f64 * sample_rate / frame_size as f64;
                if frequency < 45.0 || frequency > 5000.0_f64.min(sample_rate * 0.48) {
                    continue;
                }
                let power = value.norm_sqr();
                let perceptual_power = power.ln_1p();
                spectral_total += perceptual_power;
                if has_previous {
                    positive_flux += (perceptual_power - self.previous_spectrum[bin]).max(0.0);
                }
                self.previous_spectrum[bin] = perceptual_power;
                if frequency < 250.0 {
                    frame_low += perceptual_power;
                } else if frequency <= 4000.0 {
                    frame_vocal += perceptual_power;
                    log_sum += power.max(1e-12).ln();
                    arithmetic_sum += power;
                    flatness_bins += 1;
                } else {
                    frame_high += perceptual_power;
                }
                let midi = (69.0 + 12.0 * (frequency / 440.0).log2()).round() as i32;
                let pitch_class = midi.rem_euclid(12) as usize;
                chroma[pitch_class] += perceptual_power * rms;
                frame_chroma += perceptual_power;
            }
            let frame_flatness = if flatness_bins > 0 && arithmetic_sum > 0.0 {
                (log_sum / flatness_bins as f64).exp() / (arithmetic_sum / flatness_bins as f64)
            } else {
                0.0
            };
            flatness_total += frame_flatness;
            low_energy += frame_low;
            vocal_energy += frame_vocal;
            high_energy += frame_high;
            let time = (start as f64 + frame_size as f64 / 2.0) / sample_rate;
            low_frames.push(EnergyPoint {
                time,
                energy: frame_low,
            });
            mid_frames.push(EnergyPoint {
                time,
                energy: frame_vocal,
            });
            high_frames.push(EnergyPoint {
                time,
                energy: frame_high,
            });
            transient_frames.push(EnergyPoint {
                time,
                energy: if has_previous {
                    (positive_flux / spectral_total.max(1e-9)).clamp(0.0, 1.0)
                } else {
                    0.0
                },
            });
            vocal_frames.push(EnergyPoint {
                time,
                energy: vocal_probability_from(frame_low, frame_vocal, frame_high, frame_flatness),
            });
            chroma_weight += (frame_chroma * rms).max(1e-9);
            has_previous = true;
            accepted_frames += 1;
        }

        result.chroma = chroma.to_vec();
        let chroma_sum: f64 = result.chroma.iter().sum();
        if chroma_sum > 0.0 {
            for value in &mut result.chroma {
                *value /= chroma_sum;
            }
        }
        const MAJOR: [f64; 12] = [
            6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
        ];
        const MINOR: [f64; 12] = [
            6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
        ];
        const NAMES: [&str; 12] = [
            "C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B",
        ];
        let mut candidates = Vec::with_capacity(24);
        for root in 0..12 {
            let mut major_score = 0.0;
            let mut minor_score = 0.0;
            for pitch in 0..12 {
                major_score += result.chroma[pitch] * MAJOR[(pitch + 12 - root) % 12];
                minor_score += result.chroma[pitch] * MINOR[(pitch + 12 - root) % 12];
            }
            candidates.push((major_score, format!("{} major", NAMES[root])));
            candidates.push((minor_score, format!("{} minor", NAMES[root])));
        }
        candidates.sort_by(|left, right| match right.0.total_cmp(&left.0) {
            Ordering::Equal => right.1.cmp(&left.1),
            ordering => ordering,
        });
        if chroma_weight > 0.0 && candidates.len() >= 2 {
            result.key = candidates[0].1.clone();
            result.key_confidence =
                ((candidates[0].0 - candidates[1].0) / candidates[0].0.max(0.01) * 4.0)
                    .clamp(0.0, 1.0);
        }
        result.vocal_probability = vocal_probability_from(
            low_energy,
            vocal_energy,
            high_energy,
            flatness_total / accepted_frames.max(1) as f64,
        );
        Ok(())
    }
}

fn copy_tempo(result: &mut WholeTrackAnalysis, tempo: TempoResult) {
    result.bpm = tempo.bpm;
    result.beat_interval = tempo.beat_interval;
    result.first_beat = tempo.first_beat;
    result.beat_confidence = tempo.confidence;
    result.beats = tempo.beats;
    result.downbeats = tempo.downbeats;
}

fn to_db(value: f64) -> f64 {
    if value > 1e-9 {
        20.0 * value.log10()
    } else {
        -70.0
    }
}

fn percentile(values: &[f64], ratio: f64) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    sorted[(ratio.clamp(0.0, 1.0) * (sorted.len() - 1) as f64) as usize]
}

fn average(values: &[f64], start: usize, end: usize) -> f64 {
    let start = start.min(values.len());
    let end = end.max(start).min(values.len());
    if start == end {
        0.0
    } else {
        values[start..end].iter().sum::<f64>() / (end - start) as f64
    }
}

fn has_material_recovery(
    levels: &[f64],
    start: usize,
    sustain: usize,
    reference: f64,
    quiet_level: f64,
) -> bool {
    if reference <= 0.0 || quiet_level >= reference * 0.38 {
        return false;
    }
    let threshold = (reference * 0.72).max(quiet_level * 1.8);
    (start..=levels.len().saturating_sub(sustain))
        .any(|index| average(levels, index, index + sustain) >= threshold)
}

fn has_quiet_then_recovery(levels: &[f64], start: usize, sustain: usize, reference: f64) -> bool {
    if reference <= 0.0 {
        return false;
    }
    let mut found_quiet = false;
    for index in start..=levels.len().saturating_sub(sustain) {
        let value = average(levels, index, index + sustain);
        if value < reference * 0.38 {
            found_quiet = true;
        } else if found_quiet && value >= reference * 0.72 {
            return true;
        }
    }
    false
}

fn analyze_envelope(samples: &[f32], sample_rate: f64, duration: f64) -> EnvelopeResult {
    let mut result = EnvelopeResult {
        window_seconds: 0.25,
        ..EnvelopeResult::default()
    };
    let window_size = (sample_rate * result.window_seconds) as usize;
    for frame in samples.chunks(window_size.max(1)) {
        let power: f64 = frame
            .iter()
            .map(|value| *value as f64 * *value as f64)
            .sum();
        result
            .levels
            .push((power / frame.len().max(1) as f64).sqrt());
    }
    if result.levels.is_empty() {
        return result;
    }
    result.noise_floor = percentile(&result.levels, 0.05);
    result.reference = percentile(&result.levels, 0.85);
    result.threshold = 0.0025_f64
        .max((result.noise_floor * 2.6).min(result.reference * 0.28))
        .max(result.reference * 0.1);
    let sustain = (1.5 / result.window_seconds).round() as usize;
    let sustain = sustain.max(4);
    for index in 0..=result.levels.len().saturating_sub(sustain) {
        let slice = &result.levels[index..index + sustain];
        let active = slice
            .iter()
            .filter(|value| **value >= result.threshold)
            .count();
        let peak = slice.iter().copied().fold(0.0, f64::max);
        if active < sustain * 2 / 3 || peak < result.threshold * 1.45 {
            continue;
        }
        result.audible_start = (index as f64 * result.window_seconds - 0.1).max(0.0);
        let local = average(&result.levels, index, index + sustain);
        result.pickup_confidence = ((local - result.noise_floor)
            / (result.reference - result.noise_floor).max(1e-6))
        .clamp(0.0, 1.0);
        break;
    }
    result.content_end = duration;
    let silence_threshold = 0.0015_f64.max((result.threshold * 0.25).min(result.reference * 0.04));
    let mut quiet_start = result.levels.len();
    while quiet_start > 0 && result.levels[quiet_start - 1] < silence_threshold {
        quiet_start -= 1;
    }
    let trailing_silence = duration - quiet_start as f64 * result.window_seconds;
    if trailing_silence >= 0.35 {
        result.content_end = (quiet_start as f64 * result.window_seconds).max(0.0);
    } else {
        for end in (sustain + 1..=result.levels.len()).rev() {
            let start = end - sustain;
            let active = result.levels[start..end]
                .iter()
                .filter(|value| **value >= result.threshold)
                .count();
            if active >= sustain / 2
                && average(&result.levels, start, end) >= result.threshold * 0.85
            {
                result.content_end = duration.min(end as f64 * result.window_seconds);
                break;
            }
        }
    }
    result
}

fn find_mix_out_time(
    samples: &[f32],
    sample_rate: f64,
    duration: f64,
    envelope: &EnvelopeResult,
) -> f64 {
    const WINDOW_SECONDS: f64 = 0.05;
    let window_size = (sample_rate * WINDOW_SECONDS) as usize;
    let levels: Vec<f64> = samples
        .chunks(window_size.max(1))
        .map(|frame| {
            (frame
                .iter()
                .map(|value| *value as f64 * *value as f64)
                .sum::<f64>()
                / frame.len().max(1) as f64)
                .sqrt()
        })
        .collect();
    if levels.is_empty() {
        return envelope.content_end;
    }
    let silence = 0.0015_f64.max((envelope.threshold * 0.25).min(envelope.reference * 0.04));
    let search_start = levels
        .len()
        .min((duration * 0.55 / WINDOW_SECONDS) as usize);
    let context = (2.0 / WINDOW_SECONDS) as usize;
    let recovery = (3.0 / WINDOW_SECONDS).round() as usize;
    let mut best_index = 0;
    let mut best_duration = 0.0;
    let mut index = search_start;
    while index < levels.len() {
        if levels[index] >= silence {
            index += 1;
            continue;
        }
        let mut end = index + 1;
        while end < levels.len() && levels[end] < silence {
            end += 1;
        }
        let silence_duration = (end - index) as f64 * WINDOW_SECONDS;
        if silence_duration >= 0.3 && end as f64 * WINDOW_SECONDS <= duration - 4.0 {
            let before = index.saturating_sub(context);
            let after = levels.len().min(end + context);
            let before_peak = levels[before..index].iter().copied().fold(0.0, f64::max);
            let after_peak = levels[end..after].iter().copied().fold(0.0, f64::max);
            let quiet_level = average(&levels, index, end);
            let early_gap = index as f64 * WINDOW_SECONDS < envelope.content_end * 0.8;
            if before_peak >= silence * 2.0
                && after_peak >= silence * 2.0
                && (!early_gap
                    || !has_material_recovery(
                        &levels,
                        end,
                        recovery,
                        envelope.reference,
                        quiet_level,
                    ))
                && silence_duration > best_duration
            {
                best_index = index;
                best_duration = silence_duration;
            }
        }
        index = end;
    }
    if best_index == 0 {
        return envelope.content_end;
    }
    let cliff_threshold = (silence * 2.0).max(envelope.reference * 0.65);
    let maximum_backtrack = (4.0 / WINDOW_SECONDS) as usize;
    let mut cliff = best_index;
    while cliff > search_start
        && best_index - cliff < maximum_backtrack
        && levels[cliff - 1] < cliff_threshold
    {
        cliff -= 1;
    }
    cliff as f64 * WINDOW_SECONDS
}

fn vocal_probability_from(low: f64, vocal: f64, high: f64, flatness: f64) -> f64 {
    let total = low + vocal + high;
    let mid_ratio = vocal / total.max(1e-12);
    let low_ratio = low / total.max(1e-12);
    let score = -2.4 + 5.2 * mid_ratio - 0.8 * low_ratio + 0.6 * flatness;
    (1.0 / (1.0 + (-score).exp())).clamp(0.0, 1.0)
}

fn nearest_downbeat(downbeats: &[f64], target: f64, fallback: f64) -> f64 {
    if downbeats.is_empty() {
        return fallback;
    }
    let found = downbeats.partition_point(|value| *value < target);
    match (found.checked_sub(1), found < downbeats.len()) {
        (Some(before), true) => {
            if target - downbeats[before] <= downbeats[found] - target {
                downbeats[before]
            } else {
                downbeats[found]
            }
        }
        (Some(before), false) => downbeats[before],
        (None, true) => downbeats[found],
        _ => fallback,
    }
}

fn downbeat_at_or_before(downbeats: &[f64], target: f64, fallback: f64) -> f64 {
    if downbeats.is_empty() {
        return fallback;
    }
    let found = downbeats.partition_point(|value| *value <= target);
    if found == 0 {
        downbeats[0]
    } else {
        downbeats[found - 1]
    }
}

fn build_structure(envelope: &EnvelopeResult, result: &mut WholeTrackAnalysis) {
    let phrase_seconds = if result.beat_interval > 0.0 {
        result.beat_interval * 32.0
    } else {
        16.0
    };
    let phrase_start = result
        .downbeats
        .first()
        .copied()
        .unwrap_or(envelope.audible_start);
    let first_window = (envelope.audible_start / envelope.window_seconds) as usize;
    let four_seconds = (4.0 / envelope.window_seconds) as usize;
    let quiet_windows = (3.0 / envelope.window_seconds).round() as usize;
    let mut strong_window = first_window;
    for index in first_window..=envelope.levels.len().saturating_sub(four_seconds) {
        if average(&envelope.levels, index, index + four_seconds) >= envelope.reference * 0.62 {
            strong_window = index;
            break;
        }
    }
    let raw_intro =
        (phrase_start + phrase_seconds).max(strong_window as f64 * envelope.window_seconds);
    result.intro_end_time = nearest_downbeat(&result.downbeats, raw_intro, raw_intro)
        .clamp(envelope.audible_start, envelope.content_end.min(48.0));

    let mut raw_outro = result
        .intro_end_time
        .max(envelope.content_end - phrase_seconds);
    let search_start =
        (result.intro_end_time.max(envelope.content_end * 0.6) / envelope.window_seconds) as usize;
    for index in search_start..envelope.levels.len().saturating_sub(four_seconds) {
        let section = average(&envelope.levels, index, index + four_seconds);
        let tail = average(&envelope.levels, index, envelope.levels.len());
        if section >= envelope.reference * 0.68 || tail >= envelope.reference * 0.72 {
            continue;
        }
        if !has_quiet_then_recovery(&envelope.levels, index, quiet_windows, envelope.reference) {
            raw_outro = index as f64 * envelope.window_seconds;
            break;
        }
    }
    result.outro_start_time = nearest_downbeat(&result.downbeats, raw_outro, raw_outro)
        .clamp(result.intro_end_time, envelope.content_end);

    result.phrase_boundaries.push(phrase_start);
    let mut time = phrase_start + phrase_seconds;
    while time < envelope.content_end {
        result.phrase_boundaries.push(time);
        time += phrase_seconds;
    }
    result.phrase_boundaries.push(result.intro_end_time);
    result.phrase_boundaries.push(result.outro_start_time);
    if result
        .phrase_boundaries
        .last()
        .is_none_or(|last| *last < envelope.content_end - 0.05)
    {
        result.phrase_boundaries.push(envelope.content_end);
    }
    result.phrase_boundaries.sort_by(f64::total_cmp);
    result
        .phrase_boundaries
        .dedup_by(|left, right| (*left - *right).abs() < 0.05);
    for pair in result.phrase_boundaries.windows(2) {
        let start = pair[0];
        let end = pair[1];
        let energy = average(
            &envelope.levels,
            (start / envelope.window_seconds) as usize,
            (end / envelope.window_seconds).ceil() as usize,
        );
        let kind = if end <= result.intro_end_time + 0.1 {
            "intro"
        } else if start >= result.outro_start_time - 0.1 {
            "outro"
        } else if energy < envelope.reference * 0.58 {
            "breakdown"
        } else {
            "body"
        };
        result.phrases.push(Phrase {
            start,
            end,
            kind: kind.into(),
            confidence: (energy / envelope.reference.max(1e-6)).clamp(0.0, 1.0),
        });
    }

    let eight_bar_target = if result.beat_interval > 0.0 {
        phrase_start + result.beat_interval * 32.0
    } else {
        result.intro_end_time
    };
    let latest_cue = envelope
        .audible_start
        .max(36.0_f64.min(envelope.content_end * 0.28));
    let bounded_target = latest_cue.min(eight_bar_target);
    result.mix_in_time = downbeat_at_or_before(&result.downbeats, bounded_target, bounded_target)
        .clamp(envelope.audible_start, latest_cue);
    let cue_window = (result.mix_in_time / envelope.window_seconds) as usize;
    let cue_energy = average(&envelope.levels, cue_window, cue_window + four_seconds);
    result.mix_in_confidence = (result.beat_confidence * 0.65
        + (cue_energy / envelope.reference.max(1e-6)).clamp(0.0, 1.0) * 0.35)
        .clamp(0.0, 1.0);

    result.mix_in_candidates.push(MixCuePoint {
        time: result.audible_start_time,
        score: 0.8,
        kind: "pickup".into(),
    });
    if result.mix_in_time > result.audible_start_time + 0.1 {
        result.mix_in_candidates.push(MixCuePoint {
            time: result.mix_in_time,
            score: 0.9,
            kind: "intro_drop".into(),
        });
    }
    let drop_cue = if result.beat_interval > 0.0 {
        phrase_start + result.beat_interval * 32.0
    } else {
        result.intro_end_time
    };
    if drop_cue > result.mix_in_time + 0.5 && drop_cue < envelope.content_end * 0.4 {
        result.mix_in_candidates.push(MixCuePoint {
            time: downbeat_at_or_before(&result.downbeats, drop_cue, drop_cue),
            score: 0.95,
            kind: "main_drop".into(),
        });
    }
    if result.mix_out_time > 0.0 && result.mix_out_time < envelope.content_end - 1.0 {
        result.mix_out_candidates.push(MixCuePoint {
            time: result.mix_out_time,
            score: 0.95,
            kind: "energy_cliff".into(),
        });
    }
    result.mix_out_candidates.push(MixCuePoint {
        time: result.outro_start_time,
        score: 0.9,
        kind: "outro_start".into(),
    });
    result.mix_out_candidates.push(MixCuePoint {
        time: envelope.content_end,
        score: 0.75,
        kind: "content_end".into(),
    });
}

fn resample_band(grid: &[EnergyPoint], frames: &[EnergyPoint]) -> Vec<EnergyPoint> {
    let reference = percentile(
        &frames.iter().map(|frame| frame.energy).collect::<Vec<_>>(),
        0.85,
    );
    let mut cursor = 0;
    grid.iter()
        .map(|point| {
            while cursor + 1 < frames.len()
                && (frames[cursor + 1].time - point.time).abs()
                    < (frames[cursor].time - point.time).abs()
            {
                cursor += 1;
            }
            let energy = if frames.is_empty() || point.energy <= 0.1 || reference <= 1e-9 {
                0.0
            } else {
                (frames[cursor].energy / reference).clamp(0.0, 1.5)
            };
            EnergyPoint {
                time: point.time,
                energy,
            }
        })
        .collect()
}

fn local_stability(energy: &[f64], low: &[f64], vocal: &[f64], center: usize) -> f64 {
    if energy.is_empty() {
        return 0.0;
    }
    let start = center.saturating_sub(2);
    let end = energy.len().min(center + 3);
    let deviation = |values: &[f64], scale: f64| {
        if values.len() < end || end <= start {
            return 0.0;
        }
        let mean = average(values, start, end);
        values[start..end]
            .iter()
            .map(|value| (value - mean).abs() / scale)
            .sum::<f64>()
            / (end - start) as f64
    };
    1.0 - (deviation(energy, 1.5) * 0.55
        + deviation(low, 1.5) * 0.25
        + deviation(vocal, 1.0) * 0.20)
        .clamp(0.0, 1.0)
}

fn average_frame_stability(frames: &[TransitionFeatureFrame], start: usize, end: usize) -> f64 {
    let start = start.min(frames.len());
    let end = end.max(start).min(frames.len());
    if start == end {
        0.0
    } else {
        frames[start..end]
            .iter()
            .map(|frame| frame.stability)
            .sum::<f64>()
            / (end - start) as f64
    }
}

fn build_transition_evidence(result: &mut WholeTrackAnalysis, transient_frames: &[EnergyPoint]) {
    let count = result.energy_curve.len();
    if count == 0 {
        return;
    }
    let energy: Vec<f64> = result
        .energy_curve
        .iter()
        .map(|point| point.energy)
        .collect();
    let low: Vec<f64> = (0..count)
        .map(|index| {
            result
                .low_energy_curve
                .get(index)
                .map_or(0.0, |point| point.energy)
        })
        .collect();
    let vocal: Vec<f64> = (0..count)
        .map(|index| {
            result
                .vocal_activity_mask
                .get(index)
                .copied()
                .unwrap_or(0.0)
        })
        .collect();
    let mut transient = vec![0.0; count];
    let mut cursor = 0;
    for index in 0..count {
        while cursor + 1 < transient_frames.len()
            && (transient_frames[cursor + 1].time - result.energy_curve[index].time).abs()
                < (transient_frames[cursor].time - result.energy_curve[index].time).abs()
        {
            cursor += 1;
        }
        if !transient_frames.is_empty() && energy[index] > 0.1 {
            transient[index] = transient_frames[cursor].energy;
        }
    }
    for index in 0..count {
        let previous = index.saturating_sub(1);
        let next = (index + 1).min(count - 1);
        let energy_delta = (energy[next] - energy[previous]).abs() / 1.5;
        let low_delta = (low[next] - low[previous]).abs() / 1.5;
        let vocal_delta = (vocal[next] - vocal[previous]).abs();
        result
            .transition_feature_frames
            .push(TransitionFeatureFrame {
                time: result.energy_curve[index].time,
                energy: energy[index],
                low: low[index],
                mid: result
                    .mid_energy_curve
                    .get(index)
                    .map_or(energy[index], |point| point.energy),
                high: result
                    .high_energy_curve
                    .get(index)
                    .map_or(energy[index], |point| point.energy),
                vocal: vocal[index],
                novelty: (energy_delta * 0.55 + low_delta * 0.25 + vocal_delta * 0.20)
                    .clamp(0.0, 1.0),
                transient_density: transient[index],
                stability: local_stability(&energy, &low, &vocal, index),
            });
    }
    for index in 1..result.transition_feature_frames.len().saturating_sub(1) {
        let previous = &result.transition_feature_frames[index - 1];
        let frame = &result.transition_feature_frames[index];
        let next = &result.transition_feature_frames[index + 1];
        if frame.novelty < 0.14 || frame.novelty < previous.novelty || frame.novelty < next.novelty
        {
            continue;
        }
        let nearest = nearest_downbeat(&result.downbeats, frame.time, frame.time);
        let distance = (nearest - frame.time).abs();
        let time = if distance <= 0.25_f64.max(result.beat_interval) {
            nearest
        } else {
            frame.time
        };
        let context = 4;
        let before = average_frame_stability(
            &result.transition_feature_frames,
            index.saturating_sub(context),
            index,
        );
        let after = average_frame_stability(
            &result.transition_feature_frames,
            index + 1,
            index + 1 + context,
        );
        let candidate = StructuralBoundaryCandidate {
            time,
            observed_time: frame.time,
            confidence: (frame.novelty * 0.65
                + result.beat_confidence * 0.20
                + before.max(after) * 0.15)
                .clamp(0.0, 1.0),
            source: "detected-change".into(),
            novelty_peak: frame.novelty,
            energy_delta: (energy[(index + 1).min(count - 1)] - energy[index - 1]).abs() / 1.5,
            low_delta: (low[(index + 1).min(count - 1)] - low[index - 1]).abs() / 1.5,
            vocal_delta: (vocal[(index + 1).min(count - 1)] - vocal[index - 1]).abs(),
            stability_before: before,
            stability_after: after,
            downbeat_distance: distance,
        };
        if let Some(last) = result.structural_boundary_candidates.last_mut()
            && time - last.time < 4.0
        {
            if candidate.novelty_peak > last.novelty_peak {
                *last = candidate;
            }
            continue;
        }
        result.structural_boundary_candidates.push(candidate);
        if result.structural_boundary_candidates.len() >= 24 {
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spectral_bands_are_independent() {
        let rate = 11_025.0;
        let seconds = 12.0;
        let samples: Vec<f32> = (0..(rate * seconds) as usize)
            .map(|index| {
                let time = index as f64 / rate;
                if !(1.0..11.0).contains(&time) {
                    0.0
                } else {
                    let frequency = if time < 6.0 { 1000.0 } else { 80.0 };
                    (0.2 * (std::f64::consts::TAU * frequency * time).sin()) as f32
                }
            })
            .collect();
        let result = WholeTrackAnalyzer::new()
            .unwrap()
            .analyze(&samples, rate, seconds)
            .unwrap();
        let mean = |curve: &[EnergyPoint], from: f64, to: f64| {
            let selected: Vec<_> = curve
                .iter()
                .filter(|point| point.time >= from && point.time < to)
                .collect();
            selected.iter().map(|point| point.energy).sum::<f64>() / selected.len() as f64
        };
        assert!(
            mean(&result.low_energy_curve, 7.0, 10.0)
                > mean(&result.low_energy_curve, 2.0, 5.0) + 0.7
        );
        assert!(
            mean(&result.mid_energy_curve, 2.0, 5.0)
                > mean(&result.mid_energy_curve, 7.0, 10.0) + 0.7
        );
        assert!((result.vocal_probability + result.instrumental_probability - 1.0).abs() < 1e-12);
    }

    #[test]
    fn invalid_public_input_is_an_error() {
        let error = WholeTrackAnalyzer::new()
            .unwrap()
            .analyze(&[], 11_025.0, 1.0)
            .unwrap_err();
        assert!(error.to_string().contains("non-empty"));
    }
}
