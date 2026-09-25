//! Every tunable value the engine uses lives here. Nothing in the DSP, analysis, or planning
//! code is allowed to invent its own constants.

use crate::error::{CrossfadeError, Result};

/// Which tempo the transition converges on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TempoTarget {
    /// Stretch the outgoing track onto the incoming tempo. The incoming track plays at its
    /// native rate, so the consumer can resume normal playback the instant the transition ends.
    Incoming,
    /// Stretch the incoming track onto the outgoing tempo. The incoming track is left running
    /// off-tempo after the transition unless the consumer keeps stretching it.
    Outgoing,
    /// Meet in the middle (geometric mean), splitting the stretch across both tracks.
    Midpoint,
    /// Never beatmatch. Both tracks play at their native rate.
    None,
}

#[derive(Debug, Clone)]
pub struct TempoConfig {
    pub target: TempoTarget,
    /// Stretch amounts within this deviation from 1.0 score as musically free.
    pub preferred_ratio_deviation: f32,
    /// Beyond this the stretch is audible but tolerable; scoring falls off steeply.
    pub acceptable_ratio_deviation: f32,
    /// Hard ceiling. A candidate needing more than this is demoted to an unmatched transition
    /// rather than rejected outright.
    pub max_ratio_deviation: f32,
    /// Keep pitch constant while stretching. Disable to let the transition ride pitch with tempo.
    pub preserve_pitch: bool,
    /// Treat a 2:1 tempo relationship (87 vs 174 BPM) as compatible by folding one side.
    pub allow_half_double: bool,
}

impl Default for TempoConfig {
    fn default() -> Self {
        Self {
            target: TempoTarget::Incoming,
            preferred_ratio_deviation: 0.04,
            acceptable_ratio_deviation: 0.08,
            max_ratio_deviation: 0.12,
            preserve_pitch: true,
            allow_half_double: true,
        }
    }
}

#[derive(Debug, Clone)]
pub struct TimingConfig {
    pub min_duration: f64,
    pub max_duration: f64,
    /// Transition lengths to try, in beats at the target tempo.
    pub allowed_beat_lengths: Vec<u32>,
    /// How far back from the end of the outgoing track to look for a transition point.
    pub outgoing_search_window: f64,
    /// How far into the incoming track to look for an entry point.
    pub incoming_search_window: f64,
    /// Audio that must remain after the transition ends, so the outgoing track never runs dry.
    pub outgoing_tail_guard: f64,
    /// Skip this much of the incoming track before considering entry points.
    pub incoming_head_guard: f64,
    /// Anchor points taken from each side. Total candidates are bounded by
    /// `max_anchors^2 * allowed_beat_lengths.len()`.
    pub max_anchors: usize,
    pub max_candidates: usize,
}

impl Default for TimingConfig {
    fn default() -> Self {
        Self {
            min_duration: 1.5,
            max_duration: 16.0,
            allowed_beat_lengths: vec![4, 8, 16, 32],
            outgoing_search_window: 45.0,
            incoming_search_window: 45.0,
            outgoing_tail_guard: 0.25,
            incoming_head_guard: 0.0,
            max_anchors: 16,
            max_candidates: 1024,
        }
    }
}

#[derive(Debug, Clone)]
pub struct AnalysisConfig {
    pub fft_size: usize,
    pub hop_size: usize,
    /// Upper edge of the "low" band, and the region the bass swap operates on.
    pub bass_crossover_hz: f32,
    /// Boundary between the "mid" and "high" bands.
    pub mid_crossover_hz: f32,
    /// Spectral flux above `mean + k * stddev` counts as a transient.
    pub transient_sigma: f32,
    /// Frames quieter than this (dBFS) are treated as silence when trimming search regions.
    pub silence_floor_db: f32,
    /// Bars per musical phrase. Four bars is the near-universal unit in dance music, and it is
    /// what "16-beat phrase" means at four beats to the bar.
    pub bars_per_phrase: u32,
}

impl Default for AnalysisConfig {
    fn default() -> Self {
        Self {
            fft_size: 2048,
            hop_size: 512,
            bass_crossover_hz: 200.0,
            mid_crossover_hz: 2000.0,
            transient_sigma: 1.5,
            silence_floor_db: -60.0,
            bars_per_phrase: 4,
        }
    }
}

