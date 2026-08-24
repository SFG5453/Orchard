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

//! Plain-data conversions between the N-API objects and earmark's types.
//!
//! Everything here runs on the JavaScript thread, before the render task is queued: a `Task` may
//! not touch JS values, so the PCM is copied out first.

use earmark::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};
use earmark::{
    RegionConstraint, SelectedTransition, TimeWindow, TransitionConstraints, TransitionStrategy,
};

use crate::{JsRegionConstraint, JsSelectedTransition, JsTransitionOptions};

/// Longest overlap worth accepting, as a guard against a caller passing whole tracks. The planner
/// never asks for more than `max_duration`, so anything approaching this is a bug upstream.
const MAX_SECONDS: f64 = 90.0;

pub fn constraints(options: &JsTransitionOptions) -> TransitionConstraints {
    TransitionConstraints {
        outgoing: region(options.outgoing.as_ref()),
        incoming: region(options.incoming.as_ref()),
        beat_lengths: options
            .beat_lengths
            .clone()
            .filter(|lengths| !lengths.is_empty()),
    }
}

/// Converts only the public, stable strategy vocabulary. The exact-render API
/// is a contract boundary, so aliases and fuzzy matching would hide caller
/// mistakes and could silently execute a different DSP shape.
pub fn selected_transition(value: &JsSelectedTransition) -> Result<SelectedTransition, String> {
    let strategy = match value.strategy.as_str() {
        "equal_power_crossfade" => TransitionStrategy::EqualPowerCrossfade,
        "beatmatched_crossfade" => TransitionStrategy::BeatmatchedCrossfade,
        "bass_swap" => TransitionStrategy::BassSwap,
        "filtered_blend" => TransitionStrategy::FilteredBlend,
        "short_fade" => TransitionStrategy::ShortFade,
        other => return Err(format!("unknown transition strategy '{other}'")),
    };
    Ok(SelectedTransition {
        outgoing_start: value.outgoing_start,
        incoming_start: value.incoming_start,
        duration: value.duration,
        beats: value.beats,
        outgoing_bpm: value.outgoing_bpm as f32,
        incoming_bpm: value.incoming_bpm as f32,
        target_bpm: value.target_bpm as f32,
        outgoing_tempo_ratio: value.outgoing_tempo_ratio as f32,
        incoming_tempo_ratio: value.incoming_tempo_ratio as f32,
        outgoing_pitch_semitones: value.outgoing_pitch_semitones.unwrap_or(0.0) as f32,
        incoming_pitch_semitones: value.incoming_pitch_semitones.unwrap_or(0.0) as f32,
        strategy,
    })
}

fn region(source: Option<&JsRegionConstraint>) -> RegionConstraint {
    let Some(source) = source else {
        return RegionConstraint::NONE;
    };
    RegionConstraint {
        start_within: source
            .start_earliest
            .zip(source.start_latest)
            .map(|(a, b)| TimeWindow::new(a, b)),
        end_within: source
            .end_earliest
            .zip(source.end_latest)
            .map(|(a, b)| TimeWindow::new(a, b)),
    }
}

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

/// Rejects PCM that is empty, ragged, or too large to be an overlap slice.
pub fn validate_pcm(channels: &[Vec<f32>], sample_rate: f64, label: &str) -> Result<(), String> {
    let Some(first) = channels.first() else {
        return Err(format!("{label} PCM has no channels"));
    };
    if first.is_empty() {
        return Err(format!("{label} PCM is empty"));
    }
    if channels.iter().any(|channel| channel.len() != first.len()) {
        return Err(format!("{label} PCM channels have differing lengths"));
    }
    if !sample_rate.is_finite() || sample_rate < 1000.0 {
        return Err(format!("{label} sample rate {sample_rate} is not usable"));
    }
    if first.len() as f64 / sample_rate > MAX_SECONDS {
        return Err(format!(
            "{label} PCM is {:.1}s, beyond the {MAX_SECONDS}s an overlap slice should ever be",
            first.len() as f64 / sample_rate
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn js_selected(strategy: &str) -> crate::JsSelectedTransition {
        crate::JsSelectedTransition {
            outgoing_start: 4.0,
            incoming_start: 2.0,
            duration: 8.0,
            beats: 16,
            outgoing_bpm: 120.0,
            incoming_bpm: 120.0,
            target_bpm: 120.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: Some(0.25),
            incoming_pitch_semitones: Some(-0.25),
            strategy: strategy.to_string(),
        }
    }

    #[test]
    fn every_public_strategy_name_maps_exactly_once() {
        let cases = [
            (
                "equal_power_crossfade",
                earmark::TransitionStrategy::EqualPowerCrossfade,
            ),
            (
                "beatmatched_crossfade",
                earmark::TransitionStrategy::BeatmatchedCrossfade,
            ),
            ("bass_swap", earmark::TransitionStrategy::BassSwap),
            ("filtered_blend", earmark::TransitionStrategy::FilteredBlend),
            ("short_fade", earmark::TransitionStrategy::ShortFade),
        ];
        for (name, expected) in cases {
            assert_eq!(
                selected_transition(&js_selected(name)).unwrap().strategy,
                expected
            );
        }
    }

    #[test]
    fn selected_transition_conversion_preserves_exact_timing_and_ratios() {
        let selected = selected_transition(&js_selected("filtered_blend")).unwrap();

        assert_eq!(selected.outgoing_start, 4.0);
        assert_eq!(selected.incoming_start, 2.0);
        assert_eq!(selected.duration, 8.0);
        assert_eq!(selected.beats, 16);
        assert_eq!(selected.outgoing_tempo_ratio, 1.0);
        assert_eq!(selected.incoming_tempo_ratio, 1.0);
        assert_eq!(selected.outgoing_pitch_semitones, 0.25);
        assert_eq!(selected.incoming_pitch_semitones, -0.25);
    }

    #[test]
    fn unknown_strategy_names_are_stable_invalid_arguments() {
        assert_eq!(
            selected_transition(&js_selected("surprise_drop")).unwrap_err(),
            "unknown transition strategy 'surprise_drop'"
        );
    }

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

    #[test]
    fn ragged_and_empty_pcm_is_rejected() {
        assert!(validate_pcm(&[], 44_100.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![]], 44_100.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![0.0; 10], vec![0.0; 9]], 44_100.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![0.0; 10]], 0.0, "outgoing").is_err());
        assert!(validate_pcm(&[vec![0.0; 10]], 44_100.0, "outgoing").is_ok());
    }

    #[test]
    fn whole_tracks_are_refused_as_overlap_slices() {
        let frames = (44_100.0 * (MAX_SECONDS + 1.0)) as usize;
        assert!(validate_pcm(&[vec![0.0; frames]], 44_100.0, "outgoing").is_err());
    }
}
