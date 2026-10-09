//! Candidate generation.
//!
//! Transition points come from the supplied downbeats, never from a fixed offset. Generation is
//! purely combinatorial and deterministic: anchors from each side crossed with the allowed beat
//! lengths, minus anything that does not physically fit.

use crate::config::{EngineConfig, TempoTarget};
use crate::planner::constraints::{TimeWindow, TransitionConstraints};
use crate::types::beat::BeatAnalysis;
use crate::types::diagnostics::Candidate;

pub struct CandidateContext<'a> {
    pub outgoing_beats: &'a BeatAnalysis,
    pub incoming_beats: &'a BeatAnalysis,
    pub outgoing_duration: f64,
    pub incoming_duration: f64,
    pub constraints: &'a TransitionConstraints,
}

pub struct Generated {
    pub candidates: Vec<Candidate>,
    /// Combinations discarded before scoring because they did not fit the audio or the config.
    pub rejected: usize,
}

/// Tempo relationship between the two tracks, after octave folding.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TempoFit {
    pub outgoing_bpm: f32,
    /// Incoming tempo expressed in the same octave as the outgoing one.
    pub incoming_bpm: f32,
    pub target_bpm: f32,
    pub outgoing_ratio: f32,
    pub incoming_ratio: f32,
    pub beatmatched: bool,
}

impl TempoFit {
    /// Tempo the output timeline runs at, which is what beat counts are measured against.
    pub fn reference_bpm(&self) -> f32 {
        self.target_bpm
    }
}

/// Works out how (and whether) the two tempos can be reconciled.
///
/// A pairing that would need more stretch than the configured maximum is not thrown away — it
/// is demoted to an unmatched transition, so the planner still has something to fall back on.
pub fn tempo_fit(outgoing_bpm: f32, incoming_bpm: f32, config: &EngineConfig) -> TempoFit {
    let tempo = &config.tempo;
    let folded = if tempo.allow_half_double {
        fold_to_octave(incoming_bpm, outgoing_bpm)
    } else {
        incoming_bpm
    };

    let unmatched = TempoFit {
        outgoing_bpm,
        incoming_bpm: folded,
        target_bpm: outgoing_bpm,
        outgoing_ratio: 1.0,
        incoming_ratio: 1.0,
        beatmatched: false,
    };

    if tempo.target == TempoTarget::None || outgoing_bpm <= 0.0 || folded <= 0.0 {
        return unmatched;
    }

    let target = match tempo.target {
        TempoTarget::Incoming => folded,
        TempoTarget::Outgoing => outgoing_bpm,
        TempoTarget::Midpoint => (outgoing_bpm * folded).sqrt(),
        TempoTarget::None => unreachable!("handled above"),
    };

    let outgoing_ratio = target / outgoing_bpm;
    let incoming_ratio = target / folded;
    let deviation = (outgoing_ratio - 1.0)
        .abs()
        .max((incoming_ratio - 1.0).abs());
    if deviation > tempo.max_ratio_deviation {
        return unmatched;
    }

    TempoFit {
        outgoing_bpm,
        incoming_bpm: folded,
        target_bpm: target,
        outgoing_ratio,
        incoming_ratio,
        beatmatched: true,
    }
}

