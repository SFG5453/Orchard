//! The outgoing filter ride, and the depth curve a host can lay over it.

mod common;

use common::{SR, Voice, high_band_energy, track};
use earmark::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};
use earmark::{
    AudioBuffer, BeatAnalysis, EngineConfig, SmartCrossfadeEngine, TransitionPlan,
    TransitionStrategy,
};

/// Deliberately clashing material, which is what pushes the planner onto a filtered blend.
///
/// The bright track is the *outgoing* one on purpose: the ride being measured is on that side, so
/// it has to be the side carrying the top end. With the voices the other way round the incoming
/// track's own 6 kHz tone dominates the mix and every depth measures the same.
fn clashing() -> (AudioBuffer, AudioBuffer, BeatAnalysis, BeatAnalysis) {
    let (outgoing, outgoing_beats) = track(120.0, 90.0, Voice::bright(), 2, SR);
    let (incoming, incoming_beats) = track(121.0, 90.0, Voice::club(), 2, SR);
    (outgoing, incoming, outgoing_beats, incoming_beats)
}

fn engine() -> SmartCrossfadeEngine {
    SmartCrossfadeEngine::new(EngineConfig::default()).unwrap()
}

fn filtered_blend_plan() -> (AudioBuffer, AudioBuffer, TransitionPlan) {
    let (outgoing, incoming, outgoing_beats, incoming_beats) = clashing();
    let plan = engine()
        .analyze(&outgoing, &incoming, &outgoing_beats, &incoming_beats)
        .unwrap();
    assert_eq!(
        plan.strategy,
        TransitionStrategy::FilteredBlend,
        "this fixture is meant to produce a filtered blend"
    );
    (outgoing, incoming, plan)
}

/// Renders `plan` after giving its outgoing filter the supplied depth curve.
fn render_with_depth(
    outgoing: &AudioBuffer,
    incoming: &AudioBuffer,
    plan: &TransitionPlan,
    depth: Option<AutomationCurve>,
) -> AudioBuffer {
    let mut plan = plan.clone();
    if let Some(curve) = depth {
        plan.filters.outgoing[0].depth = Some(curve);
    }
    engine().render(outgoing, incoming, &plan).unwrap().audio
}

fn top_end(audio: &AudioBuffer) -> f32 {
    high_band_energy(audio.channel(0), 2_000.0, audio.sample_rate())
}

#[test]
fn a_filtered_blend_rides_the_outgoing_low_pass_down() {
    let (outgoing, incoming, plan) = filtered_blend_plan();
    let sweep = &plan.filters.outgoing[0];

    assert!(
        sweep.cutoff.value_at(0.0) > 10_000.0,
        "the ride has to start above hearing, not at {}",
        sweep.cutoff.value_at(0.0)
    );
    assert!(
        sweep.cutoff.value_at(1.0) < 1_000.0,
        "the ride has to close down, not stop at {}",
        sweep.cutoff.value_at(1.0)
    );
    assert!(
        sweep.cutoff.value_at(0.15) > sweep.cutoff.value_at(0.85),
        "the corner should fall over the transition"
    );

    let rendered = engine().render(&outgoing, &incoming, &plan).unwrap().audio;
    assert!(rendered.peak() > 0.05, "the blend rendered silent");
}

#[test]
fn zero_depth_holds_the_filter_open() {
    let (outgoing, incoming, plan) = filtered_blend_plan();
    let full = render_with_depth(&outgoing, &incoming, &plan, None);
    let none = render_with_depth(
        &outgoing,
        &incoming,
        &plan,
        Some(AutomationCurve::constant(0.0)),
    );

    assert!(
        top_end(&none) > top_end(&full),
        "depth 0 should keep more top end than a full ride: {} vs {}",
        top_end(&none),
        top_end(&full)
    );
}

#[test]
fn depth_moves_the_ride_monotonically() {
    let (outgoing, incoming, plan) = filtered_blend_plan();
    let at = |depth: f32| {
        top_end(&render_with_depth(
            &outgoing,
            &incoming,
            &plan,
            Some(AutomationCurve::constant(depth)),
        ))
    };
    let (open, half, closed) = (at(0.0), at(0.5), at(1.0));
    assert!(
        closed < half && half < open,
        "expected closed {closed} < half {half} < open {open}"
    );
}

#[test]
fn a_depth_curve_that_collapses_does_not_reopen_the_filter() {
    let (outgoing, incoming, plan) = filtered_blend_plan();
    let dipping = AutomationCurve::from_points(vec![
        AutomationPoint::new(0.0, 1.0, CurveShape::Linear),
        AutomationPoint::new(0.5, 1.0, CurveShape::Linear),
        AutomationPoint::new(0.51, 0.0, CurveShape::Linear),
        AutomationPoint::new(1.0, 0.0, CurveShape::Linear),
    ]);

    let dipped = render_with_depth(&outgoing, &incoming, &plan, Some(dipping));
    let held = render_with_depth(
        &outgoing,
        &incoming,
        &plan,
        Some(AutomationCurve::constant(1.0)),
    );
    assert_eq!(
        dipped, held,
        "the running maximum should have held the deepest point"
    );
}

#[test]
fn attaching_a_depth_curve_keeps_rendering_deterministic() {
    let (outgoing, incoming, plan) = filtered_blend_plan();
    let curve = AutomationCurve::ramp(0.2, 1.0, CurveShape::SmoothStep);
    let first = render_with_depth(&outgoing, &incoming, &plan, Some(curve.clone()));
    let second = render_with_depth(&outgoing, &incoming, &plan, Some(curve));
    assert_eq!(first, second);
}
