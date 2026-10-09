//! The contract between planning and rendering.
//!
//! A [`TransitionPlan`] is complete: given the same plan and the same PCM, the renderer produces
//! bit-identical output. Nothing is decided during rendering.

use crate::audio::AudioBuffer;
use crate::dsp::automation::AutomationCurve;
use crate::dsp::fade::FadeCurve;
use crate::dsp::filters::FilterAutomation;
use crate::types::diagnostics::TransitionDiagnostics;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TransitionStrategy {
    /// Plain overlapping fade with equal-power curves. No tempo work.
    EqualPowerCrossfade,
    /// Equal-power fade with both sides time-stretched onto a common tempo.
    BeatmatchedCrossfade,
    /// Beatmatched, and the low band is handed over at a single point rather than summed, so
    /// two kick drums never share the bottom octave.
    BassSwap,
    /// The outgoing track is progressively low-passed away while the incoming track opens up.
    /// Used when the two spectra fight each other.
    FilteredBlend,
    /// A short fade for material that cannot be matched or overlapped for long.
    ShortFade,
}

impl TransitionStrategy {
    pub fn is_beatmatched(self) -> bool {
        matches!(self, Self::BeatmatchedCrossfade | Self::BassSwap)
    }

    pub fn describe(self) -> &'static str {
        match self {
            Self::EqualPowerCrossfade => "equal-power crossfade",
            Self::BeatmatchedCrossfade => "beatmatched crossfade",
            Self::BassSwap => "bass swap",
            Self::FilteredBlend => "filtered blend",
            Self::ShortFade => "short fade",
        }
    }
}

/// Fade shapes plus the gain rides layered on top of them.
///
/// Gain curves are linear (not dB) and span the transition, which is how loudness compensation
/// stays gradual instead of stepping at the boundary.
#[derive(Debug, Clone, PartialEq)]
pub struct FadePlan {
    pub outgoing_curve: FadeCurve,
    pub incoming_curve: FadeCurve,
    pub outgoing_gain: AutomationCurve,
    pub incoming_gain: AutomationCurve,
}

impl Default for FadePlan {
    fn default() -> Self {
        Self {
            outgoing_curve: FadeCurve::EqualPower,
            incoming_curve: FadeCurve::EqualPower,
            outgoing_gain: AutomationCurve::constant(1.0),
            incoming_gain: AutomationCurve::constant(1.0),
        }
    }
}

/// Filters applied to each side for the length of the transition. Empty means untouched.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct FilterPlan {
    pub outgoing: Vec<FilterAutomation>,
    pub incoming: Vec<FilterAutomation>,
}

impl FilterPlan {
    pub fn is_empty(&self) -> bool {
        self.outgoing.is_empty() && self.incoming.is_empty()
    }
}

/// Hard source-rate deviation accepted by the exact selected-plan path.
///
/// Orchard uses this stricter limit before authorizing WSOLA. The free Earmark
/// planner may explore wider ratios and demote them, but an already-selected
/// plan must never make a second policy decision during rendering.
pub const MAX_SELECTED_TEMPO_RATIO_DEVIATION: f32 = 0.04;

/// Peak per-side deviation accepted for a selected tempo glide. Each side is
/// furthest from native where its fade leaves it quietest.
pub const MAX_RAMPED_TEMPO_RATIO_DEVIATION: f32 = 0.12;

/// Instantaneous tempo ratios at the first and last output frame of one side. The ratio moves
/// geometrically between them, so the tempo changes by a constant percentage per second.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TempoGlide {
    pub start: f64,
    pub end: f64,
}

impl TempoGlide {
    /// Both sides of a glide from average ratios: one shared tempo moves from the outgoing BPM
    /// to the incoming BPM, so the outgoing starts native and the incoming ends native.
    pub fn pair(outgoing_average: f64, incoming_average: f64) -> (Self, Self) {
        let span = outgoing_average / incoming_average;
        (
            Self { start: 1.0, end: span },
            Self { start: 1.0 / span, end: 1.0 },
        )
    }

