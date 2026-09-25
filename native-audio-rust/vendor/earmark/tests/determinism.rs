//! The planner must never wander. Same PCM, same beats, same config, same answer.

mod common;

use common::{SR, Voice, track};
use earmark::{EngineConfig, SmartCrossfadeEngine};

fn config(diagnostics: bool) -> EngineConfig {
    EngineConfig {
        collect_diagnostics: diagnostics,
        ..EngineConfig::default()
    }
}

#[test]
fn repeated_planning_on_one_engine_agrees() {
    let mut engine = SmartCrossfadeEngine::new(config(false)).unwrap();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(127.0, 60.0, Voice::bright(), 2, SR);

    let first = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    for _ in 0..3 {
        let again = engine
            .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
            .unwrap();
        assert_eq!(first, again);
    }
}

#[test]
fn separate_engines_reach_the_same_plan() {
    let (outgoing, outgoing_beats) = track(120.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(122.0, 60.0, Voice::club(), 2, SR);

    let plan_once = || {
        SmartCrossfadeEngine::new(config(false))
            .unwrap()
            .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
            .unwrap()
    };
    assert_eq!(plan_once(), plan_once());
}

#[test]
fn a_plan_renders_identically_on_a_fresh_engine() {
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(126.0, 60.0, Voice::club(), 2, SR);

    let plan = SmartCrossfadeEngine::new(config(false))
        .unwrap()
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    let render = || {
        SmartCrossfadeEngine::new(config(false))
            .unwrap()
            .render(&outgoing, &incoming, &plan)
            .unwrap()
    };
    assert_eq!(render(), render());
}

#[test]
fn a_reused_engine_renders_identically_to_a_fresh_one() {
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(126.0, 60.0, Voice::club(), 2, SR);

    let mut engine = SmartCrossfadeEngine::new(config(false)).unwrap();
    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();

    // Warm the reusable state with an unrelated render first.
    let _ = engine.render(&incoming, &outgoing, &plan).unwrap();
    let reused = engine.render(&outgoing, &incoming, &plan).unwrap();
    let fresh = SmartCrossfadeEngine::new(config(false))
        .unwrap()
        .render(&outgoing, &incoming, &plan)
        .unwrap();

    assert_eq!(reused, fresh);
}

#[test]
fn diagnostics_do_not_change_the_decision() {
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(125.0, 60.0, Voice::club(), 2, SR);

    let plan_with = |diagnostics| {
        SmartCrossfadeEngine::new(config(diagnostics))
            .unwrap()
            .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
            .unwrap()
    };

    let quiet = plan_with(false);
    let verbose = plan_with(true);
    assert!(quiet.diagnostics.is_none());
    assert!(verbose.diagnostics.is_some());
    assert_eq!(quiet.outgoing_start, verbose.outgoing_start);
    assert_eq!(quiet.incoming_start, verbose.incoming_start);
    assert_eq!(quiet.beats, verbose.beats);
    assert_eq!(quiet.strategy, verbose.strategy);
}

#[test]
fn the_selected_candidate_is_the_top_ranked_one() {
    let mut engine = SmartCrossfadeEngine::new(config(true)).unwrap();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(126.0, 60.0, Voice::club(), 2, SR);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    let diagnostics = plan.diagnostics.as_ref().unwrap();
    let selected = diagnostics.selected().unwrap();

    assert!(diagnostics.candidates.len() > 1);
    assert!(
        diagnostics
            .candidates
            .iter()
            .all(|c| c.score.total <= selected.score.total + 1e-6)
    );
    assert!((diagnostics.ranked()[0].score.total - selected.score.total).abs() < 1e-6);

    for candidate in &diagnostics.candidates {
        let score = &candidate.score;
        for component in [
            score.total,
            score.beat_alignment,
            score.phrase_alignment,
            score.tempo,
            score.spectral,
            score.loudness,
            score.energy,
            score.transient,
            score.low_freq,
            score.duration,
        ] {
            assert!(
                (0.0..=1.0).contains(&component),
                "out of range: {component}"
            );
        }
    }
}

#[test]
fn the_summary_explains_the_selection() {
    let mut engine = SmartCrossfadeEngine::new(config(true)).unwrap();
    let (outgoing, outgoing_beats) = track(124.0, 60.0, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(126.0, 60.0, Voice::club(), 2, SR);

    let plan = engine
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    let summary = plan.summary();

    assert!(summary.contains(&format!("{}-beat", plan.beats)));
    assert!(summary.contains(plan.strategy.describe()));
    assert!(summary.contains("Outgoing start"));
    assert!(summary.contains("phrase alignment"));
}
