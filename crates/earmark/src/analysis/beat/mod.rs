//! Beat and downbeat tracking from the 50 fps log-mel beat spectrogram, without a model.
//!
//! Two beat-pointer Viterbi decodes read the same band onsets differently: one leans
//! towards the faster of two metrical levels, the other towards the slower. When they
//! land an octave apart, the kick band settles it. Sub-bass-led mixes also get off-beat
//! stretches moved back onto the snare and half-time grooves counted at their pulse.
//! A bar HMM then places downbeats.

mod downbeat;
mod emission;
mod onset;
mod phase;
mod tempo;
mod viterbi;

use super::model_frontends::{
    BEAT_SPECTROGRAM_HOP, BEAT_SPECTROGRAM_MELS, BEAT_SPECTROGRAM_SAMPLE_RATE, ModelFrontends,
};
use crate::audio::AudioBuffer;
use crate::audio::resample::resample;
use crate::error::Result;
use emission::Emissions;
use viterbi::BeatStateSpace;

const FPS: f64 = BEAT_SPECTROGRAM_SAMPLE_RATE / BEAT_SPECTROGRAM_HOP as f64;

/// Tuning for [`BeatTracker`]. Defaults were fitted on labelled beat datasets.
#[derive(Debug, Clone, PartialEq)]
pub struct BeatTrackerConfig {
    pub min_bpm: f64,
    pub max_bpm: f64,
    /// Tempo-change stiffness between consecutive beats.
    pub transition_lambda: f64,
    /// Fraction (1 / lambda) of each beat treated as the beat itself.
    pub observation_lambda: f64,
    /// Kick, low-mid, mid and high onset weights in the fast decoder's activation.
    pub band_weights: [f64; 4],
    /// Fast decoder activation = 1 - exp(-gain * onset).
    pub activation_gain: f64,
    /// Log-normal tempo prior, added per beat.
    pub prior_bpm: f64,
    pub prior_octaves: f64,
    pub prior_weight: f64,
    /// Slow decoder: mean band onset at beats, off-beats and elsewhere.
    pub slow_emission_means: [[f64; 4]; 3],
    pub slow_emission_floor: f64,
    /// Bands are correlated; this scales their summed log-likelihood.
    pub slow_emission_temperature: f64,
    pub slow_prior_bpm: f64,
    pub slow_prior_weight: f64,
    /// Take the slow grid when its kick onsets between beats sit this far (natural log)
    /// below its kick onsets on beats. Negative infinity keeps the fast grid.
    pub kick_threshold: f64,
    /// Also take the slow grid when sub-bass (below ~90 Hz) outweighs the bass above it
    /// by more than this log ratio: 808-led productions are counted at the slower pulse.
    pub sub_bass_threshold: f64,
    /// Sub-bass-led mixes: syncopated 808s blur the kick band, so both decoders lean on
    /// the snare and clap region instead.
    pub heavy_band_weights: [f64; 4],
    pub heavy_slow_emission_means: [[f64; 4]; 3],
    /// Bar model: continuation probability, template spread floor, 3/4 log prior.
    pub bar_stay: f64,
    pub bar_spread_floor: f64,
    pub bar_triple_prior: f64,
    /// Beat decoding runs this many times faster than the 50 fps spectrogram, so fast
    /// tempi fall on finer whole-frame beat intervals.
    pub decode_oversample: usize,
}

impl Default for BeatTrackerConfig {
    fn default() -> Self {
        Self {
            min_bpm: 55.0,
            max_bpm: 215.0,
            transition_lambda: 200.0,
            observation_lambda: 16.0,
            band_weights: [1.0, 1.0, 1.0, 1.0],
            activation_gain: 2.0,
            prior_bpm: 100.0,
            prior_octaves: 1.0,
            prior_weight: 2.0,
            slow_emission_means: [
                [1.54, 1.70, 1.76, 1.82],
                [0.391, 0.422, 0.428, 0.453],
                [0.25, 0.26, 0.26, 0.24],
            ],
            slow_emission_floor: 0.01,
            slow_emission_temperature: 0.5,
            slow_prior_bpm: 120.0,
            slow_prior_weight: 2.0,
            kick_threshold: -0.4,
            sub_bass_threshold: -0.4,
            heavy_band_weights: [1.0, 1.3, 1.9, 1.6],
            heavy_slow_emission_means: [
                [1.37, 1.52, 1.63, 1.46],
                [0.41, 0.45, 0.43, 0.41],
                [0.19, 0.26, 0.28, 0.25],
            ],
            bar_stay: 0.995,
            bar_spread_floor: 0.8,
            bar_triple_prior: -5.0,
            decode_oversample: 2,
        }
    }
}

