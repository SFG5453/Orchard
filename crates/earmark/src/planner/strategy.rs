//! Strategy selection and the automation each strategy implies.
//!
//! Choice is deterministic and driven by the measurements, not by a preference list. The result
//! is a fade plan and a filter plan — nothing here touches audio.

use crate::config::{EngineConfig, LoudnessConfig};
use crate::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};
use crate::dsp::fade::FadeCurve;
use crate::dsp::filters::{FilterAutomation, FilterKind};
use crate::dsp::gain::db_to_linear;
use crate::planner::scoring::Evaluation;
use crate::types::diagnostics::Candidate;
use crate::types::transition::{FadePlan, FilterPlan, TransitionStrategy};

/// Where the bass hand-off happens, as a fraction of the transition. Keeping it near the middle
/// and short makes the swap read as a deliberate move rather than a slow blur.
const SWAP_START: f32 = 0.45;
const SWAP_END: f32 = 0.55;
/// The outgoing low-pass stays open for this much of a filtered blend before it starts closing.
const BLEND_FILTER_HOLD: f32 = 0.3;
/// The incoming high-pass has fully opened by this point of a filtered blend.
const BLEND_OPEN_BY: f32 = 0.6;

pub fn choose(
    candidate: &Candidate,
    evaluation: &Evaluation,
    config: &EngineConfig,
) -> TransitionStrategy {
    let strategy = &config.strategy;
    if candidate.duration <= strategy.short_fade_max_duration {
        return TransitionStrategy::ShortFade;
    }

    let clashing = evaluation.score.spectral < strategy.filtered_blend_spectral;
    if !candidate.beatmatched {
        // Without a shared tempo, overlapping percussion just smears; filtering keeps the two
        // tracks out of each other's way instead.
        return if clashing {
            TransitionStrategy::FilteredBlend
        } else {
            TransitionStrategy::EqualPowerCrossfade
        };
    }

    let both_bass_heavy = evaluation.outgoing_region.low >= strategy.bass_swap_low_energy
        && evaluation.incoming_region.low >= strategy.bass_swap_low_energy;
    if both_bass_heavy {
        TransitionStrategy::BassSwap
    } else if clashing {
        TransitionStrategy::FilteredBlend
    } else {
        TransitionStrategy::BeatmatchedCrossfade
    }
}

/// Loudness trim for each side, in dB.
///
/// The correction always lands so the incoming track finishes the transition at its native
/// level, which means the consumer needs no persistent gain offset afterwards.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct GainPlan {
    /// Reached by the end of the transition, starting from unity.
    pub outgoing_db: f32,
    /// Applied at the start of the transition, ramping back to unity by the end.
    pub incoming_db: f32,
}

pub fn loudness_trim(difference_db: f32, config: &LoudnessConfig) -> GainPlan {
    if !config.match_loudness || !difference_db.is_finite() {
        return GainPlan::default();
    }
    if difference_db.abs() <= config.tolerance_db {
        return GainPlan::default();
    }
    let trim = difference_db.abs().min(config.max_gain_db);
    if difference_db > 0.0 {
        GainPlan {
            outgoing_db: -trim,
            incoming_db: 0.0,
        }
    } else {
        GainPlan {
            outgoing_db: 0.0,
            incoming_db: -trim,
        }
    }
}

pub fn build_fade(strategy: TransitionStrategy, gains: GainPlan) -> FadePlan {
    let outgoing_curve = match strategy {
        // The low-pass is already removing the outgoing track, so a gentler amplitude fade
        // avoids attenuating it twice.
        TransitionStrategy::FilteredBlend => FadeCurve::SmoothStep,
        _ => FadeCurve::EqualPower,
    };

    FadePlan {
        outgoing_curve,
        incoming_curve: FadeCurve::EqualPower,
        outgoing_gain: AutomationCurve::ramp(
            1.0,
            db_to_linear(gains.outgoing_db),
            CurveShape::SmoothStep,
        ),
        incoming_gain: AutomationCurve::ramp(
            db_to_linear(gains.incoming_db),
            1.0,
            CurveShape::SmoothStep,
        ),
    }
}