    fn flat(self) -> bool {
        (self.end / self.start - 1.0).abs() < 1e-9
    }

    pub fn is_native(self) -> bool {
        (self.start - 1.0).abs() < 1e-9 && (self.end - 1.0).abs() < 1e-9
    }

    /// Source seconds consumed after `time` output seconds of a `duration`-second glide.
    /// Past the end the side continues at its final ratio.
    pub fn offset(self, time: f64, duration: f64) -> f64 {
        if duration <= 0.0 || self.flat() {
            return time * self.start;
        }
        let span = self.end / self.start;
        let within = time.min(duration);
        let glided = duration * self.start * (span.powf(within / duration) - 1.0) / span.ln();
        glided + (time - within).max(0.0) * self.end
    }

    /// Source seconds per output second, averaged over the glide.
    pub fn average(self) -> f64 {
        if self.flat() {
            self.start
        } else {
            (self.end - self.start) / (self.end / self.start).ln()
        }
    }

    pub fn peak_deviation(self) -> f64 {
        (self.start - 1.0).abs().max((self.end - 1.0).abs())
    }
}

/// A caller's authoritative musical decision, before render-shape metadata is
/// filled from the two audio buffers.
///
/// Unlike [`TransitionPlan`], this carries no inferred loudness, filters, or
/// diagnostics. [`crate::SmartCrossfadeEngine::plan_selected`] validates these
/// exact fields and mechanically builds the automation implied by `strategy`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SelectedTransition {
    pub outgoing_start: f64,
    pub incoming_start: f64,
    pub duration: f64,
    pub beats: u32,

    pub outgoing_bpm: f32,
    pub incoming_bpm: f32,
    pub target_bpm: f32,

    /// Source seconds consumed per output second.
    pub outgoing_tempo_ratio: f32,
    pub incoming_tempo_ratio: f32,
    pub outgoing_pitch_semitones: f32,
    pub incoming_pitch_semitones: f32,
    /// The ratios are averages of a [`TempoGlide`] pair.
    pub tempo_ramp: bool,

    pub strategy: TransitionStrategy,
}

impl SelectedTransition {
    pub fn outgoing_end(self) -> f64 {
        self.outgoing_start + self.duration * self.outgoing_tempo_ratio as f64
    }

    pub fn incoming_end(self) -> f64 {
        self.incoming_start + self.duration * self.incoming_tempo_ratio as f64
    }

    pub fn max_ratio_deviation(self) -> f32 {
        (self.outgoing_tempo_ratio - 1.0)
            .abs()
            .max((self.incoming_tempo_ratio - 1.0).abs())
    }

    pub fn tempo_glides(self) -> Option<(TempoGlide, TempoGlide)> {
        self.tempo_ramp.then(|| {
            TempoGlide::pair(
                self.outgoing_tempo_ratio as f64,
                self.incoming_tempo_ratio as f64,
            )
        })
    }
}

/// Everything the renderer needs, and everything a consumer needs to explain the decision.
#[derive(Debug, Clone, PartialEq)]
pub struct TransitionPlan {
    /// Where the transition begins in the outgoing track, in seconds.
    pub outgoing_start: f64,
    /// Where the transition begins in the incoming track, in seconds.
    pub incoming_start: f64,
    /// Length of the rendered transition, in seconds of output time.
    pub duration: f64,
    /// Transition length in beats at [`TransitionPlan::target_bpm`].
    pub beats: u32,

    pub sample_rate: u32,
    pub channels: usize,

    pub outgoing_bpm: f32,
    pub incoming_bpm: f32,
    pub target_bpm: f32,

    /// Source seconds consumed per output second. Above 1.0 the track is sped up.
    pub outgoing_tempo_ratio: f32,
    pub incoming_tempo_ratio: f32,
    /// Pitch offset applied on top of the stretch. Zero keeps pitch independent of tempo.
    pub outgoing_pitch_semitones: f32,
    pub incoming_pitch_semitones: f32,
    /// Both sides follow one gliding tempo; the ratios above are its averages.
    pub tempo_ramp: bool,

