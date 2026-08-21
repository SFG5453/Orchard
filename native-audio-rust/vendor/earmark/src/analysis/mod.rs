//! Feature extraction. Nothing here decides anything — it only measures.

pub mod energy;
pub mod loudness;
pub mod phrase;
pub mod spectrum;

pub use phrase::PhraseGrid;
pub use spectrum::SpectrumAnalyzer;

use crate::audio::AudioBuffer;
use crate::config::AnalysisConfig;
use crate::error::Result;
use crate::types::analysis::{FeatureTrack, RegionFeatures};
use crate::types::beat::BeatAnalysis;

/// Measurements for the part of a track a transition could touch.
///
/// Only the search window is analysed, not the whole track: a five-minute song costs one
/// 45-second STFT pass rather than five minutes of it.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackAnalysis {
    pub features: FeatureTrack,
    pub phrase: PhraseGrid,
    /// Bounds of the analysed window, in seconds from the start of the track.
    pub window_start: f64,
    pub window_end: f64,
    /// Length of the whole track.
    pub duration: f64,
}

impl TrackAnalysis {
    /// Aggregate features for `[start, end]`. Times outside the analysed window simply
    /// contribute no frames.
    pub fn region(&self, start: f64, end: f64, config: &AnalysisConfig) -> RegionFeatures {
        energy::aggregate(self.features.range(start, end), config)
    }

    pub fn covers(&self, start: f64, end: f64) -> bool {
        start >= self.window_start && end <= self.window_end
    }
}

/// Owns the FFT plan and the mono scratch buffer so repeated analyses do not reallocate.
pub struct Analyzer {
    spectrum: SpectrumAnalyzer,
    mono: Vec<f32>,
}

impl Analyzer {
    pub fn new(config: &AnalysisConfig) -> Result<Self> {
        Ok(Self {
            spectrum: SpectrumAnalyzer::new(config)?,
            mono: Vec::new(),
        })
    }

    pub fn analyze_window(
        &mut self,
        buffer: &AudioBuffer,
        beats: &BeatAnalysis,
        window_start: f64,
        window_duration: f64,
        config: &AnalysisConfig,
    ) -> Result<TrackAnalysis> {
        let start_frame = buffer.frame_index(window_start).min(buffer.frames());
        let requested = (window_duration.max(0.0) * buffer.sample_rate() as f64).round() as usize;
        let frames = requested.min(buffer.frames() - start_frame);

        downmix_range(buffer, start_frame, frames, &mut self.mono);
        let start_seconds = start_frame as f64 / buffer.sample_rate() as f64;
        let features =
            self.spectrum
                .analyze(&self.mono, buffer.sample_rate(), start_seconds, config);

        Ok(TrackAnalysis {
            features,
            phrase: PhraseGrid::detect(beats, config),
            window_start: start_seconds,
            window_end: start_seconds + frames as f64 / buffer.sample_rate() as f64,
            duration: buffer.duration(),
        })
    }
}

/// Sums a frame range to mono in place, without materialising an intermediate buffer.
fn downmix_range(buffer: &AudioBuffer, start_frame: usize, frames: usize, out: &mut Vec<f32>) {
    out.clear();
    out.resize(frames, 0.0);
    if frames == 0 {
        return;
    }
    let scale = 1.0 / buffer.channel_count() as f32;
    for channel in 0..buffer.channel_count() {
        let samples = &buffer.channel(channel)[start_frame..start_frame + frames];
        for (slot, sample) in out.iter_mut().zip(samples) {
            *slot += sample * scale;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: u32 = 48_000;

    fn tone(freq: f32, seconds: f64, channels: usize) -> AudioBuffer {
        let frames = (seconds * SR as f64) as usize;
        let channel: Vec<f32> = (0..frames)
            .map(|i| (i as f32 / SR as f32 * freq * std::f32::consts::TAU).sin())
            .collect();
        AudioBuffer::new(vec![channel; channels], SR).unwrap()
    }

    fn beats() -> BeatAnalysis {
        let beats: Vec<f64> = (0..40).map(|i| i as f64 * 0.5).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        BeatAnalysis::new(120.0, beats, downbeats).unwrap()
    }

    #[test]
    fn only_the_requested_window_is_analysed() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let analysis = analyzer
            .analyze_window(&tone(440.0, 10.0, 2), &beats(), 4.0, 2.0, &config)
            .unwrap();

        assert!((analysis.window_start - 4.0).abs() < 1e-6);
        assert!((analysis.window_end - 6.0).abs() < 1e-6);
        assert!((analysis.duration - 10.0).abs() < 1e-6);
        assert!(analysis.features.frames.iter().all(|f| f.time >= 4.0));
        assert!(analysis.features.frames.iter().all(|f| f.time <= 6.0));
    }

    #[test]
    fn a_window_past_the_end_is_clipped() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let analysis = analyzer
            .analyze_window(&tone(440.0, 2.0, 1), &beats(), 1.0, 30.0, &config)
            .unwrap();
        assert!((analysis.window_end - 2.0).abs() < 1e-6);
        assert!(!analysis.features.is_empty());
    }

    #[test]
    fn region_lookups_aggregate_the_covered_frames() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let analysis = analyzer
            .analyze_window(&tone(60.0, 6.0, 2), &beats(), 0.0, 6.0, &config)
            .unwrap();

        let region = analysis.region(1.0, 3.0, &config);
        assert!(region.low > 0.8, "bass tone should dominate: {region:?}");
        assert!(analysis.covers(1.0, 3.0));
        assert!(!analysis.covers(1.0, 30.0));
    }

    #[test]
    fn regions_outside_the_window_read_as_silent() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let analysis = analyzer
            .analyze_window(&tone(440.0, 6.0, 1), &beats(), 0.0, 2.0, &config)
            .unwrap();
        assert_eq!(analysis.region(4.0, 5.0, &config).rms, 0.0);
    }

    #[test]
    fn the_phrase_grid_comes_from_the_supplied_beats() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let analysis = analyzer
            .analyze_window(&tone(440.0, 4.0, 1), &beats(), 0.0, 4.0, &config)
            .unwrap();
        assert_eq!(analysis.phrase.beats_per_bar, 4);
        assert_eq!(analysis.phrase.beats_per_phrase(), 16);
    }

    #[test]
    fn analysis_is_repeatable_across_calls() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let buffer = tone(440.0, 4.0, 2);
        let first = analyzer
            .analyze_window(&buffer, &beats(), 0.0, 4.0, &config)
            .unwrap();
        let second = analyzer
            .analyze_window(&buffer, &beats(), 0.0, 4.0, &config)
            .unwrap();
        assert_eq!(first, second);
    }

    #[test]
    fn an_empty_window_produces_no_frames() {
        let config = AnalysisConfig::default();
        let mut analyzer = Analyzer::new(&config).unwrap();
        let analysis = analyzer
            .analyze_window(&tone(440.0, 4.0, 1), &beats(), 0.0, 0.0, &config)
            .unwrap();
        assert!(analysis.features.is_empty());
    }
}