#[derive(Debug, Clone)]
pub struct LoudnessConfig {
    pub match_loudness: bool,
    /// Loudness differences smaller than this are left alone.
    pub tolerance_db: f32,
    /// Ceiling on the correction applied to either side.
    pub max_gain_db: f32,
    /// Output peak ceiling in dBFS. The renderer trims the whole mix if it would exceed this.
    pub ceiling_db: f32,
    pub prevent_clipping: bool,
}

impl Default for LoudnessConfig {
    fn default() -> Self {
        Self {
            match_loudness: true,
            tolerance_db: 1.0,
            max_gain_db: 6.0,
            ceiling_db: -0.5,
            prevent_clipping: true,
        }
    }
}

#[derive(Debug, Clone)]
pub struct FilterConfig {
    /// Cutoff the bass swap pivots around.
    pub bass_swap_hz: f32,
    /// How far down the outgoing low-pass sweeps on a filtered blend.
    pub lowpass_min_hz: f32,
    /// Effectively "open" for a low-pass.
    pub lowpass_max_hz: f32,
    /// Effectively "open" for a high-pass.
    pub highpass_min_hz: f32,
    pub q: f32,
    /// Samples between filter coefficient recalculations during a sweep. Recomputing per sample
    /// is inaudibly different and measurably slower.
    pub update_interval: usize,
}

impl Default for FilterConfig {
    fn default() -> Self {
        Self {
            bass_swap_hz: 180.0,
            lowpass_min_hz: 400.0,
            lowpass_max_hz: 18_000.0,
            highpass_min_hz: 20.0,
            q: std::f32::consts::FRAC_1_SQRT_2,
            update_interval: 32,
        }
    }
}

/// Relative importance of each scoring component. Weights are normalised by their sum, so only
/// the ratios matter.
#[derive(Debug, Clone)]
pub struct ScoringWeights {
    pub beat_alignment: f32,
    pub phrase_alignment: f32,
    pub tempo: f32,
    pub spectral: f32,
    pub loudness: f32,
    pub energy: f32,
    pub transient: f32,
    pub low_freq: f32,
    pub duration: f32,
}

impl Default for ScoringWeights {
    fn default() -> Self {
        Self {
            beat_alignment: 1.0,
            phrase_alignment: 1.2,
            tempo: 1.5,
            spectral: 0.8,
            loudness: 0.6,
            energy: 1.0,
            transient: 0.7,
            low_freq: 0.9,
            duration: 0.5,
        }
    }
}

impl ScoringWeights {
    pub fn total(&self) -> f32 {
        self.beat_alignment
            + self.phrase_alignment
            + self.tempo
            + self.spectral
            + self.loudness
            + self.energy
            + self.transient
            + self.low_freq
            + self.duration
    }
}

/// Preferred transition length in beats, used to score duration suitability.
#[derive(Debug, Clone)]
pub struct StrategyConfig {
    pub preferred_beats: u32,
    /// Below this many seconds a transition is rendered as a short fade regardless of tempo fit.
    pub short_fade_max_duration: f64,
    /// Both sides must exceed this share of low-band energy for a bass swap to make sense.
    pub bass_swap_low_energy: f32,
    /// Spectral compatibility below this pushes the planner toward a filtered blend.
    pub filtered_blend_spectral: f32,
}

impl Default for StrategyConfig {
    fn default() -> Self {
        Self {
            preferred_beats: 16,
            short_fade_max_duration: 2.5,
            bass_swap_low_energy: 0.3,
            filtered_blend_spectral: 0.5,
        }
    }
}

#[derive(Debug, Clone)]
pub struct EngineConfig {
    pub tempo: TempoConfig,
    pub timing: TimingConfig,
    pub analysis: AnalysisConfig,
    pub loudness: LoudnessConfig,
    pub filters: FilterConfig,
    pub scoring: ScoringWeights,
    pub strategy: StrategyConfig,
    /// Render at this rate. `None` keeps the incoming track's rate.
    pub output_sample_rate: Option<u32>,
    /// Resample when the two tracks disagree on sample rate. With this off, a mismatch is an error.
    pub allow_resampling: bool,
    /// Attach per-candidate scores to the produced plan.
    pub collect_diagnostics: bool,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            tempo: TempoConfig::default(),
            timing: TimingConfig::default(),
            analysis: AnalysisConfig::default(),
            loudness: LoudnessConfig::default(),
            filters: FilterConfig::default(),
            scoring: ScoringWeights::default(),
            strategy: StrategyConfig::default(),
            output_sample_rate: None,
            allow_resampling: true,
            collect_diagnostics: false,
        }
    }
}