pub fn generate(ctx: &CandidateContext, config: &EngineConfig) -> Generated {
    let timing = &config.timing;
    let fit = tempo_fit(ctx.outgoing_beats.bpm, ctx.incoming_beats.bpm, config);
    let reference = fit.reference_bpm();
    if reference <= 0.0 {
        return Generated {
            candidates: Vec::new(),
            rejected: 0,
        };
    }

    let per_side = anchor_budget(
        timing.max_anchors,
        timing.max_candidates,
        timing.allowed_beat_lengths.len(),
    );
    let outgoing_anchors = outgoing_anchors(ctx, config, per_side, fit.outgoing_ratio as f64);
    let incoming_anchors = incoming_anchors(ctx, config, per_side, fit.incoming_ratio as f64);

    let mut candidates = Vec::new();
    let mut rejected = 0usize;

    for &outgoing_start in &outgoing_anchors {
        for &incoming_start in &incoming_anchors {
            for &beats in &timing.allowed_beat_lengths {
                if !ctx.constraints.admits_beats(beats) {
                    rejected += 1;
                    continue;
                }
                let duration = beats as f64 * 60.0 / reference as f64;
                let candidate = Candidate {
                    outgoing_start,
                    incoming_start,
                    beats,
                    duration,
                    target_bpm: fit.target_bpm,
                    outgoing_tempo_ratio: fit.outgoing_ratio,
                    incoming_tempo_ratio: fit.incoming_ratio,
                    beatmatched: fit.beatmatched,
                };
                if fits(&candidate, ctx, config) {
                    candidates.push(candidate);
                } else {
                    rejected += 1;
                }
            }
        }
    }

    if candidates.len() > timing.max_candidates {
        rejected += candidates.len() - timing.max_candidates;
        candidates.truncate(timing.max_candidates);
    }

    Generated {
        candidates,
        rejected,
    }
}

/// Hard feasibility: does this transition actually fit inside both tracks?
fn fits(candidate: &Candidate, ctx: &CandidateContext, config: &EngineConfig) -> bool {
    let timing = &config.timing;
    if !(timing.min_duration..=timing.max_duration).contains(&candidate.duration) {
        return false;
    }
    if candidate.outgoing_start < 0.0 || candidate.incoming_start < 0.0 {
        return false;
    }
    if candidate.outgoing_end() + timing.outgoing_tail_guard > ctx.outgoing_duration {
        return false;
    }
    if candidate.incoming_end() > ctx.incoming_duration {
        return false;
    }
    let constraints = ctx.constraints;
    if !constraints.outgoing.admits(
        candidate.outgoing_start,
        candidate.outgoing_end() - candidate.outgoing_start,
    ) {
        return false;
    }
    if !constraints.incoming.admits(
        candidate.incoming_start,
        candidate.incoming_end() - candidate.incoming_start,
    ) {
        return false;
    }
    true
}

/// Keeps `anchors^2 * beat_lengths` inside the candidate budget so generation cannot blow up on
/// a long track with a dense downbeat grid.
fn anchor_budget(max_anchors: usize, max_candidates: usize, beat_lengths: usize) -> usize {
    let lengths = beat_lengths.max(1);
    let per_side = ((max_candidates / lengths) as f64).sqrt().floor() as usize;
    max_anchors.min(per_side.max(1))
}

fn outgoing_anchors(
    ctx: &CandidateContext,
    config: &EngineConfig,
    limit: usize,
    ratio: f64,
) -> Vec<f64> {
    let timing = &config.timing;
    let feasible = TimeWindow::new(
        0.0,
        ctx.outgoing_duration - timing.min_duration - timing.outgoing_tail_guard,
    );
    let search = ctx
        .constraints
        .outgoing
        .start_bounds(timing, ratio)
        .unwrap_or_else(|| {
            TimeWindow::new(
                ctx.outgoing_duration - timing.outgoing_search_window,
                feasible.latest,
            )
        });
    let window = search.intersect(&feasible);
    if window.is_empty() {
        return Vec::new();
    }

    let anchors = pick(ctx.outgoing_beats, window.earliest.max(0.0), window.latest);
    // The end of a track is where an outro lives, so the latest anchors are the relevant ones.
    anchors[anchors.len().saturating_sub(limit)..].to_vec()
}

fn incoming_anchors(
    ctx: &CandidateContext,
    config: &EngineConfig,
    limit: usize,
    ratio: f64,
) -> Vec<f64> {
    let timing = &config.timing;
    let feasible = TimeWindow::new(timing.incoming_head_guard.max(0.0), ctx.incoming_duration);
    let search = ctx
        .constraints
        .incoming
        .start_bounds(timing, ratio)
        .unwrap_or_else(|| {
            TimeWindow::new(
                feasible.earliest,
                feasible.earliest + timing.incoming_search_window,
            )
        });
    let window = search.intersect(&feasible);
    if window.is_empty() {
        return Vec::new();
    }

    let anchors = pick(ctx.incoming_beats, window.earliest, window.latest);
    anchors[..anchors.len().min(limit)].to_vec()
}

