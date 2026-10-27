/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! The render shape of a transition the shared planner already chose, in plain Rust that both
//! hosts reach through `orchard-adaptive-mix`.

mod curve;
mod plan;

pub use curve::depth_curve;
pub use plan::SelectedPlan;

use earmark::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};
use earmark::dsp::filters::{FilterAutomation, FilterKind};
use earmark::{FULL_SPAN, TransitionPlan, TransitionStrategy};

/// Longest overlap a host accepts; the worker refuses longer plans well before this.
pub const MAX_SECONDS: f64 = 90.0;

/// Corner where a muffle's bass changes hands.
const MUFFLE_SWAP_HZ: f32 = 180.0;
/// A muffle's low-pass leaves fully open, drops past the brightest highs within its first beat,
/// and settles where only the bass and the body of the mix remain.
const MUFFLE_OPEN_HZ: f32 = 18_000.0;
const MUFFLE_DIM_HZ: f32 = 6_000.0;
const MUFFLE_CLOSED_HZ: f32 = 300.0;
/// Two stacked sections make a fourth-order Butterworth low-pass, steep enough that the highs vanish.
const MUFFLE_Q: [f32; 2] = [0.541_2, 1.306_6];

/// Apply the shared native render-shape fields without making a new musical choice.
pub fn apply_selected_shape(plan: &mut TransitionPlan, selected: &SelectedPlan) {
    if selected.strategy == "muffle_swap" {
        muffle(plan, selected);
    } else if let Some(swap_fraction) = selected.bass_swap_fraction {
        let swap_at = (swap_fraction as f32).clamp(0.05, 0.95);
        // Off the middle, the low band gets its own fader so the bass still hands over level.
        let full = |span: Option<(f64, f64)>| span.is_none_or(|(start, end)| start <= 1e-6 && end >= 1.0 - 1e-6);
        let spans_full = full(selected.outgoing_fade_span) && full(selected.incoming_fade_span);
        if plan.strategy == TransitionStrategy::BassSwap && spans_full && (swap_at - 0.5).abs() > 1e-3 {
            plan.fade.bass_crossover = Some(swap_at);
        }
        let swap_start = (swap_at - 0.05).max(0.0);
        let swap_end = (swap_at + 0.05).min(1.0);
        let outgoing_cut = AutomationCurve::from_points(vec![
            AutomationPoint::new(0.0, 20.0, CurveShape::Linear),
            AutomationPoint::new(swap_start, 20.0, CurveShape::EqualPowerIn),
            AutomationPoint::new(swap_end, 200.0, CurveShape::Linear),
            AutomationPoint::new(1.0, 200.0, CurveShape::Linear),
        ]);
        // A filtered blend low-passes the outgoing side, which keeps its bass. Without a cut
        // at the swap both basslines play through the second half.
        // One bassline at a time. The subwoofer has been through enough.
        if plan.strategy == TransitionStrategy::FilteredBlend
            && !plan.filters.outgoing.iter().any(|filter| filter.kind == FilterKind::HighPass)
        {
            plan.filters.outgoing.push(FilterAutomation::new(
                FilterKind::HighPass,
                outgoing_cut.clone(),
                std::f32::consts::FRAC_1_SQRT_2,
            ));
        }
        for filter in &mut plan.filters.outgoing {
            if filter.kind == FilterKind::HighPass {
                filter.cutoff = outgoing_cut.clone();
            }
        }
        for filter in &mut plan.filters.incoming {
            if filter.kind == FilterKind::HighPass {
                filter.cutoff = AutomationCurve::from_points(vec![
                    AutomationPoint::new(0.0, 200.0, CurveShape::Linear),
                    AutomationPoint::new(swap_start, 200.0, CurveShape::EqualPowerOut),
                    AutomationPoint::new(swap_end, 20.0, CurveShape::Linear),
                    AutomationPoint::new(1.0, 20.0, CurveShape::Linear),
                ]);
            }
        }
    }

    if selected.filter_sweep == Some(0.0) {
        plan.filters
            .outgoing
            .retain(|f| f.kind != FilterKind::LowPass);
    }

    fade_spans(plan, selected);
}

fn fade_spans(plan: &mut TransitionPlan, selected: &SelectedPlan) {
    let span = |value: Option<(f64, f64)>| value.map_or(FULL_SPAN, |(start, end)| {
        let start = start.clamp(0.0, 1.0) as f32;
        (start, (end.clamp(0.0, 1.0) as f32).max(start))
    });
    plan.fade.outgoing_span = span(selected.outgoing_fade_span);
    plan.fade.incoming_span = span(selected.incoming_fade_span);
}