    /// Loudness trim reached by the end of the transition for the outgoing side, and applied at
    /// the start for the incoming side. Both are recorded in the fade plan's gain curves.
    pub outgoing_gain_db: f32,
    pub incoming_gain_db: f32,

    pub strategy: TransitionStrategy,
    pub fade: FadePlan,
    pub filters: FilterPlan,
    pub diagnostics: Option<TransitionDiagnostics>,
}

impl TransitionPlan {
    /// Seconds of outgoing source the transition consumes. Differs from `duration` whenever the
    /// outgoing track is stretched.
    pub fn outgoing_source_duration(&self) -> f64 {
        self.duration * self.outgoing_tempo_ratio as f64
    }

    pub fn incoming_source_duration(&self) -> f64 {
        self.duration * self.incoming_tempo_ratio as f64
    }

    /// Position in the outgoing track where the transition finishes.
    pub fn outgoing_end(&self) -> f64 {
        self.outgoing_start + self.outgoing_source_duration()
    }

    /// Position in the incoming track playback should continue from.
    pub fn incoming_end(&self) -> f64 {
        self.incoming_start + self.incoming_source_duration()
    }

    pub fn tempo_glides(&self) -> Option<(TempoGlide, TempoGlide)> {
        self.tempo_ramp.then(|| {
            TempoGlide::pair(
                self.outgoing_tempo_ratio as f64,
                self.incoming_tempo_ratio as f64,
            )
        })
    }

    pub fn frames(&self) -> usize {
        (self.duration * self.sample_rate as f64).round() as usize
    }

    pub fn tempo_adjustment_percent(&self) -> f32 {
        (self.outgoing_tempo_ratio - 1.0) * 100.0
    }

    /// Human-readable explanation, for logs and debug overlays.
    pub fn summary(&self) -> String {
        let mut text = format!(
            "Selected {}-beat transition\n\nOutgoing start: {:.2}s\nIncoming start: {:.2}s\n\
             Duration: {:.2}s\nTempo: {:.1} -> {:.1} BPM ({:+.1}%)\nStrategy: {}\n",
            self.beats,
            self.outgoing_start,
            self.incoming_start,
            self.duration,
            self.outgoing_bpm,
            self.target_bpm,
            self.tempo_adjustment_percent(),
            self.strategy.describe(),
        );
        if let Some(diagnostics) = &self.diagnostics
            && let Some(selected) = diagnostics.selected()
        {
            let score = &selected.score;
            text.push_str(&format!(
                "\ntempo compatibility:    {:.2}\nphrase alignment:       {:.2}\n\
                 spectral compatibility: {:.2}\nloudness compatibility: {:.2}\n\
                 total:                  {:.2}\n",
                score.tempo, score.phrase_alignment, score.spectral, score.loudness, score.total,
            ));
        }
        text
    }
}

/// Rendered transition plus the playback positions a consumer resumes from.
#[derive(Debug, Clone, PartialEq)]
pub struct TransitionOutput {
    pub audio: AudioBuffer,
    /// Seconds of source consumed from each track while rendering.
    pub outgoing_consumed: f64,
    pub incoming_consumed: f64,
    /// Where the outgoing track had reached when the transition ended.
    pub outgoing_resume: f64,
    /// Where the incoming track should continue playing from.
    pub incoming_resume: f64,
    /// Deepest limiter reduction where summing pushed the mix past the ceiling. Zero when none
    /// was needed.
    pub ceiling_trim_db: f32,
}