pub fn build_filters(strategy: TransitionStrategy, config: &EngineConfig) -> FilterPlan {
    let filters = &config.filters;
    match strategy {
        TransitionStrategy::BassSwap => FilterPlan {
            outgoing: vec![FilterAutomation::new(
                FilterKind::HighPass,
                hold_then_move(
                    filters.highpass_min_hz,
                    filters.bass_swap_hz,
                    SWAP_START,
                    SWAP_END,
                ),
                filters.q,
            )],
            incoming: vec![FilterAutomation::new(
                FilterKind::HighPass,
                hold_then_move(
                    filters.bass_swap_hz,
                    filters.highpass_min_hz,
                    SWAP_START,
                    SWAP_END,
                ),
                filters.q,
            )],
        },
        TransitionStrategy::FilteredBlend => FilterPlan {
            // The outgoing ride is the one the listener follows, so it gets the eased shape: the
            // corner has to leave 18 kHz and arrive at the crossover without a lurch at either
            // end. The incoming side is only uncovering its own low end and can move plainly.
            outgoing: vec![FilterAutomation::new(
                FilterKind::LowPass,
                sweep(
                    filters.lowpass_max_hz,
                    filters.lowpass_min_hz,
                    BLEND_FILTER_HOLD,
                    1.0,
                    CurveShape::Logistic,
                ),
                filters.q,
            )],
            incoming: vec![FilterAutomation::new(
                FilterKind::HighPass,
                hold_then_move(
                    filters.bass_swap_hz,
                    filters.highpass_min_hz,
                    0.0,
                    BLEND_OPEN_BY,
                ),
                filters.q,
            )],
        },
        _ => FilterPlan::default(),
    }
}

/// Holds `from` until `start`, moves to `to` by `end`, then holds. Frequencies interpolate
/// geometrically so the sweep sounds even across its range.
fn hold_then_move(from: f32, to: f32, start: f32, end: f32) -> AutomationCurve {
    sweep(from, to, start, end, CurveShape::Logarithmic)
}