/// Beats, downbeats and tempo of one track. Times are seconds.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct BeatTrack {
    pub beats: Vec<f64>,
    pub downbeats: Vec<f64>,
    pub bpm: f64,
    pub beats_per_bar: u32,
    /// Probability that the grid follows the music's pulse at some metrical level.
    pub confidence: f64,
    /// Share of beat gaps within 7.5% of the median gap.
    pub regularity: f64,
    /// Log ratio of onset strength on beats to onset strength overall.
    pub support: f64,
    /// Share of beats both decoders place within 50 ms of each other.
    pub agreement: f64,
    /// Probability that the downbeats sit on the bar lines.
    pub bar_confidence: f64,
    /// Per-beat log-likelihood margin of the chosen bar phase over the best rotation.
    pub bar_margin: f64,
}

pub struct BeatTracker {
    config: BeatTrackerConfig,
    space: BeatStateSpace,
}

impl BeatTracker {
    pub fn new(config: BeatTrackerConfig) -> Self {
        let fps = FPS * config.decode_oversample.max(1) as f64;
        let min_interval = (60.0 * fps / config.max_bpm).floor() as usize;
        let max_interval = (60.0 * fps / config.min_bpm).ceil() as usize;
        let space = BeatStateSpace::new(min_interval, max_interval, config.transition_lambda);
        Self { config, space }
    }

    /// `values` is a row-major `[frames][128]` log-mel spectrogram at 50 fps.
    pub fn track(&self, values: &[f32]) -> BeatTrack {
        let frames = values.len() / BEAT_SPECTROGRAM_MELS;
        if frames < 16 {
            return BeatTrack::default();
        }
        let c = &self.config;
        let mut bands = onset::band_flux(values, frames);
        // Silence decodes to a perfectly regular grid; it has no beats to report (sorry, Cage).
        if bands.iter().flatten().all(|v| *v < 1e-4) {
            return BeatTrack::default();
        }
        let mut agreement = 1.0;
        for band in &mut bands {
            onset::normalize(band, 12);
        }
        // Trap hats tick at 140 BPM; the heads nodding along count 70.
        let heavy = sub_bass_ratio(values) > c.sub_bass_threshold;
        let (weights, slow_means) = if heavy {
            (&c.heavy_band_weights, &c.heavy_slow_emission_means)
        } else {
            (&c.band_weights, &c.slow_emission_means)
        };
        let onset = onset::weighted(&bands, weights);
        let k = c.decode_oversample.max(1);
        // Interpolated frames repeat the evidence; scaling keeps its weight per second.
        let per_frame = 1.0 / k as f32;
        let fine = onset::upsample(&onset, k);
        let mut fast =
            emission::activation(&fine, c.activation_gain as f32, c.observation_lambda as f32);
        fast.scale(per_frame);
        let mut grid = self.decode(&fast, &onset, c.prior_bpm, c.prior_weight);
        if c.kick_threshold.is_finite() {
            let fine_bands = bands.each_ref().map(|band| onset::upsample(band, k));
            let mut slow = emission::generative(
                &fine_bands,
                slow_means,
                c.slow_emission_floor as f32,
                c.slow_emission_temperature as f32,
            );
            slow.scale(per_frame);
            let slow_grid = self.decode(&slow, &onset, c.slow_prior_bpm, c.slow_prior_weight);
            agreement = shared_pulse(&grid, &slow_grid);
            if prefers_slow(&grid, &slow_grid, &bands[0], c.kick_threshold, heavy) {
                grid = slow_grid;
            }
        }
        if heavy {
            phase::repair(&mut grid, &bands[2]);
            if let Some(half) = phase::half_time(&grid, &bands[0], &bands[2]) {
                grid = half;
            }
        }
        // The grid runs through leading and trailing silence; keep only beats near sound.
        let (first, last) = audible_span(values);
        grid.retain(|f| *f >= first - 2.0 && *f <= last + 2.0);
        let beats: Vec<f64> = grid.iter().map(|f| f / FPS).collect();
        let (downbeats, beats_per_bar, bar_margin) = self.bars(values, frames, &bands, &grid);
        let regularity = regularity(&grid);
        let support = support(&onset, &grid);
        BeatTrack {
            bpm: tempo::fitted_bpm(&beats).unwrap_or_else(|| median_bpm(&beats)),
            beats,
            downbeats,
            beats_per_bar,
            confidence: confidence(regularity, support, agreement),
            bar_confidence: bar_confidence(regularity, support, agreement, bar_margin as f64),
            bar_margin: bar_margin as f64,
            regularity,
            support,
            agreement,
        }
    }

