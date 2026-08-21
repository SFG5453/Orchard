//! Transition planning: generate, score, select, describe.
//!
//! The planner never touches PCM beyond measuring it. Its output is a [`TransitionPlan`] that
//! fully determines what the renderer will do.

pub mod candidate;
pub mod constraints;
pub mod scoring;
pub mod strategy;

use crate::analysis::{TrackAnalysis, loudness};
use crate::audio::AudioBuffer;
use crate::audio::stretch::semitones_for_ratio;
use crate::config::EngineConfig;
use crate::error::{CrossfadeError, Result};
use crate::planner::candidate::CandidateContext;
use crate::planner::constraints::TransitionConstraints;
use crate::planner::scoring::ScoringContext;
use crate::types::beat::BeatAnalysis;
use crate::types::diagnostics::{ScoredCandidate, TransitionDiagnostics};
use crate::types::transition::TransitionPlan;

pub struct PlanInputs<'a> {
    pub outgoing: &'a AudioBuffer,
    pub incoming: &'a AudioBuffer,
    pub outgoing_beats: &'a BeatAnalysis,
    pub incoming_beats: &'a BeatAnalysis,
    pub outgoing_analysis: &'a TrackAnalysis,
    pub incoming_analysis: &'a TrackAnalysis,
    /// Rate and layout the transition will be rendered at.
    pub sample_rate: u32,
    pub channels: usize,
    /// Caller-supplied limits on where the transition may be placed.
    pub constraints: &'a TransitionConstraints,
}