/// [`hold_then_move`] with the travelling segment's time shape spelled out.
fn sweep(from: f32, to: f32, start: f32, end: f32, shape: CurveShape) -> AutomationCurve {
    let start = start.clamp(0.0, 1.0);
    let end = end.clamp(start, 1.0);
    let mut points = vec![
        AutomationPoint::new(0.0, from, CurveShape::Logarithmic),
        AutomationPoint::new(start, from, shape),
        AutomationPoint::new(end, to, CurveShape::Logarithmic),
    ];
    if end < 1.0 {
        points.push(AutomationPoint::new(1.0, to, CurveShape::Logarithmic));
    }
    AutomationCurve::from_points(points)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::analysis::RegionFeatures;
    use crate::types::diagnostics::CandidateScore;

    fn candidate(duration: f64, beatmatched: bool) -> Candidate {
        Candidate {
            outgoing_start: 100.0,
            incoming_start: 10.0,
            beats: 16,
            duration,
            target_bpm: 128.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            beatmatched,
        }
    }

    fn evaluation(spectral: f32, low: f32) -> Evaluation {
        let region = RegionFeatures {
            low,
            ..RegionFeatures::default()
        };
        Evaluation {
            score: CandidateScore {
                spectral,
                ..CandidateScore::default()
            },
            outgoing_region: region,
            incoming_region: region,
        }
    }

    #[test]
    fn very_short_transitions_are_always_short_fades() {
        let config = EngineConfig::default();
        assert_eq!(
            choose(&candidate(1.0, true), &evaluation(1.0, 0.5), &config),
            TransitionStrategy::ShortFade
        );
    }

    #[test]
    fn two_bass_heavy_tracks_get_a_bass_swap() {
        let config = EngineConfig::default();
        assert_eq!(
            choose(&candidate(8.0, true), &evaluation(0.9, 0.5), &config),
            TransitionStrategy::BassSwap
        );
    }

    #[test]
    fn clashing_spectra_get_a_filtered_blend() {
        let config = EngineConfig::default();
        assert_eq!(
            choose(&candidate(8.0, true), &evaluation(0.2, 0.1), &config),
            TransitionStrategy::FilteredBlend
        );
        assert_eq!(
            choose(&candidate(8.0, false), &evaluation(0.2, 0.1), &config),
            TransitionStrategy::FilteredBlend
        );
    }

    #[test]
    fn compatible_matched_tracks_get_a_beatmatched_crossfade() {
        let config = EngineConfig::default();
        assert_eq!(
            choose(&candidate(8.0, true), &evaluation(0.9, 0.1), &config),
            TransitionStrategy::BeatmatchedCrossfade
        );
    }

    #[test]
    fn unmatched_but_compatible_tracks_get_a_plain_crossfade() {
        let config = EngineConfig::default();
        assert_eq!(
            choose(&candidate(8.0, false), &evaluation(0.9, 0.5), &config),
            TransitionStrategy::EqualPowerCrossfade
        );
    }

    #[test]
    fn small_loudness_gaps_are_left_alone() {
        let config = LoudnessConfig::default();
        assert_eq!(loudness_trim(0.5, &config), GainPlan::default());
        assert_eq!(loudness_trim(-0.5, &config), GainPlan::default());
    }

    #[test]
    fn a_louder_outgoing_track_is_pulled_down() {
        let plan = loudness_trim(4.0, &LoudnessConfig::default());
        assert!((plan.outgoing_db + 4.0).abs() < 1e-6);
        assert_eq!(plan.incoming_db, 0.0);
    }

    #[test]
    fn a_louder_incoming_track_starts_quiet_and_recovers() {
        let plan = loudness_trim(-4.0, &LoudnessConfig::default());
        assert_eq!(plan.outgoing_db, 0.0);
        assert!((plan.incoming_db + 4.0).abs() < 1e-6);

        let fade = build_fade(TransitionStrategy::BassSwap, plan);
        assert!(fade.incoming_gain.value_at(0.0) < 1.0);
        assert!((fade.incoming_gain.value_at(1.0) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn trims_are_capped_and_can_be_disabled() {
        let config = LoudnessConfig::default();
        assert!((loudness_trim(40.0, &config).outgoing_db + config.max_gain_db).abs() < 1e-6);

        let off = LoudnessConfig {
            match_loudness: false,
            ..LoudnessConfig::default()
        };
        assert_eq!(loudness_trim(40.0, &off), GainPlan::default());
        assert_eq!(loudness_trim(f32::NAN, &config), GainPlan::default());
    }

    #[test]
    fn a_bass_swap_hands_the_low_band_over_mid_transition() {
        let config = EngineConfig::default();
        let plan = build_filters(TransitionStrategy::BassSwap, &config);
        let outgoing = &plan.outgoing[0].cutoff;
        let incoming = &plan.incoming[0].cutoff;

        assert!(outgoing.value_at(0.0) < config.filters.bass_swap_hz);
        assert!((outgoing.value_at(1.0) - config.filters.bass_swap_hz).abs() < 1.0);
        assert!((incoming.value_at(0.0) - config.filters.bass_swap_hz).abs() < 1.0);
        assert!(incoming.value_at(1.0) < config.filters.bass_swap_hz);
        assert_eq!(plan.outgoing[0].kind, FilterKind::HighPass);
    }

    #[test]
    fn a_filtered_blend_closes_the_outgoing_track_down() {
        let config = EngineConfig::default();
        let plan = build_filters(TransitionStrategy::FilteredBlend, &config);
        let cutoff = &plan.outgoing[0].cutoff;
        assert_eq!(plan.outgoing[0].kind, FilterKind::LowPass);
        assert!((cutoff.value_at(0.0) - config.filters.lowpass_max_hz).abs() < 1.0);
        assert!((cutoff.value_at(1.0) - config.filters.lowpass_min_hz).abs() < 1.0);
        assert!(cutoff.value_at(0.2) > cutoff.value_at(0.8));
    }

    #[test]
    fn plain_crossfades_use_no_filters() {
        let config = EngineConfig::default();
        assert!(build_filters(TransitionStrategy::EqualPowerCrossfade, &config).is_empty());
        assert!(build_filters(TransitionStrategy::BeatmatchedCrossfade, &config).is_empty());
        assert!(build_filters(TransitionStrategy::ShortFade, &config).is_empty());
    }

    #[test]
    fn a_filtered_blend_softens_the_outgoing_amplitude_fade() {
        let plan = build_fade(TransitionStrategy::FilteredBlend, GainPlan::default());
        assert_eq!(plan.outgoing_curve, FadeCurve::SmoothStep);
        assert_eq!(plan.incoming_curve, FadeCurve::EqualPower);
    }

    #[test]
    fn held_sweeps_stay_flat_before_they_move() {
        let curve = hold_then_move(1_000.0, 100.0, 0.5, 1.0);
        assert!((curve.value_at(0.0) - 1_000.0).abs() < 1e-3);
        assert!((curve.value_at(0.4) - 1_000.0).abs() < 1e-3);
        assert!(curve.value_at(0.75) < 1_000.0);
        assert!((curve.value_at(1.0) - 100.0).abs() < 1e-3);
    }
}