    /// Downbeats and beats per bar for beats found elsewhere (seconds), from the same
    /// `[frames][128]` spectrogram [`BeatTracker::track`] reads.
    pub fn downbeats(&self, values: &[f32], beats: &[f64]) -> (Vec<f64>, u32) {
        let frames = values.len() / BEAT_SPECTROGRAM_MELS;
        if frames < 16 || beats.is_empty() {
            return (Vec::new(), 4);
        }
        let mut bands = onset::band_flux(values, frames);
        for band in &mut bands {
            onset::normalize(band, 12);
        }
        let grid: Vec<f64> = beats.iter().map(|t| t * FPS).collect();
        let (downbeats, meter, _) = self.bars(values, frames, &bands, &grid);
        (downbeats, meter)
    }

    /// Downbeat times, beats per bar, and bar-phase margin for a grid in frames.
    fn bars(
        &self,
        values: &[f32],
        frames: usize,
        bands: &[Vec<f32>; 4],
        grid: &[f64],
    ) -> (Vec<f64>, u32, f32) {
        let c = &self.config;
        let features = downbeat::features(values, frames, bands, grid);
        let params = downbeat::BarParams {
            stay: c.bar_stay as f32,
            spread_floor: c.bar_spread_floor as f32,
            triple_prior: c.bar_triple_prior as f32,
        };
        let (positions, meter, margin) = downbeat::decode(&features, &params);
        let downbeats = grid
            .iter()
            .zip(&positions)
            .filter(|(_, position)| **position == 0)
            .map(|(frame, _)| frame / FPS)
            .collect();
        (downbeats, meter, margin)
    }

    /// Tracks mono `samples` at any rate; they are resampled to the 22.05 kHz frontend.
    pub(crate) fn track_samples(
        &self,
        frontends: &mut ModelFrontends,
        samples: &[f32],
        sample_rate: f64,
    ) -> Result<BeatTrack> {
        let resampled;
        let mono = if (sample_rate - BEAT_SPECTROGRAM_SAMPLE_RATE).abs() <= 1.0 {
            samples
        } else {
            let buffer = AudioBuffer::new(vec![samples.to_vec()], sample_rate.round() as u32)?;
            resampled = resample(&buffer, BEAT_SPECTROGRAM_SAMPLE_RATE as u32)?;
            resampled.channel(0)
        };
        let spectrogram = frontends.beat_spectrogram(mono, BEAT_SPECTROGRAM_SAMPLE_RATE)?;
        Ok(self.track(&spectrogram.values))
    }

    /// Viterbi beat frames, moved onto the nearest onset peak (fractional 50 fps frames).
    /// `emissions` run at the oversampled decode rate, `onset` at 50 fps.
    fn decode(
        &self,
        emissions: &Emissions,
        onset: &[f32],
        prior_bpm: f64,
        prior_weight: f64,
    ) -> Vec<f64> {
        let c = &self.config;
        let k = c.decode_oversample.max(1);
        let fps = FPS * k as f64;
        let bonus: Vec<f32> = self
            .space
            .intervals()
            .iter()
            .map(|&interval| {
                let octaves = (60.0 * fps / interval as f64 / prior_bpm).log2() / c.prior_octaves;
                (-0.5 * octaves * octaves * prior_weight) as f32
            })
            .collect();
        let e = emissions;
        self.space
            .decode(
                &e.beat,
                &e.off,
                &e.other,
                c.observation_lambda as f32,
                &bonus,
            )
            .into_iter()
            .map(|frame| onset::refine(onset, (frame as f64 / k as f64).round() as usize))
            .collect()
    }
}

/// First and last frame whose mean log-mel level is above digital silence.
fn audible_span(values: &[f32]) -> (f64, f64) {
    let rows = values.as_chunks::<BEAT_SPECTROGRAM_MELS>().0;
    let loud = |row: &[f32; BEAT_SPECTROGRAM_MELS]| {
        row.iter().sum::<f32>() > 0.05 * BEAT_SPECTROGRAM_MELS as f32
    };
    let first = rows.iter().position(loud).unwrap_or(0);
    let last = rows.iter().rposition(loud).unwrap_or(rows.len());
    (first as f64, last as f64)
}

