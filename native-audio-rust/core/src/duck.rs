/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

//! Handing the caller's measurement to the outgoing low-pass ride.
//!
//! Only the low-pass. That filter is the one whose job is to get the outgoing track out of the
//! way, so how far it travels is exactly what a presence measurement should govern. A bass swap's
//! high-pass is a structural hand-over -- it is what stops two kick drums sharing the bottom
//! octave -- and scaling *it* by the same curve would let a quiet moment hold the outgoing low end
//! in place under a track that has already taken over. A strategy that rides nothing at all leaves
//! the curve unused rather than forcing a filter that was not planned.

use earmark::TransitionPlan;
use earmark::dsp::filters::FilterKind;

use crate::curve::depth_curve;

/// Applies a curve measured across the whole outgoing slice, cropped to the region the planner
/// settled on.
///
/// The measurement spans the slice rather than the transition because the caller has to measure
/// before it asks, and what the transition *is* is what the ask decides.
pub fn apply_across_slice(plan: &mut TransitionPlan, points: Option<&[f64]>, source_duration: f64) {
    let Some(points) = points else {
        return;
    };
    if source_duration <= 0.0 {
        return;
    }
    let from = plan.outgoing_start / source_duration;
    let to = plan.outgoing_end() / source_duration;
    apply(plan, points, from, to);
}

/// Applies a curve already measured across the selected overlap, which needs no cropping.
pub fn apply_across_overlap(plan: &mut TransitionPlan, points: Option<&[f64]>) {
    let Some(points) = points else {
        return;
    };
    apply(plan, points, 0.0, 1.0);
}

fn apply(plan: &mut TransitionPlan, points: &[f64], from: f64, to: f64) {
    let rides = plan
        .filters
        .outgoing
        .iter()
        .any(|filter| filter.kind == FilterKind::LowPass);
    if !rides {
        return;
    }
    let Some(curve) = depth_curve(points, from, to) else {
        return;
    };
    for filter in &mut plan.filters.outgoing {
        if filter.kind == FilterKind::LowPass {
            filter.depth = Some(curve.clone());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use earmark::TransitionStrategy;
    use earmark::dsp::automation::{AutomationCurve, CurveShape};
    use earmark::dsp::fade::FadeCurve;
    use earmark::dsp::filters::FilterAutomation;
    use earmark::types::transition::{FadePlan, FilterPlan};

    fn plan_with(filters: FilterPlan, strategy: TransitionStrategy) -> TransitionPlan {
        TransitionPlan {
            outgoing_start: 2.0,
            incoming_start: 0.0,
            duration: 4.0,
            beats: 16,
            sample_rate: 44_100,
            channels: 2,
            outgoing_bpm: 120.0,
            incoming_bpm: 120.0,
            target_bpm: 120.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: 0.0,
            incoming_pitch_semitones: 0.0,
            outgoing_gain_db: 0.0,
            incoming_gain_db: 0.0,
            strategy,
            fade: FadePlan {
                outgoing_curve: FadeCurve::EqualPower,
                incoming_curve: FadeCurve::EqualPower,
                outgoing_gain: AutomationCurve::constant(1.0),
                incoming_gain: AutomationCurve::constant(1.0),
            },
            filters,
            diagnostics: None,
        }
    }

    fn automation(kind: FilterKind) -> FilterAutomation {
        FilterAutomation::new(
            kind,
            AutomationCurve::ramp(18_000.0, 200.0, CurveShape::Logistic),
            std::f32::consts::FRAC_1_SQRT_2,
        )
    }

    #[test]
    fn the_duck_curve_scales_the_outgoing_low_pass_ride() {
        let plan = &mut plan_with(
            FilterPlan {
                outgoing: vec![automation(FilterKind::LowPass)],
                incoming: Vec::new(),
            },
            TransitionStrategy::FilteredBlend,
        );
        apply_across_slice(plan, Some(&[0.0, 0.5, 1.0]), 10.0);
        assert!(plan.filters.outgoing[0].depth.is_some());
    }

    /// A bass swap's high-pass is a structural hand-over, not a ride. Scaling it by a presence
    /// measurement would let a quiet moment hold the outgoing low end under a track that has
    /// already taken over -- audible as mud, and silent in every test that only checks the render
    /// succeeded.
    #[test]
    fn the_duck_curve_never_touches_a_bass_swap() {
        let plan = &mut plan_with(
            FilterPlan {
                outgoing: vec![automation(FilterKind::HighPass)],
                incoming: vec![automation(FilterKind::HighPass)],
            },
            TransitionStrategy::BassSwap,
        );
        apply_across_slice(plan, Some(&[0.0, 0.0, 0.0]), 10.0);
        assert!(plan.filters.outgoing[0].depth.is_none());
        assert!(plan.filters.incoming[0].depth.is_none());
    }

    /// The same rule on the exact-plan path, which crops nothing and so reaches `apply` by a
    /// different route.
    #[test]
    fn an_overlap_curve_never_touches_a_bass_swap_either() {
        let plan = &mut plan_with(
            FilterPlan {
                outgoing: vec![automation(FilterKind::HighPass)],
                incoming: vec![automation(FilterKind::HighPass)],
            },
            TransitionStrategy::BassSwap,
        );
        apply_across_overlap(plan, Some(&[0.0, 0.0, 0.0]));
        assert!(plan.filters.outgoing[0].depth.is_none());
        assert!(plan.filters.incoming[0].depth.is_none());
    }

    #[test]
    fn a_strategy_that_rides_nothing_is_left_alone() {
        let plan = &mut plan_with(
            FilterPlan::default(),
            TransitionStrategy::EqualPowerCrossfade,
        );
        apply_across_slice(plan, Some(&[0.5; 8]), 10.0);
        assert!(plan.filters.is_empty());
    }

    #[test]
    fn no_curve_leaves_the_ride_at_full_depth() {
        let plan = &mut plan_with(
            FilterPlan {
                outgoing: vec![automation(FilterKind::LowPass)],
                incoming: Vec::new(),
            },
            TransitionStrategy::FilteredBlend,
        );
        apply_across_slice(plan, None, 10.0);
        assert!(plan.filters.outgoing[0].depth.is_none());
    }

    #[test]
    fn the_curve_is_cropped_to_the_region_the_planner_chose() {
        let plan = &mut plan_with(
            FilterPlan {
                outgoing: vec![automation(FilterKind::LowPass)],
                incoming: Vec::new(),
            },
            TransitionStrategy::FilteredBlend,
        );
        // A ramp over a 10s slice, with the transition covering 2s..6s, is the 0.2..0.6 slab.
        let points: Vec<f64> = (0..=100).map(|i| i as f64 / 100.0).collect();
        apply_across_slice(plan, Some(&points), 10.0);

        let depth = plan.filters.outgoing[0].depth.as_ref().unwrap();
        assert!(
            (depth.value_at(0.0) - 0.2).abs() < 1e-2,
            "{}",
            depth.value_at(0.0)
        );
        assert!(
            (depth.value_at(1.0) - 0.6).abs() < 1e-2,
            "{}",
            depth.value_at(1.0)
        );
    }
}