impl EngineConfig {
    pub fn validate(&self) -> Result<()> {
        let a = &self.analysis;
        if !a.fft_size.is_power_of_two() || a.fft_size < 64 {
            return Err(CrossfadeError::config(format!(
                "fft_size must be a power of two >= 64, got {}",
                a.fft_size
            )));
        }
        if a.hop_size == 0 || a.hop_size > a.fft_size {
            return Err(CrossfadeError::config(format!(
                "hop_size must be in 1..={}, got {}",
                a.fft_size, a.hop_size
            )));
        }
        if !(0.0..a.mid_crossover_hz).contains(&a.bass_crossover_hz) {
            return Err(CrossfadeError::config(
                "bass_crossover_hz must be positive and below mid_crossover_hz",
            ));
        }
        if a.bars_per_phrase == 0 {
            return Err(CrossfadeError::config("bars_per_phrase must be non-zero"));
        }

        let t = &self.timing;
        if !(t.min_duration > 0.0 && t.max_duration > t.min_duration) {
            return Err(CrossfadeError::config(
                "require 0 < min_duration < max_duration",
            ));
        }
        if t.allowed_beat_lengths.is_empty() || t.allowed_beat_lengths.contains(&0) {
            return Err(CrossfadeError::config(
                "allowed_beat_lengths must be non-empty and contain no zeroes",
            ));
        }
        if t.max_anchors == 0 || t.max_candidates == 0 {
            return Err(CrossfadeError::config(
                "max_anchors and max_candidates must be non-zero",
            ));
        }
        if t.outgoing_search_window <= 0.0 || t.incoming_search_window <= 0.0 {
            return Err(CrossfadeError::config("search windows must be positive"));
        }

        let m = &self.tempo;
        if !(0.0 < m.preferred_ratio_deviation
            && m.preferred_ratio_deviation <= m.acceptable_ratio_deviation
            && m.acceptable_ratio_deviation <= m.max_ratio_deviation
            && m.max_ratio_deviation < 1.0)
        {
            return Err(CrossfadeError::config(
                "require 0 < preferred <= acceptable <= max ratio deviation < 1",
            ));
        }

        if self.filters.update_interval == 0 {
            return Err(CrossfadeError::config(
                "filters.update_interval must be non-zero",
            ));
        }
        if self.filters.q <= 0.0 {
            return Err(CrossfadeError::config("filters.q must be positive"));
        }
        if self.scoring.total() <= 0.0 {
            return Err(CrossfadeError::config(
                "scoring weights must sum to a positive value",
            ));
        }
        if let Some(rate) = self.output_sample_rate
            && rate < 8_000
        {
            return Err(CrossfadeError::config(
                "output_sample_rate must be at least 8000 Hz",
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_is_valid() {
        EngineConfig::default().validate().unwrap();
    }

    #[test]
    fn rejects_non_power_of_two_fft() {
        let mut config = EngineConfig::default();
        config.analysis.fft_size = 1000;
        assert!(config.validate().is_err());
    }

    #[test]
    fn rejects_hop_larger_than_window() {
        let mut config = EngineConfig::default();
        config.analysis.hop_size = config.analysis.fft_size + 1;
        assert!(config.validate().is_err());
    }

    #[test]
    fn rejects_inverted_durations() {
        let mut config = EngineConfig::default();
        config.timing.min_duration = 10.0;
        config.timing.max_duration = 5.0;
        assert!(config.validate().is_err());
    }

    #[test]
    fn rejects_unordered_tempo_deviations() {
        let mut config = EngineConfig::default();
        config.tempo.preferred_ratio_deviation = 0.2;
        assert!(config.validate().is_err());
    }

    #[test]
    fn rejects_empty_beat_lengths() {
        let mut config = EngineConfig::default();
        config.timing.allowed_beat_lengths.clear();
        assert!(config.validate().is_err());
    }
}