/// Log ratio of mean magnitude in the two lowest mel bands (to ~90 Hz) to bands 2-9
/// (~90-290 Hz).
fn sub_bass_ratio(values: &[f32]) -> f64 {
    let (mut sub, mut bass) = (0.0f64, 0.0f64);
    for row in values.as_chunks::<BEAT_SPECTROGRAM_MELS>().0 {
        let magnitude = |v: f32| (v.exp_m1() / 1000.0) as f64;
        sub += row[..2].iter().map(|v| magnitude(*v)).sum::<f64>();
        bass += row[2..10].iter().map(|v| magnitude(*v)).sum::<f64>();
    }
    (sub / (bass + 1e-9)).max(1e-9).ln()
}

/// True when `slow` runs at half the tempo of `fast` and either the kick band says the
/// points between slow beats are not beats or the mix is sub-bass led.
fn prefers_slow(fast: &[f64], slow: &[f64], kick: &[f32], threshold: f64, heavy: bool) -> bool {
    if fast.len() < 8 || slow.len() < 8 {
        return false;
    }
    let ratio = median_gap(slow) / median_gap(fast);
    if !(1.8..=2.2).contains(&ratio) {
        return false;
    }
    if heavy {
        return true;
    }
    let on = slow.iter().map(|&f| onset::peak(kick, f)).sum::<f32>() / slow.len() as f32;
    let between = slow
        .windows(2)
        .map(|pair| onset::peak(kick, 0.5 * (pair[0] + pair[1])))
        .sum::<f32>()
        / (slow.len() - 1) as f32;
    (((between + 0.05) / (on + 0.05)) as f64).ln() < threshold
}

/// Share of `fast` beats within 2.5 frames of a `slow` beat or of the point between two.
fn shared_pulse(fast: &[f64], slow: &[f64]) -> f64 {
    if fast.is_empty() || slow.len() < 2 {
        return 0.0;
    }
    let mut points: Vec<f64> = slow.to_vec();
    points.extend(slow.windows(2).map(|w| 0.5 * (w[0] + w[1])));
    points.sort_by(f64::total_cmp);
    let hits = fast
        .iter()
        .filter(|&&f| {
            let i = points.partition_point(|&p| p < f);
            let near = |j: usize| points.get(j).is_some_and(|p| (p - f).abs() <= 2.5);
            near(i) || (i > 0 && near(i - 1))
        })
        .count();
    hits as f64 / fast.len() as f64
}

fn regularity(grid: &[f64]) -> f64 {
    let median = median_gap(grid);
    if grid.len() < 3 || median <= 0.0 {
        return 0.0;
    }
    let steady = grid
        .windows(2)
        .filter(|w| ((w[1] - w[0]) / median - 1.0).abs() <= 0.075)
        .count();
    steady as f64 / (grid.len() - 1) as f64
}

fn support(onset: &[f32], grid: &[f64]) -> f64 {
    if grid.is_empty() || onset.is_empty() {
        return 0.0;
    }
    let on = grid
        .iter()
        .map(|&f| onset::peak(onset, f) as f64)
        .sum::<f64>()
        / grid.len() as f64;
    let all = onset.iter().map(|&v| v as f64).sum::<f64>() / onset.len() as f64;
    ((on + 0.1) / (all + 0.1)).ln()
}

/// Logistic fit of "the grid follows the pulse at some metrical level" (AMLt >= 0.8)
/// on 3,717 labelled tracks; AUC 0.83 there, 0.90 on held-out GTZAN.
fn confidence(regularity: f64, support: f64, agreement: f64) -> f64 {
    let z = -6.92 + 3.44 * regularity + 1.25 * support + 3.39 * agreement;
    1.0 / (1.0 + (-z).exp())
}

/// Logistic fit of "downbeat F1 >= 0.8" on 2,907 tracks with bar annotations; AUC 0.82
/// there, 0.81 on held-out GTZAN.
fn bar_confidence(regularity: f64, support: f64, agreement: f64, margin: f64) -> f64 {
    let z = -6.38 + 2.36 * regularity + 1.11 * support + 1.78 * agreement + 5.12 * margin;
    1.0 / (1.0 + (-z).exp())
}

fn median_gap(grid: &[f64]) -> f64 {
    let mut gaps: Vec<f64> = grid.windows(2).map(|w| w[1] - w[0]).collect();
    gaps.sort_by(f64::total_cmp);
    gaps.get(gaps.len() / 2).copied().unwrap_or(0.0)
}

fn median_bpm(beats: &[f64]) -> f64 {
    let gap = median_gap(beats);
    if gap > 0.0 { 60.0 / gap } else { 0.0 }
}

#[cfg(test)]
mod tests;
