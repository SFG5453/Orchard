//! STFT feature extraction.
//!
//! The planner works on a handful of interpretable numbers per frame, not raw bins. The FFT
//! plan and every work buffer are owned by the analyser and reused across calls, so a full
//! analysis pass allocates only when the frame count grows.

use std::sync::Arc;

use num_complex::Complex32;
use realfft::{RealFftPlanner, RealToComplex};

use crate::config::AnalysisConfig;
use crate::error::{CrossfadeError, Result};
use crate::types::analysis::{FeatureTrack, FrameFeatures};

pub struct SpectrumAnalyzer {
    fft: Arc<dyn RealToComplex<f32>>,
    window: Vec<f32>,
    frame: Vec<f32>,
    spectrum: Vec<Complex32>,
    scratch: Vec<Complex32>,
    magnitudes: Vec<f32>,
    previous: Vec<f32>,
    fft_size: usize,
    hop: usize,
}

impl SpectrumAnalyzer {
    pub fn new(config: &AnalysisConfig) -> Result<Self> {
        validate_transform(config)?;
        let fft_size = config.fft_size;
        let fft = RealFftPlanner::<f32>::new().plan_fft_forward(fft_size);
        let bins = fft.complex_len();
        Ok(Self {
            window: hann(fft_size),
            frame: fft.make_input_vec(),
            spectrum: fft.make_output_vec(),
            scratch: fft.make_scratch_vec(),
            magnitudes: vec![0.0; bins],
            previous: vec![0.0; bins],
            fft,
            fft_size,
            hop: config.hop_size,
        })
    }

    pub fn fft_size(&self) -> usize {
        self.fft_size
    }

    pub fn hop_size(&self) -> usize {
        self.hop
    }

    /// Analyses a mono signal. `start_time` is the position of `mono[0]` within the track, so
    /// the resulting frame times are absolute rather than region-relative.
    pub fn analyze(
        &mut self,
        mono: &[f32],
        sample_rate: u32,
        start_time: f64,
        config: &AnalysisConfig,
    ) -> FeatureTrack {
        let hop_seconds = self.hop as f64 / sample_rate as f64;
        let mut track = FeatureTrack {
            start_time: start_time + self.fft_size as f64 / 2.0 / sample_rate as f64,
            hop_seconds,
            frames: Vec::new(),
        };
        if mono.len() < self.fft_size {
            return track;
        }

        let bins = self.magnitudes.len();
        let bin_hz = sample_rate as f32 / self.fft_size as f32;
        let low_edge = bin_index(config.bass_crossover_hz, bin_hz, bins);
        let mid_edge = bin_index(config.mid_crossover_hz, bin_hz, bins).max(low_edge);

        self.previous.fill(0.0);
        let frame_count = (mono.len() - self.fft_size) / self.hop + 1;
        track.frames.reserve(frame_count);

        for index in 0..frame_count {
            let offset = index * self.hop;
            let samples = &mono[offset..offset + self.fft_size];
            for (slot, (sample, window)) in
                self.frame.iter_mut().zip(samples.iter().zip(&self.window))
            {
                *slot = sample * window;
            }

            // Only fails on a length mismatch, which the buffers above make impossible.
            let _ = self.fft.process_with_scratch(
                &mut self.frame,
                &mut self.spectrum,
                &mut self.scratch,
            );

            let mut total = 0.0f32;
            let mut weighted = 0.0f32;
            let mut flux = 0.0f32;
            for (k, bin) in self.spectrum.iter().enumerate() {
                let magnitude = bin.norm();
                self.magnitudes[k] = magnitude;
                total += magnitude;
                weighted += magnitude * (k as f32 * bin_hz);
                flux += (magnitude - self.previous[k]).max(0.0);
            }

            let low: f32 = self.magnitudes[..low_edge].iter().sum();
            let mid: f32 = self.magnitudes[low_edge..mid_edge].iter().sum();
            let high: f32 = self.magnitudes[mid_edge..].iter().sum();

            track.frames.push(FrameFeatures {
                time: start_time
                    + (offset as f64 + self.fft_size as f64 / 2.0) / sample_rate as f64,
                rms: crate::dsp::gain::rms(samples),
                low,
                mid,
                high,
                centroid: if total > f32::EPSILON {
                    weighted / total
                } else {
                    0.0
                },
                flux: flux / bins as f32,
            });

            self.previous.copy_from_slice(&self.magnitudes);
        }

        track
    }
}

/// Guards the transform sizes independently of [`crate::config::EngineConfig::validate`], since
/// an analyser can be built from a config that never went through the engine.
fn validate_transform(config: &AnalysisConfig) -> Result<()> {
    if !config.fft_size.is_power_of_two() || config.fft_size < 64 {
        return Err(CrossfadeError::config(
            "fft_size must be a power of two of at least 64",
        ));
    }
    if config.hop_size == 0 || config.hop_size > config.fft_size {
        return Err(CrossfadeError::config(
            "hop_size must be non-zero and no larger than fft_size",
        ));
    }
    Ok(())
}

fn hann(size: usize) -> Vec<f32> {
    (0..size)
        .map(|i| 0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / size as f32).cos())
        .collect()
}