impl TransitionOutput {
    pub fn duration(&self) -> f64 {
        self.audio.duration()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plan() -> TransitionPlan {
        TransitionPlan {
            outgoing_start: 203.42,
            incoming_start: 15.08,
            duration: 8.0,
            beats: 16,
            sample_rate: 48_000,
            channels: 2,
            outgoing_bpm: 120.0,
            incoming_bpm: 124.0,
            target_bpm: 124.0,
            outgoing_tempo_ratio: 124.0 / 120.0,
            incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: 0.0,
            incoming_pitch_semitones: 0.0,
            tempo_ramp: false,
            outgoing_gain_db: -2.0,
            incoming_gain_db: 0.0,
            strategy: TransitionStrategy::BassSwap,
            fade: FadePlan::default(),
            filters: FilterPlan::default(),
            diagnostics: None,
        }
    }

    #[test]
    fn stretched_side_consumes_more_source_than_output() {
        let plan = plan();
        assert!(plan.outgoing_source_duration() > plan.duration);
        assert!((plan.incoming_source_duration() - plan.duration).abs() < 1e-9);
    }

    #[test]
    fn end_positions_follow_the_consumed_source() {
        let plan = plan();
        let expected = plan.outgoing_start + plan.duration * (124.0 / 120.0);
        assert!((plan.outgoing_end() - expected).abs() < 1e-6);
        assert!((plan.incoming_end() - (15.08 + 8.0)).abs() < 1e-9);
    }

    #[test]
    fn frame_count_matches_the_sample_rate() {
        assert_eq!(plan().frames(), 384_000);
    }

    #[test]
    fn tempo_adjustment_is_reported_as_a_percentage() {
        assert!((plan().tempo_adjustment_percent() - 3.3333).abs() < 1e-3);
    }

    #[test]
    fn summary_names_the_strategy_and_timings() {
        let text = plan().summary();
        assert!(text.contains("16-beat"));
        assert!(text.contains("bass swap"));
        assert!(text.contains("203.42s"));
        assert!(text.contains("+3.3%"));
    }

    #[test]
    fn strategy_flags_which_modes_beatmatch() {
        assert!(TransitionStrategy::BassSwap.is_beatmatched());
        assert!(TransitionStrategy::BeatmatchedCrossfade.is_beatmatched());
        assert!(!TransitionStrategy::ShortFade.is_beatmatched());
        assert!(!TransitionStrategy::EqualPowerCrossfade.is_beatmatched());
    }

    #[test]
    fn empty_filter_plan_is_reported_as_such() {
        assert!(FilterPlan::default().is_empty());
    }

    #[test]
    fn a_glide_pair_starts_and_ends_native_and_consumes_whole_beats() {
        let log_mean = 10.0 / (130.0f64 / 120.0).ln();
        let (outgoing, incoming) = TempoGlide::pair(log_mean / 120.0, log_mean / 130.0);
        assert_eq!(outgoing.start, 1.0);
        assert_eq!(incoming.end, 1.0);
        assert!((outgoing.end - 130.0 / 120.0).abs() < 1e-12);
        assert!((incoming.start - 120.0 / 130.0).abs() < 1e-12);
        assert!((outgoing.peak_deviation() - 10.0 / 120.0).abs() < 1e-12);

        // Sixteen beats at the log-mean tempo consume sixteen beats of each source.
        let duration = 16.0 * 60.0 / log_mean;
        assert!((outgoing.offset(duration, duration) - 8.0).abs() < 1e-9);
        assert!((incoming.offset(duration, duration) - 16.0 * 60.0 / 130.0).abs() < 1e-9);
        assert!((outgoing.offset(duration + 1.0, duration) - 8.0 - outgoing.end).abs() < 1e-9);
        assert!((outgoing.average() - log_mean / 120.0).abs() < 1e-12);
    }

    #[test]
    fn a_flat_glide_is_a_constant_stretch() {
        let glide = TempoGlide { start: 1.02, end: 1.02 };
        assert!((glide.offset(4.0, 8.0) - 4.08).abs() < 1e-12);
        assert_eq!(glide.average(), 1.02);
        assert!(TempoGlide { start: 1.0, end: 1.0 }.is_native());
    }
}
