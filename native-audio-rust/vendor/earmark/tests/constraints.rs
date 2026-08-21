//! Planning inside a region the caller nominates.

mod common;

use common::{SR, Voice, track};
use earmark::{
    CrossfadeError, EngineConfig, RegionConstraint, SmartCrossfadeEngine, TimeWindow,
    TransitionConstraints,
};

const OUTGOING_SECONDS: f64 = 180.0;
const INCOMING_SECONDS: f64 = 120.0;

fn engine() -> SmartCrossfadeEngine {
    SmartCrossfadeEngine::new(EngineConfig::default()).unwrap()
}

struct Pair {
    outgoing: earmark::AudioBuffer,
    incoming: earmark::AudioBuffer,
    outgoing_beats: earmark::BeatAnalysis,
    incoming_beats: earmark::BeatAnalysis,
}

fn pair() -> Pair {
    let (outgoing, outgoing_beats) = track(120.0, OUTGOING_SECONDS, Voice::club(), 2, SR);
    let (incoming, incoming_beats) = track(122.0, INCOMING_SECONDS, Voice::club(), 2, SR);
    Pair {
        outgoing,
        incoming,
        outgoing_beats,
        incoming_beats,
    }
}

/// The downbeat nearest `target`.
///
/// Transition lengths are whole bars, so every reachable end sits on the downbeat lattice. A
/// caller whose cue comes from elsewhere has to snap it to the grid (or allow a window at least
/// half a bar wide), or the planner correctly reports that nothing fits.
fn snap(beats: &earmark::BeatAnalysis, target: f64) -> f64 {
    beats
        .downbeats
        .iter()
        .copied()
        .min_by(|a, b| (a - target).abs().total_cmp(&(b - target).abs()))
        .expect("the fixture grid has downbeats")
}

fn plan_with(pair: &Pair, constraints: &TransitionConstraints) -> earmark::TransitionPlan {
    engine()
        .analyze_constrained(
            &pair.outgoing,
            &pair.incoming,
            &pair.outgoing_beats,
            &pair.incoming_beats,
            constraints,
        )
        .unwrap()
}

#[test]
fn an_outgoing_end_constraint_lands_the_mix_out_where_the_caller_asked() {
    let pair = pair();
    let mix_out = snap(&pair.outgoing_beats, 150.0);
    let constraints = TransitionConstraints {
        outgoing: RegionConstraint::ending_within(TimeWindow::around(mix_out, 0.05)),
        ..TransitionConstraints::NONE
    };

    let plan = plan_with(&pair, &constraints);
    assert!(
        (plan.outgoing_end() - mix_out).abs() <= 0.05,
        "outgoing_end {} is outside the nominated window around {mix_out}",
        plan.outgoing_end()
    );
}

/// The invariant a DJ-style host cares about: the incoming track's drop is where the mix hands
/// over, so the transition has to *end* there rather than start there.
#[test]
fn an_incoming_end_constraint_makes_the_transition_finish_on_the_drop() {
    let pair = pair();
    let drop = snap(&pair.incoming_beats, 32.0);
    let constraints = TransitionConstraints {
        incoming: RegionConstraint::ending_within(TimeWindow::around(drop, 0.05)),
        ..TransitionConstraints::NONE
    };

    let plan = plan_with(&pair, &constraints);
    assert!(
        (plan.incoming_end() - drop).abs() <= 0.05,
        "incoming_end {} missed the drop at {drop}",
        plan.incoming_end()
    );
    assert!(
        plan.incoming_start < drop,
        "the transition should begin before the drop, not on it"
    );
}

#[test]
fn both_ends_can_be_pinned_at_once() {
    let pair = pair();
    let mix_out = snap(&pair.outgoing_beats, 150.0);
    let drop = snap(&pair.incoming_beats, 32.0);
    let constraints = TransitionConstraints {
        outgoing: RegionConstraint::ending_within(TimeWindow::around(mix_out, 0.05)),
        incoming: RegionConstraint::ending_within(TimeWindow::around(drop, 0.05)),
        ..TransitionConstraints::NONE
    };

    let plan = plan_with(&pair, &constraints);
    assert!((plan.outgoing_end() - mix_out).abs() <= 0.05);
    assert!((plan.incoming_end() - drop).abs() <= 0.05);
}

/// Every reachable end is a whole number of bars from a downbeat, so a window narrower than that
/// lattice can legitimately contain nothing. The planner says so instead of quietly drifting to
/// the closest thing it could find.
#[test]
fn a_window_that_falls_between_downbeats_is_refused_not_approximated() {
    let pair = pair();
    let between = snap(&pair.incoming_beats, 32.0) + 0.9;
    let constraints = TransitionConstraints {
        incoming: RegionConstraint::ending_within(TimeWindow::around(between, 0.1)),
        ..TransitionConstraints::NONE
    };

    let refused = engine().analyze_constrained(
        &pair.outgoing,
        &pair.incoming,
        &pair.outgoing_beats,
        &pair.incoming_beats,
        &constraints,
    );
    assert!(matches!(
        refused,
        Err(CrossfadeError::NoViableTransition(_))
    ));

    // Widening past half a bar brings the lattice back into reach.
    let forgiving = TransitionConstraints {
        incoming: RegionConstraint::ending_within(TimeWindow::around(between, 1.0)),
        ..TransitionConstraints::NONE
    };
    assert!((plan_with(&pair, &forgiving).incoming_end() - between).abs() <= 1.0);
}