fn bin_index(frequency: f32, bin_hz: f32, bins: usize) -> usize {
    if bin_hz <= 0.0 {
        return 0;
    }
    ((frequency / bin_hz).round() as usize).min(bins)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: u32 = 48_000;

    fn sine(freq: f32, frames: usize) -> Vec<f32> {
        (0..frames)
            .map(|i| (i as f32 / SR as f32 * freq * std::f32::consts::TAU).sin())
            .collect()
    }

    fn analyzer() -> (SpectrumAnalyzer, AnalysisConfig) {
        let config = AnalysisConfig::default();
        (SpectrumAnalyzer::new(&config).unwrap(), config)
    }

    #[test]
    fn frame_count_follows_the_hop_size() {
        let (mut analyzer, config) = analyzer();
        let track = analyzer.analyze(&sine(440.0, 4096), SR, 0.0, &config);
        assert_eq!(track.frames.len(), (4096 - 2048) / 512 + 1);
        assert!((track.hop_seconds - 512.0 / SR as f64).abs() < 1e-12);
    }

    #[test]
    fn short_input_yields_no_frames() {
        let (mut analyzer, config) = analyzer();
        assert!(
            analyzer
                .analyze(&sine(440.0, 100), SR, 0.0, &config)
                .is_empty()
        );
    }

    #[test]
    fn frame_times_are_absolute() {
        let (mut analyzer, config) = analyzer();
        let track = analyzer.analyze(&sine(440.0, 8192), SR, 30.0, &config);
        let expected = 30.0 + 1024.0 / SR as f64;
        assert!((track.frames[0].time - expected).abs() < 1e-9);
        assert!(track.frames.windows(2).all(|w| w[1].time > w[0].time));
    }

    #[test]
    fn a_bass_tone_lands_in_the_low_band() {
        let (mut analyzer, config) = analyzer();
        let track = analyzer.analyze(&sine(60.0, 8192), SR, 0.0, &config);
        let frame = track.frames[2];
        assert!(frame.low > frame.mid * 10.0, "{frame:?}");
        assert!(frame.low > frame.high * 10.0);
        assert!(frame.centroid < 200.0, "centroid {}", frame.centroid);
    }

    #[test]
    fn a_treble_tone_lands_in_the_high_band() {
        let (mut analyzer, config) = analyzer();
        let track = analyzer.analyze(&sine(8_000.0, 8192), SR, 0.0, &config);
        let frame = track.frames[2];
        assert!(frame.high > frame.low * 10.0, "{frame:?}");
        assert!(frame.centroid > 5_000.0, "centroid {}", frame.centroid);
    }

    #[test]
    fn a_mid_tone_lands_in_the_mid_band() {
        let (mut analyzer, config) = analyzer();
        let track = analyzer.analyze(&sine(1_000.0, 8192), SR, 0.0, &config);
        let frame = track.frames[2];
        assert!(frame.mid > frame.low, "{frame:?}");
        assert!(frame.mid > frame.high);
    }

    #[test]
    fn rms_tracks_signal_level() {
        let (mut analyzer, config) = analyzer();
        let loud = analyzer.analyze(&sine(440.0, 8192), SR, 0.0, &config);
        let quiet: Vec<f32> = sine(440.0, 8192).iter().map(|s| s * 0.25).collect();
        let quiet = analyzer.analyze(&quiet, SR, 0.0, &config);
        assert!((loud.frames[2].rms / quiet.frames[2].rms - 4.0).abs() < 0.1);
    }

    #[test]
    fn flux_spikes_on_an_onset() {
        let (mut analyzer, config) = analyzer();
        let mut signal = vec![0.0f32; 8192];
        signal[4096..].copy_from_slice(&sine(1_000.0, 4096));
        let track = analyzer.analyze(&signal, SR, 0.0, &config);
        let quiet = track.frames[1].flux;
        let onset = track.frames.iter().map(|f| f.flux).fold(0.0f32, f32::max);
        assert!(onset > quiet * 10.0, "onset {onset} quiet {quiet}");
    }

    #[test]
    fn silence_produces_defined_features() {
        let (mut analyzer, config) = analyzer();
        let track = analyzer.analyze(&vec![0.0; 8192], SR, 0.0, &config);
        for frame in &track.frames {
            assert_eq!(frame.centroid, 0.0);
            assert_eq!(frame.rms, 0.0);
            assert!(frame.flux.is_finite());
        }
    }

    #[test]
    fn analysis_is_repeatable() {
        let (mut analyzer, config) = analyzer();
        let signal = sine(440.0, 8192);
        let first = analyzer.analyze(&signal, SR, 0.0, &config);
        let second = analyzer.analyze(&signal, SR, 0.0, &config);
        assert_eq!(first, second);
    }

    #[test]
    fn invalid_transform_sizes_are_rejected() {
        let odd_size = AnalysisConfig {
            fft_size: 1000,
            ..AnalysisConfig::default()
        };
        assert!(SpectrumAnalyzer::new(&odd_size).is_err());

        let no_hop = AnalysisConfig {
            hop_size: 0,
            ..AnalysisConfig::default()
        };
        assert!(SpectrumAnalyzer::new(&no_hop).is_err());
    }
}