/// Filters of a muffle. The incoming fades in over `lead..swap` and the outgoing out over
/// `swap..exit`. A steep low-pass closes over the outgoing through `lead..swap` with the outgoing
/// at full level, and the bass changes hands on the swap like a bass swap.
fn muffle(plan: &mut TransitionPlan, selected: &SelectedPlan) {
    let (lead, swap) = selected.incoming_fade_span.map_or((0.0, 0.5), |(a, b)| (a as f32, b as f32));
    let exit = selected.outgoing_fade_span.map_or(1.0, |(_, b)| b as f32).max(swap);
    let open = swap + (exit - swap) * 0.1;
    let point = |at: f32, hz: f32| AutomationPoint::new(at.clamp(0.0, 1.0), hz, CurveShape::Logarithmic);
    let butterworth = std::f32::consts::FRAC_1_SQRT_2;
    let closing = AutomationCurve::from_points(vec![
        point(0.0, MUFFLE_OPEN_HZ), point(lead, MUFFLE_OPEN_HZ), point(lead + (swap - lead) * 0.125, MUFFLE_DIM_HZ),
        point(swap, MUFFLE_CLOSED_HZ), point(1.0, MUFFLE_CLOSED_HZ),
    ]);
    let cut = AutomationCurve::from_points(vec![
        point(0.0, 20.0), point(swap, 20.0), point(open, MUFFLE_SWAP_HZ), point(1.0, MUFFLE_SWAP_HZ),
    ]);
    plan.filters.outgoing = vec![FilterAutomation::new(FilterKind::HighPass, cut, butterworth)];
    plan.filters.outgoing.extend(MUFFLE_Q.map(|q| FilterAutomation::new(FilterKind::LowPass, closing.clone(), q)));
    plan.filters.incoming = vec![FilterAutomation::new(FilterKind::HighPass, AutomationCurve::from_points(vec![
        point(0.0, MUFFLE_SWAP_HZ), point(swap, MUFFLE_SWAP_HZ), point(open, 20.0), point(1.0, 20.0),
    ]), butterworth)];
}

#[cfg(test)]
mod tests {
    use super::*;
    use earmark::{AudioBuffer, EngineConfig, SmartCrossfadeEngine, TransitionOutput};

    fn silence(seconds: usize, sample_rate: u32) -> AudioBuffer {
        AudioBuffer::new(vec![vec![0.0; seconds * sample_rate as usize]; 2], sample_rate).unwrap()
    }

    fn selected(strategy: &str) -> SelectedPlan {
        SelectedPlan {
            outgoing_start: 4.0,
            incoming_start: 2.0,
            duration: 8.0,
            beats: 16,
            outgoing_bpm: 120.0,
            incoming_bpm: 120.0,
            target_bpm: 120.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: None,
            incoming_pitch_semitones: None,
            tempo_ramp: false,
            strategy: strategy.to_string(),
            handoff_fraction: None,
            bed_position: None,
            bass_swap_fraction: None,
            filter_sweep: None,
            outgoing_fade_span: None,
            incoming_fade_span: None,
        }
    }

    fn render(audio: &AudioBuffer, choice: &SelectedPlan) -> Result<(TransitionPlan, TransitionOutput), String> {
        let mut engine = SmartCrossfadeEngine::new(EngineConfig::default()).map_err(|e| e.to_string())?;
        let mut plan = engine.plan_selected(audio, audio, &choice.to_earmark()?).map_err(|e| e.to_string())?;
        apply_selected_shape(&mut plan, choice);
        let output = engine.render(audio, audio, &plan).map_err(|e| e.to_string())?;
        Ok((plan, output))
    }

    #[test]
    fn planned_render_returns_the_exact_requested_timing() {
        let (plan, output) = render(&silence(20, 8_000), &selected("beatmatched_crossfade")).unwrap();
        assert_eq!(plan.outgoing_start, 4.0);
        assert_eq!(plan.incoming_start, 2.0);
        assert_eq!(plan.duration, 8.0);
        assert_eq!(output.outgoing_resume, 12.0);
        assert_eq!(output.incoming_resume, 10.0);
    }

    #[test]
    fn a_selected_glide_resumes_each_side_after_its_own_beats() {
        let log_mean = 10.0 / (130.0f64 / 120.0).ln();
        let mut choice = selected("beatmatched_crossfade");
        choice.duration = 16.0 * 60.0 / log_mean;
        choice.incoming_bpm = 130.0;
        choice.target_bpm = log_mean;
        choice.outgoing_tempo_ratio = log_mean / 120.0;
        choice.incoming_tempo_ratio = log_mean / 130.0;
        choice.tempo_ramp = true;
        let (_, output) = render(&silence(20, 8_000), &choice).expect("an 8% glide should render");
        assert!((output.outgoing_resume - 12.0).abs() < 1e-4);
        assert!((output.incoming_resume - (2.0 + 16.0 * 60.0 / 130.0)).abs() < 1e-4);
    }

