//! Phrase structure inferred from the supplied downbeats.
//!
//! Popular music is built from repeating groups of bars, and transitions land best on a phrase
//! boundary. The grid never invents downbeats — it only counts the ones the caller supplied.

use crate::config::AnalysisConfig;
use crate::types::beat::BeatAnalysis;

/// Score given when the beat grid carries no downbeats at all: neither rewarded nor punished.
const UNKNOWN_ALIGNMENT: f32 = 0.5;
/// Landing halfway through a phrase is musically weaker than landing on its start, but still
/// better than an arbitrary bar.
const HALF_PHRASE_ALIGNMENT: f32 = 0.7;
const OFF_PHRASE_ALIGNMENT: f32 = 0.4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PhraseGrid {
    pub beats_per_bar: u32,
    pub bars_per_phrase: u32,
}

impl PhraseGrid {
    pub fn detect(beats: &BeatAnalysis, config: &AnalysisConfig) -> Self {
        Self {
            beats_per_bar: beats.beats_per_bar(),
            bars_per_phrase: config.bars_per_phrase.max(1),
        }
    }

    pub fn beats_per_phrase(&self) -> u32 {
        self.beats_per_bar * self.bars_per_phrase
    }

    /// How well `time` lands on a phrase boundary, in `0.0..=1.0`.
    ///
    /// Combines *structural* position (which bar of the phrase) with *timing* (how close the
    /// moment actually is to that downbeat), so a time halfway between downbeats scores poorly
    /// even if the nearest one starts a phrase.
    pub fn alignment(&self, beats: &BeatAnalysis, time: f64) -> f32 {
        let Some(index) = beats.nearest_downbeat_index(time) else {
            return UNKNOWN_ALIGNMENT;
        };
        let distance = (beats.downbeats[index] - time).abs();
        let tolerance = beats.beat_interval() * 0.5;
        let timing = if tolerance <= 0.0 {
            0.0
        } else {
            (1.0 - distance / tolerance).max(0.0) as f32
        };
        timing * self.structural_weight(index as u32)
    }

    /// True when `time` sits on the first downbeat of a phrase, within half a beat.
    pub fn is_phrase_start(&self, beats: &BeatAnalysis, time: f64) -> bool {
        let Some(index) = beats.nearest_downbeat_index(time) else {
            return false;
        };
        let distance = (beats.downbeats[index] - time).abs();
        (index as u32).is_multiple_of(self.bars_per_phrase)
            && distance <= beats.beat_interval() * 0.5
    }

    /// Number of whole phrases spanned by `beats_count` beats, when it divides evenly.
    pub fn phrases_in(&self, beats_count: u32) -> Option<u32> {
        let per_phrase = self.beats_per_phrase();
        if per_phrase > 0 && beats_count.is_multiple_of(per_phrase) {
            Some(beats_count / per_phrase)
        } else {
            None
        }
    }

    fn structural_weight(&self, downbeat_index: u32) -> f32 {
        let position = downbeat_index % self.bars_per_phrase;
        if position == 0 {
            1.0
        } else if self.bars_per_phrase.is_multiple_of(2) && position == self.bars_per_phrase / 2 {
            HALF_PHRASE_ALIGNMENT
        } else {
            OFF_PHRASE_ALIGNMENT
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 120 BPM, four beats to the bar.
    fn grid(bars: usize) -> BeatAnalysis {
        let beats: Vec<f64> = (0..bars * 4).map(|i| i as f64 * 0.5).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        BeatAnalysis::new(120.0, beats, downbeats).unwrap()
    }

    fn phrase() -> PhraseGrid {
        PhraseGrid::detect(&grid(16), &AnalysisConfig::default())
    }

    #[test]
    fn detection_reads_the_bar_and_phrase_length() {
        let phrase = phrase();
        assert_eq!(phrase.beats_per_bar, 4);
        assert_eq!(phrase.bars_per_phrase, 4);
        assert_eq!(phrase.beats_per_phrase(), 16);
    }

    #[test]
    fn phrase_starts_score_highest() {
        let beats = grid(16);
        let phrase = phrase();
        assert!((phrase.alignment(&beats, 0.0) - 1.0).abs() < 1e-6);
        assert!((phrase.alignment(&beats, 8.0) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn mid_phrase_downbeats_score_lower() {
        let beats = grid(16);
        let phrase = phrase();
        let half = phrase.alignment(&beats, 4.0);
        let quarter = phrase.alignment(&beats, 2.0);
        assert!((half - HALF_PHRASE_ALIGNMENT).abs() < 1e-6, "half {half}");
        assert!(
            (quarter - OFF_PHRASE_ALIGNMENT).abs() < 1e-6,
            "quarter {quarter}"
        );
        assert!(half > quarter);
    }

    #[test]
    fn timing_between_downbeats_is_penalised() {
        let beats = grid(16);
        let phrase = phrase();
        let on_time = phrase.alignment(&beats, 8.0);
        let late = phrase.alignment(&beats, 8.15);
        assert!(late < on_time, "late {late} on_time {on_time}");
        assert_eq!(phrase.alignment(&beats, 1.0), 0.0);
    }

    #[test]
    fn a_grid_without_downbeats_is_neutral() {
        let beats = BeatAnalysis::new(120.0, vec![0.0, 0.5, 1.0], vec![]).unwrap();
        let phrase = PhraseGrid::detect(&beats, &AnalysisConfig::default());
        assert_eq!(phrase.alignment(&beats, 0.5), UNKNOWN_ALIGNMENT);
        assert!(!phrase.is_phrase_start(&beats, 0.0));
    }

    #[test]
    fn phrase_starts_are_identified() {
        let beats = grid(16);
        let phrase = phrase();
        assert!(phrase.is_phrase_start(&beats, 8.0));
        assert!(phrase.is_phrase_start(&beats, 8.2));
        assert!(!phrase.is_phrase_start(&beats, 4.0));
        assert!(!phrase.is_phrase_start(&beats, 8.4));
    }

    #[test]
    fn phrase_counts_require_an_exact_fit() {
        let phrase = phrase();
        assert_eq!(phrase.phrases_in(32), Some(2));
        assert_eq!(phrase.phrases_in(16), Some(1));
        assert_eq!(phrase.phrases_in(8), None);
    }

    #[test]
    fn odd_phrase_lengths_have_no_halfway_point() {
        let beats = grid(16);
        let config = AnalysisConfig {
            bars_per_phrase: 3,
            ..AnalysisConfig::default()
        };
        let phrase = PhraseGrid::detect(&beats, &config);
        assert_eq!(phrase.beats_per_phrase(), 12);
        assert!((phrase.alignment(&beats, 2.0) - OFF_PHRASE_ALIGNMENT).abs() < 1e-6);
    }
}
