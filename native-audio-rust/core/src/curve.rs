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

//! Turning the caller's measurement of its own PCM into a curve the engine can ride.

use earmark::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};

/// Turns evenly spaced control points spanning the supplied PCM into a curve spanning the
/// *planned transition*.
///
/// The caller measures its PCM before the engine has chosen anything -- the choice is what it is
/// asking for -- so the measurement covers the whole slice and is cropped to the chosen region
/// here. `from` and `to` are that region as fractions of the slice.
///
/// Sampling is at the same density as the input over the retained span, so a curve cropped to a
/// third of the slice keeps a third of its detail rather than being reduced to its endpoints.
pub fn depth_curve(points: &[f64], from: f64, to: f64) -> Option<AutomationCurve> {
    if points.is_empty() {
        return None;
    }
    if points.len() == 1 {
        return Some(AutomationCurve::constant(finite_depth(points[0])));
    }

    let (from, to) = (from.clamp(0.0, 1.0), to.clamp(0.0, 1.0));
    let span = to - from;
    // A degenerate or unmeasurable region has no span to vary over, so the depth holds at the
    // value the region starts on. `is_finite` is what rejects a NaN region; a bare comparison
    // would quietly take the sampling path with a NaN width.
    if !span.is_finite() || span <= 0.0 {
        return Some(AutomationCurve::constant(finite_depth(sample(
            points, from,
        ))));
    }

    let retained = ((points.len() as f64 * span).ceil() as usize).clamp(2, points.len());
    let span = (retained - 1) as f64;
    Some(AutomationCurve::from_points(
        (0..retained)
            .map(|index| {
                let position = index as f64 / span;
                AutomationPoint::new(
                    position as f32,
                    finite_depth(sample(points, from + position * (to - from))),
                    CurveShape::Linear,
                )
            })
            .collect(),
    ))
}

/// A depth in `0..=1`. Anything unmeasurable reads as "no ducking", which leaves the ride at the
/// depth the planner asked for -- a NaN would otherwise reach the biquad and silence the channel.
fn finite_depth(value: f64) -> f32 {
    if value.is_finite() {
        value.clamp(0.0, 1.0) as f32
    } else {
        1.0
    }
}

/// Reads `points` at a fraction of its span, interpolating between neighbours.
fn sample(points: &[f64], fraction: f64) -> f64 {
    let last = points.len() - 1;
    if !fraction.is_finite() {
        return points[0];
    }
    let exact = fraction.clamp(0.0, 1.0) * last as f64;
    let index = exact.floor() as usize;
    if index >= last {
        return points[last];
    }
    let blend = exact - index as f64;
    points[index] + (points[index + 1] - points[index]) * blend
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_empty_curve_is_no_opinion() {
        assert!(depth_curve(&[], 0.0, 1.0).is_none());
    }

    #[test]
    fn a_single_point_is_a_constant() {
        let curve = depth_curve(&[0.4], 0.0, 1.0).unwrap();
        assert!(curve.is_constant());
        assert!((curve.value_at(0.0) - 0.4).abs() < 1e-6);
        assert!((curve.value_at(1.0) - 0.4).abs() < 1e-6);
    }

    #[test]
    fn an_uncropped_curve_spans_the_transition_exactly() {
        let curve = depth_curve(&[0.0, 0.5, 1.0], 0.0, 1.0).unwrap();
        assert!((curve.value_at(0.0) - 0.0).abs() < 1e-6);
        assert!((curve.value_at(0.5) - 0.5).abs() < 1e-6);
        assert!((curve.value_at(1.0) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn cropping_rescales_the_retained_span_to_the_whole_transition() {
        // A ramp over the slice, cropped to its second half, is a ramp from 0.5 to 1.0.
        let points: Vec<f64> = (0..=100).map(|i| i as f64 / 100.0).collect();
        let curve = depth_curve(&points, 0.5, 1.0).unwrap();
        assert!((curve.value_at(0.0) - 0.5).abs() < 1e-3);
        assert!((curve.value_at(0.5) - 0.75).abs() < 1e-3);
        assert!((curve.value_at(1.0) - 1.0).abs() < 1e-3);
    }

    #[test]
    fn cropping_keeps_detail_in_proportion() {
        let points: Vec<f64> = (0..=100).map(|i| i as f64 / 100.0).collect();
        let whole = depth_curve(&points, 0.0, 1.0).unwrap();
        let quarter = depth_curve(&points, 0.0, 0.25).unwrap();
        assert!(quarter.points().len() < whole.points().len());
        assert!(quarter.points().len() >= 2);
    }

    #[test]
    fn a_degenerate_region_holds_its_starting_value() {
        let curve = depth_curve(&[0.0, 1.0], 0.5, 0.5).unwrap();
        assert!(curve.is_constant());
        assert!((curve.value_at(0.0) - 0.5).abs() < 1e-6);
    }

    #[test]
    fn a_nan_region_does_not_reach_the_sampling_path() {
        let curve = depth_curve(&[0.0, 1.0], f64::NAN, 1.0).unwrap();
        assert!(curve.is_constant());
        assert!(curve.value_at(0.0).is_finite());
    }

    #[test]
    fn non_finite_points_never_reach_the_filter() {
        let curve = depth_curve(&[f64::NAN, 0.5, f64::INFINITY], 0.0, 1.0).unwrap();
        for step in 0..=10 {
            let value = curve.value_at(step as f32 / 10.0);
            assert!(value.is_finite(), "depth was {value} at step {step}");
            assert!((0.0..=1.0).contains(&value));
        }
    }

    #[test]
    fn out_of_range_points_are_clamped() {
        let curve = depth_curve(&[-3.0, 9.0], 0.0, 1.0).unwrap();
        assert!((curve.value_at(0.0) - 0.0).abs() < 1e-6);
        assert!((curve.value_at(1.0) - 1.0).abs() < 1e-6);
    }
}