    #[test]
    fn a_filtered_blend_hands_its_bass_over_at_the_swap() {
        let mut choice = selected("filtered_blend");
        choice.bass_swap_fraction = Some(0.5);
        let (plan, _) = render(&silence(20, 8_000), &choice).unwrap();
        let cut = plan.filters.outgoing.iter()
            .find(|filter| filter.kind == FilterKind::HighPass)
            .expect("the outgoing bass leaves at the swap");
        assert_eq!(cut.cutoff.value_at(0.4), 20.0);
        assert_eq!(cut.cutoff.value_at(0.6), 200.0);
        let open = plan.filters.incoming.iter()
            .find(|filter| filter.kind == FilterKind::HighPass)
            .unwrap();
        assert_eq!(open.cutoff.value_at(0.4), 200.0);
        assert_eq!(open.cutoff.value_at(0.6), 20.0);
        // The low-pass ride stays alongside the cut.
        assert!(plan.filters.outgoing.iter().any(|filter| filter.kind == FilterKind::LowPass));
    }

    #[test]
    fn a_muffle_closes_a_steep_low_pass_before_the_swap() {
        let mut choice = selected("muffle_swap");
        choice.bass_swap_fraction = Some(0.5);
        choice.incoming_fade_span = Some((0.0, 0.5));
        choice.outgoing_fade_span = Some((0.5, 0.625));
        let (plan, _) = render(&silence(20, 8_000), &choice).unwrap();
        let lows: Vec<_> = plan.filters.outgoing.iter().filter(|f| f.kind == FilterKind::LowPass).collect();
        assert_eq!(lows.len(), 2);
        for low in lows {
            assert_eq!(low.cutoff.value_at(0.0), MUFFLE_OPEN_HZ);
            assert!((low.cutoff.value_at(0.5) - MUFFLE_CLOSED_HZ).abs() < 1e-2);
        }
        let cut = plan.filters.outgoing.iter().find(|f| f.kind == FilterKind::HighPass).unwrap();
        assert_eq!(cut.cutoff.value_at(0.45), 20.0);
        assert!((cut.cutoff.value_at(0.6) - MUFFLE_SWAP_HZ).abs() < 1e-3);
        let open = plan.filters.incoming.iter().find(|f| f.kind == FilterKind::HighPass).unwrap();
        assert_eq!(open.cutoff.value_at(0.7), 20.0);
    }

    #[test]
    fn an_off_middle_bass_swap_gives_the_low_band_its_own_fader() {
        let mut choice = selected("bass_swap");
        // The planner always sends spans; full ones still count as full.
        choice.outgoing_fade_span = Some((0.0, 1.0));
        choice.incoming_fade_span = Some((0.0, 1.0));
        choice.bass_swap_fraction = Some(0.75);
        let (plan, _) = render(&silence(20, 8_000), &choice).unwrap();
        assert_eq!(plan.fade.bass_crossover, Some(0.75));
        choice.bass_swap_fraction = Some(0.5);
        let (plan, _) = render(&silence(20, 8_000), &choice).unwrap();
        assert_eq!(plan.fade.bass_crossover, None);
    }

    #[test]
    fn fade_spans_reach_the_render() {
        let rate = 8_000;
        let tone = |level: f32| AudioBuffer::new(vec![vec![level; 20 * rate as usize]; 2], rate).unwrap();
        let mut choice = selected("equal_power_crossfade");
        choice.outgoing_fade_span = Some((0.0, 0.5));
        choice.incoming_fade_span = Some((0.25, 1.0));
        let audio = tone(0.25);
        let (plan, output) = render(&audio, &choice).unwrap();
        assert_eq!(plan.fade.outgoing_span, (0.0, 0.5));
        assert_eq!(plan.fade.incoming_span, (0.25, 1.0));
        let at = |fraction: f64| output.audio.channel(0)[(fraction * 8.0 * rate as f64) as usize];
        // Only the outgoing plays at the start; only the incoming once the outgoing span ends.
        assert!((at(0.01) - 0.25).abs() < 0.01);
        let incoming = (0.65f32 / 0.75 * std::f32::consts::FRAC_PI_2).sin();
        assert!((at(0.9) - 0.25 * incoming).abs() < 0.02);
        // Unset spans fade across the whole overlap.
        let (plain, _) = render(&audio, &selected("equal_power_crossfade")).unwrap();
        assert_eq!(plain.fade.outgoing_span, FULL_SPAN);
    }

    #[test]
    fn planned_render_refusal_keeps_the_earmark_reason() {
        let mut choice = selected("beatmatched_crossfade");
        choice.outgoing_tempo_ratio = 1.05;
        let reason = render(&silence(20, 8_000), &choice).err().expect("excessive stretch rendered");
        assert_eq!(
            reason,
            "invalid selected transition: tempo ratio deviation 0.0500 exceeds the transparent limit 0.0400"
        );
    }
}