/// A cue earlier than `outgoing_search_window` must still be honoured — the constraint replaces
/// the default window rather than being clipped back into it.
#[test]
fn a_cue_outside_the_default_search_window_is_still_honoured() {
    let pair = pair();
    let config = EngineConfig::default();
    let mix_out = 60.0;
    assert!(
        mix_out < OUTGOING_SECONDS - config.timing.outgoing_search_window,
        "this test is only meaningful for a cue outside the default window"
    );

    let constraints = TransitionConstraints {
        outgoing: RegionConstraint::ending_within(TimeWindow::around(mix_out, 0.5)),
        ..TransitionConstraints::NONE
    };
    let plan = plan_with(&pair, &constraints);
    assert!((plan.outgoing_end() - mix_out).abs() <= 0.5);
}

#[test]
fn a_start_window_bounds_where_the_transition_begins() {
    let pair = pair();
    let window = TimeWindow::new(20.0, 30.0);
    let constraints = TransitionConstraints {
        incoming: RegionConstraint::starting_within(window),
        ..TransitionConstraints::NONE
    };

    let plan = plan_with(&pair, &constraints);
    assert!(
        window.contains(plan.incoming_start),
        "incoming_start {} escaped {:?}",
        plan.incoming_start,
        window
    );
}

#[test]
fn restricting_beat_lengths_pins_the_transition_length() {
    let pair = pair();
    let constraints = TransitionConstraints {
        beat_lengths: Some(vec![32]),
        ..TransitionConstraints::NONE
    };

    let plan = plan_with(&pair, &constraints);
    assert_eq!(plan.beats, 32);
}

#[test]
fn constraints_narrow_the_pool_without_changing_how_it_is_scored() {
    let pair = pair();
    let config = EngineConfig {
        collect_diagnostics: true,
        ..EngineConfig::default()
    };
    let constraints = TransitionConstraints {
        outgoing: RegionConstraint::ending_within(TimeWindow::around(150.0, 2.0)),
        ..TransitionConstraints::NONE
    };

    let mut engine = SmartCrossfadeEngine::new(config).unwrap();
    let constrained = engine
        .analyze_constrained(
            &pair.outgoing,
            &pair.incoming,
            &pair.outgoing_beats,
            &pair.incoming_beats,
            &constraints,
        )
        .unwrap();
    let free = engine
        .analyze(
            &pair.outgoing,
            &pair.incoming,
            &pair.outgoing_beats,
            &pair.incoming_beats,
        )
        .unwrap();

    let pool = |plan: &earmark::TransitionPlan| plan.diagnostics.as_ref().unwrap().candidates.len();
    assert!(
        pool(&constrained) < pool(&free),
        "constrained pool {} should be smaller than the free pool {}",
        pool(&constrained),
        pool(&free)
    );
    // Every surviving candidate is one the unconstrained run would also have considered valid.
    for scored in &constrained.diagnostics.as_ref().unwrap().candidates {
        assert!(scored.candidate.outgoing_end() <= pair.outgoing.duration());
        assert!(scored.candidate.incoming_end() <= pair.incoming.duration());
    }
}

#[test]
fn a_constrained_plan_renders_and_stays_deterministic() {
    let pair = pair();
    let constraints = TransitionConstraints {
        outgoing: RegionConstraint::ending_within(TimeWindow::around(
            snap(&pair.outgoing_beats, 150.0),
            0.05,
        )),
        incoming: RegionConstraint::ending_within(TimeWindow::around(
            snap(&pair.incoming_beats, 32.0),
            0.05,
        )),
        ..TransitionConstraints::NONE
    };

    let first = plan_with(&pair, &constraints);
    let second = plan_with(&pair, &constraints);
    assert_eq!(first, second);

    let output = engine()
        .render(&pair.outgoing, &pair.incoming, &first)
        .unwrap();
    assert_eq!(output.audio.frames(), first.frames());
    assert!(output.audio.peak() > 0.05, "the render came out silent");
}

#[test]
fn an_impossible_window_is_reported_rather_than_ignored() {
    let pair = pair();
    // Half a second is shorter than min_duration, so no transition can both start and end inside.
    let constraints = TransitionConstraints {
        outgoing: RegionConstraint {
            start_within: Some(TimeWindow::new(100.0, 100.5)),
            end_within: Some(TimeWindow::new(100.0, 100.5)),
        },
        ..TransitionConstraints::NONE
    };

    let error = engine()
        .analyze_constrained(
            &pair.outgoing,
            &pair.incoming,
            &pair.outgoing_beats,
            &pair.incoming_beats,
            &constraints,
        )
        .unwrap_err();

    match error {
        CrossfadeError::NoViableTransition(message) => {
            assert!(
                message.contains("outgoing start in") && message.contains("outgoing end in"),
                "the error should name the constraints that emptied the pool: {message}"
            );
        }
        other => panic!("expected NoViableTransition, got {other:?}"),
    }
}

#[test]
fn a_malformed_constraint_is_rejected_before_any_analysis() {
    let pair = pair();
    let constraints = TransitionConstraints {
        outgoing: RegionConstraint::ending_within(TimeWindow::new(150.0, 140.0)),
        ..TransitionConstraints::NONE
    };

    let error = engine()
        .analyze_constrained(
            &pair.outgoing,
            &pair.incoming,
            &pair.outgoing_beats,
            &pair.incoming_beats,
            &constraints,
        )
        .unwrap_err();
    assert!(matches!(error, CrossfadeError::UnsupportedConfiguration(_)));
}

#[test]
fn an_unconstrained_call_matches_the_plain_analyze_path() {
    let pair = pair();
    let constrained = plan_with(&pair, &TransitionConstraints::NONE);
    let plain = engine()
        .analyze(
            &pair.outgoing,
            &pair.incoming,
            &pair.outgoing_beats,
            &pair.incoming_beats,
        )
        .unwrap();
    assert_eq!(constrained, plain);
}