/// Downbeats where available, plain beats otherwise, and the window start as a last resort.
fn pick(beats: &BeatAnalysis, earliest: f64, latest: f64) -> Vec<f64> {
    let downbeats = beats.downbeats_in(earliest, latest);
    if !downbeats.is_empty() {
        return downbeats.to_vec();
    }
    let plain = beats.beats_in(earliest, latest);
    if !plain.is_empty() {
        return plain.to_vec();
    }
    vec![earliest]
}

/// Returns whichever of `bpm`, `bpm * 2`, or `bpm / 2` sits closest to `reference` on a log
/// scale, so a 87 BPM track can be matched against a 174 BPM one.
fn fold_to_octave(bpm: f32, reference: f32) -> f32 {
    if bpm <= 0.0 || reference <= 0.0 {
        return bpm;
    }
    [bpm, bpm * 2.0, bpm * 0.5]
        .into_iter()
        .min_by(|a, b| {
            let da = (a / reference).log2().abs();
            let db = (b / reference).log2().abs();
            da.total_cmp(&db)
        })
        .unwrap_or(bpm)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grid(bpm: f32, bars: usize) -> BeatAnalysis {
        let interval = 60.0 / bpm as f64;
        let beats: Vec<f64> = (0..bars * 4).map(|i| i as f64 * interval).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        BeatAnalysis::new(bpm, beats, downbeats).unwrap()
    }

    fn context(out_bpm: f32, in_bpm: f32) -> (BeatAnalysis, BeatAnalysis) {
        (grid(out_bpm, 60), grid(in_bpm, 60))
    }

    fn generate_with(out_bpm: f32, in_bpm: f32, config: &EngineConfig) -> Generated {
        let (outgoing, incoming) = context(out_bpm, in_bpm);
        let ctx = CandidateContext {
            outgoing_duration: outgoing.beats.last().copied().unwrap() + 10.0,
            incoming_duration: incoming.beats.last().copied().unwrap() + 10.0,
            outgoing_beats: &outgoing,
            incoming_beats: &incoming,
            constraints: &TransitionConstraints::NONE,
        };
        generate(&ctx, config)
    }

    #[test]
    fn close_tempos_beatmatch_onto_the_incoming_track() {
        let fit = tempo_fit(120.0, 124.0, &EngineConfig::default());
        assert!(fit.beatmatched);
        assert!((fit.target_bpm - 124.0).abs() < 1e-3);
        assert!((fit.outgoing_ratio - 124.0 / 120.0).abs() < 1e-6);
        assert!((fit.incoming_ratio - 1.0).abs() < 1e-6);
    }

    #[test]
    fn wildly_different_tempos_are_demoted_rather_than_dropped() {
        let fit = tempo_fit(90.0, 140.0, &EngineConfig::default());
        assert!(!fit.beatmatched);
        assert_eq!(fit.outgoing_ratio, 1.0);
        assert_eq!(fit.incoming_ratio, 1.0);
    }

    #[test]
    fn half_time_tracks_are_folded_into_one_octave() {
        let fit = tempo_fit(174.0, 87.0, &EngineConfig::default());
        assert!(fit.beatmatched);
        assert!((fit.incoming_bpm - 174.0).abs() < 1e-3);
        assert!((fit.incoming_ratio - 1.0).abs() < 1e-6);
    }

    #[test]
    fn folding_can_be_switched_off() {
        let mut config = EngineConfig::default();
        config.tempo.allow_half_double = false;
        assert!(!tempo_fit(174.0, 87.0, &config).beatmatched);
    }

    #[test]
    fn the_midpoint_target_splits_the_stretch() {
        let mut config = EngineConfig::default();
        config.tempo.target = TempoTarget::Midpoint;
        let fit = tempo_fit(120.0, 126.0, &config);
        assert!(fit.beatmatched);
        assert!(fit.outgoing_ratio > 1.0 && fit.incoming_ratio < 1.0);
        assert!((fit.outgoing_ratio * fit.incoming_ratio - 1.0).abs() < 1e-5);
    }

    #[test]
    fn disabling_tempo_matching_leaves_both_sides_native() {
        let mut config = EngineConfig::default();
        config.tempo.target = TempoTarget::None;
        let fit = tempo_fit(120.0, 124.0, &config);
        assert!(!fit.beatmatched);
        assert_eq!(fit.outgoing_ratio, 1.0);
    }

    #[test]
    fn candidates_land_on_downbeats() {
        let config = EngineConfig::default();
        let generated = generate_with(120.0, 120.0, &config);
        assert!(!generated.candidates.is_empty());

        let downbeats = grid(120.0, 60).downbeats;
        for candidate in &generated.candidates {
            assert!(
                downbeats
                    .iter()
                    .any(|d| (d - candidate.outgoing_start).abs() < 1e-9),
                "start {} is not a downbeat",
                candidate.outgoing_start
            );
        }
    }

    #[test]
    fn durations_stay_inside_the_configured_range() {
        let config = EngineConfig::default();
        let generated = generate_with(128.0, 128.0, &config);
        for candidate in &generated.candidates {
            assert!(candidate.duration >= config.timing.min_duration);
            assert!(candidate.duration <= config.timing.max_duration);
        }
    }

    #[test]
    fn candidates_leave_room_in_both_tracks() {
        let config = EngineConfig::default();
        let (outgoing, incoming) = context(120.0, 120.0);
        let ctx = CandidateContext {
            outgoing_duration: 130.0,
            incoming_duration: 130.0,
            outgoing_beats: &outgoing,
            incoming_beats: &incoming,
            constraints: &TransitionConstraints::NONE,
        };
        let generated = generate(&ctx, &config);
        for candidate in &generated.candidates {
            assert!(candidate.outgoing_end() <= 130.0);
            assert!(candidate.incoming_end() <= 130.0);
        }
        assert!(generated.rejected > 0);
    }

    #[test]
    fn generation_is_deterministic() {
        let config = EngineConfig::default();
        let first = generate_with(120.0, 122.0, &config);
        let second = generate_with(120.0, 122.0, &config);
        assert_eq!(first.candidates, second.candidates);
        assert_eq!(first.rejected, second.rejected);
    }

    #[test]
    fn the_candidate_budget_is_respected() {
        let mut config = EngineConfig::default();
        config.timing.max_candidates = 40;
        let generated = generate_with(120.0, 120.0, &config);
        assert!(generated.candidates.len() <= 40);
    }

    #[test]
    fn a_track_too_short_to_transition_yields_nothing() {
        let config = EngineConfig::default();
        let (outgoing, incoming) = context(120.0, 120.0);
        let ctx = CandidateContext {
            outgoing_duration: 0.5,
            incoming_duration: 0.5,
            outgoing_beats: &outgoing,
            incoming_beats: &incoming,
            constraints: &TransitionConstraints::NONE,
        };
        assert!(generate(&ctx, &config).candidates.is_empty());
    }

    #[test]
    fn beat_grids_without_downbeats_fall_back_to_beats() {
        let config = EngineConfig::default();
        let outgoing =
            BeatAnalysis::new(120.0, (0..240).map(|i| i as f64 * 0.5).collect(), vec![]).unwrap();
        let incoming = outgoing.clone();
        let ctx = CandidateContext {
            outgoing_duration: 130.0,
            incoming_duration: 130.0,
            outgoing_beats: &outgoing,
            incoming_beats: &incoming,
            constraints: &TransitionConstraints::NONE,
        };
        assert!(!generate(&ctx, &config).candidates.is_empty());
    }

    #[test]
    fn octave_folding_picks_the_nearest_relationship() {
        assert!((fold_to_octave(87.0, 174.0) - 174.0).abs() < 1e-3);
        assert!((fold_to_octave(174.0, 87.0) - 87.0).abs() < 1e-3);
        assert!((fold_to_octave(126.0, 124.0) - 126.0).abs() < 1e-3);
        assert_eq!(fold_to_octave(0.0, 120.0), 0.0);
    }
}