pub fn plan(inputs: &PlanInputs, config: &EngineConfig) -> Result<TransitionPlan> {
    let generated = candidate::generate(
        &CandidateContext {
            outgoing_beats: inputs.outgoing_beats,
            incoming_beats: inputs.incoming_beats,
            outgoing_duration: inputs.outgoing.duration(),
            incoming_duration: inputs.incoming.duration(),
            constraints: inputs.constraints,
        },
        config,
    );
    if generated.candidates.is_empty() {
        return Err(CrossfadeError::NoViableTransition(format!(
            "no transition fits: {} combinations were rejected for a {:.1}s outgoing track and \
             a {:.1}s incoming track, with {}",
            generated.rejected,
            inputs.outgoing.duration(),
            inputs.incoming.duration(),
            inputs.constraints.describe()
        )));
    }

    let context = ScoringContext {
        outgoing: inputs.outgoing_analysis,
        incoming: inputs.incoming_analysis,
        outgoing_beats: inputs.outgoing_beats,
        incoming_beats: inputs.incoming_beats,
    };

    let mut scored = Vec::with_capacity(generated.candidates.len());
    let mut best = 0usize;
    for (index, candidate) in generated.candidates.iter().enumerate() {
        let evaluation = scoring::evaluate(candidate, &context, config);
        let choice = strategy::choose(candidate, &evaluation, config);
        // Strictly greater keeps the earliest candidate on a tie, which keeps selection stable.
        if evaluation.score.total
            > scored
                .get(best)
                .map_or(f32::MIN, |s: &ScoredCandidate| s.score.total)
        {
            best = index;
        }
        scored.push(ScoredCandidate {
            candidate: *candidate,
            score: evaluation.score,
            strategy: choice,
        });
    }

    let winner = scored[best];
    let selected = winner.candidate;

    // Perceived loudness is measured only for the regions that actually made it, since R128 on
    // every candidate would mean metering hours of audio to render eight seconds.
    let outgoing_loudness = loudness::region_loudness(
        inputs.outgoing,
        selected.outgoing_start,
        selected.duration * selected.outgoing_tempo_ratio as f64,
    )?;
    let incoming_loudness = loudness::region_loudness(
        inputs.incoming,
        selected.incoming_start,
        selected.duration * selected.incoming_tempo_ratio as f64,
    )?;
    let gains = strategy::loudness_trim(
        loudness::difference_db(outgoing_loudness, incoming_loudness),
        &config.loudness,
    );

    let fit = candidate::tempo_fit(inputs.outgoing_beats.bpm, inputs.incoming_beats.bpm, config);
    let pitch = |ratio: f32| {
        if config.tempo.preserve_pitch {
            0.0
        } else {
            semitones_for_ratio(ratio)
        }
    };

    Ok(TransitionPlan {
        outgoing_start: selected.outgoing_start,
        incoming_start: selected.incoming_start,
        duration: selected.duration,
        beats: selected.beats,
        sample_rate: inputs.sample_rate,
        channels: inputs.channels,
        outgoing_bpm: fit.outgoing_bpm,
        incoming_bpm: fit.incoming_bpm,
        target_bpm: selected.target_bpm,
        outgoing_tempo_ratio: selected.outgoing_tempo_ratio,
        incoming_tempo_ratio: selected.incoming_tempo_ratio,
        outgoing_pitch_semitones: pitch(selected.outgoing_tempo_ratio),
        incoming_pitch_semitones: pitch(selected.incoming_tempo_ratio),
        outgoing_gain_db: gains.outgoing_db,
        incoming_gain_db: gains.incoming_db,
        strategy: winner.strategy,
        fade: strategy::build_fade(winner.strategy, gains),
        filters: strategy::build_filters(winner.strategy, config),
        diagnostics: if config.collect_diagnostics {
            Some(TransitionDiagnostics {
                candidates: scored,
                selected_candidate: best,
                rejected: generated.rejected,
            })
        } else {
            None
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analysis::Analyzer;

    const SR: u32 = 44_100;

    fn grid(bpm: f32, seconds: f64) -> BeatAnalysis {
        let interval = 60.0 / bpm as f64;
        let count = (seconds / interval) as usize;
        let beats: Vec<f64> = (0..count).map(|i| i as f64 * interval).collect();
        let downbeats: Vec<f64> = beats.iter().step_by(4).copied().collect();
        BeatAnalysis::new(bpm, beats, downbeats).unwrap()
    }

    /// A kick-like pulse train so the bands and transients look like real music.
    fn track(bpm: f32, seconds: f64, tone: f32) -> AudioBuffer {
        let frames = (seconds * SR as f64) as usize;
        let interval = (60.0 / bpm * SR as f32) as usize;
        let mut channel = vec![0.0f32; frames];
        for (i, sample) in channel.iter_mut().enumerate() {
            let phase = i as f32 / SR as f32;
            let beat_phase = (i % interval.max(1)) as f32 / SR as f32;
            let envelope = (-beat_phase * 12.0).exp();
            *sample = 0.6 * envelope * (phase * 60.0 * std::f32::consts::TAU).sin()
                + 0.2 * (phase * tone * std::f32::consts::TAU).sin();
        }
        AudioBuffer::new(vec![channel; 2], SR).unwrap()
    }

    struct Fixture {
        outgoing: AudioBuffer,
        incoming: AudioBuffer,
        outgoing_beats: BeatAnalysis,
        incoming_beats: BeatAnalysis,
        outgoing_analysis: TrackAnalysis,
        incoming_analysis: TrackAnalysis,
    }

    fn fixture(out_bpm: f32, in_bpm: f32, config: &EngineConfig) -> Fixture {
        let outgoing = track(out_bpm, 60.0, 440.0);
        let incoming = track(in_bpm, 60.0, 660.0);
        let outgoing_beats = grid(out_bpm, 60.0);
        let incoming_beats = grid(in_bpm, 60.0);

        let mut analyzer = Analyzer::new(&config.analysis).unwrap();
        let outgoing_analysis = analyzer
            .analyze_window(&outgoing, &outgoing_beats, 15.0, 45.0, &config.analysis)
            .unwrap();
        let incoming_analysis = analyzer
            .analyze_window(&incoming, &incoming_beats, 0.0, 45.0, &config.analysis)
            .unwrap();

        Fixture {
            outgoing,
            incoming,
            outgoing_beats,
            incoming_beats,
            outgoing_analysis,
            incoming_analysis,
        }
    }

    fn inputs<'a>(fixture: &'a Fixture) -> PlanInputs<'a> {
        PlanInputs {
            outgoing: &fixture.outgoing,
            incoming: &fixture.incoming,
            outgoing_beats: &fixture.outgoing_beats,
            incoming_beats: &fixture.incoming_beats,
            outgoing_analysis: &fixture.outgoing_analysis,
            incoming_analysis: &fixture.incoming_analysis,
            constraints: &TransitionConstraints::NONE,
            sample_rate: SR,
            channels: 2,
        }
    }

    #[test]
    fn a_plan_lands_on_a_downbeat_and_fits_both_tracks() {
        let config = EngineConfig::default();
        let fixture = fixture(120.0, 122.0, &config);
        let plan = plan(&inputs(&fixture), &config).unwrap();

        assert!(
            fixture
                .outgoing_beats
                .downbeats
                .iter()
                .any(|d| (d - plan.outgoing_start).abs() < 1e-6)
        );
        assert!(plan.outgoing_end() <= fixture.outgoing.duration());
        assert!(plan.incoming_end() <= fixture.incoming.duration());
        assert!(plan.duration >= config.timing.min_duration);
    }

    #[test]
    fn planning_is_deterministic() {
        let config = EngineConfig::default();
        let fixture = fixture(120.0, 124.0, &config);
        let first = plan(&inputs(&fixture), &config).unwrap();
        let second = plan(&inputs(&fixture), &config).unwrap();
        assert_eq!(first, second);
    }

    #[test]
    fn close_tempos_produce_a_beatmatched_plan() {
        let config = EngineConfig::default();
        let fixture = fixture(120.0, 123.0, &config);
        let plan = plan(&inputs(&fixture), &config).unwrap();
        assert!((plan.target_bpm - 123.0).abs() < 1e-3);
        assert!((plan.outgoing_tempo_ratio - 123.0 / 120.0).abs() < 1e-5);
        assert!(plan.strategy.is_beatmatched());
    }

    #[test]
    fn incompatible_tempos_fall_back_to_an_unmatched_transition() {
        let config = EngineConfig::default();
        let fixture = fixture(95.0, 140.0, &config);
        let plan = plan(&inputs(&fixture), &config).unwrap();
        assert_eq!(plan.outgoing_tempo_ratio, 1.0);
        assert_eq!(plan.incoming_tempo_ratio, 1.0);
        assert!(!plan.strategy.is_beatmatched());
    }

    #[test]
    fn diagnostics_are_opt_in_and_identify_the_winner() {
        let mut config = EngineConfig::default();
        let fixture = fixture(120.0, 121.0, &config);
        assert!(
            plan(&inputs(&fixture), &config)
                .unwrap()
                .diagnostics
                .is_none()
        );

        config.collect_diagnostics = true;
        let plan = plan(&inputs(&fixture), &config).unwrap();
        let diagnostics = plan.diagnostics.as_ref().unwrap();
        let winner = diagnostics.selected().unwrap();

        assert!(!diagnostics.candidates.is_empty());
        assert!((winner.candidate.outgoing_start - plan.outgoing_start).abs() < 1e-9);
        let best = diagnostics.ranked()[0].score.total;
        assert!((winner.score.total - best).abs() < 1e-6);
    }

    #[test]
    fn a_track_with_no_room_to_transition_is_reported() {
        let config = EngineConfig::default();
        let short = track(120.0, 1.0, 440.0);
        let fixture = fixture(120.0, 120.0, &config);
        let mut inputs = inputs(&fixture);
        inputs.outgoing = &short;

        let error = plan(&inputs, &config).unwrap_err();
        assert!(matches!(error, CrossfadeError::NoViableTransition(_)));
    }

    #[test]
    fn preserving_pitch_is_the_default_and_can_be_turned_off() {
        let mut config = EngineConfig::default();
        let fixture = fixture(120.0, 124.0, &config);
        assert_eq!(
            plan(&inputs(&fixture), &config)
                .unwrap()
                .outgoing_pitch_semitones,
            0.0
        );

        config.tempo.preserve_pitch = false;
        let plan = plan(&inputs(&fixture), &config).unwrap();
        assert!(plan.outgoing_pitch_semitones > 0.0);
    }
}
