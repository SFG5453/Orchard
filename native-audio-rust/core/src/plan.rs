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

//! What the caller asks for: either a region to plan inside, or an exact choice already made.

use earmark::{
    RegionConstraint, SelectedTransition, TimeWindow, TransitionConstraints, TransitionStrategy,
};

/// Where a transition may begin and end on one track. Each window needs both of its bounds; a
/// half-specified window is treated as absent.
///
/// Ends, not starts, are what pin a mix to a cue: "finish on the incoming track's drop" is a
/// constraint on the end. A window **replaces** the configured search region rather than narrowing
/// it, so a cue outside the default 45 s window is still honoured.
#[derive(Clone, Debug, Default)]
pub struct RegionWindow {
    pub start_earliest: Option<f64>,
    pub start_latest: Option<f64>,
    pub end_earliest: Option<f64>,
    pub end_latest: Option<f64>,
}

impl RegionWindow {
    fn to_earmark(&self) -> RegionConstraint {
        RegionConstraint {
            start_within: self
                .start_earliest
                .zip(self.start_latest)
                .map(|(a, b)| TimeWindow::new(a, b)),
            end_within: self
                .end_earliest
                .zip(self.end_latest)
                .map(|(a, b)| TimeWindow::new(a, b)),
        }
    }
}

/// A transition the engine still has to choose, inside the caller's bounds.
#[derive(Clone, Debug, Default)]
pub struct TransitionRequest {
    pub outgoing: RegionWindow,
    pub incoming: RegionWindow,
    /// Restricts the transition length. Values the engine does not already allow are ignored,
    /// so a list of only such values admits nothing and the engine refuses.
    pub beat_lengths: Option<Vec<u32>>,
    /// Per-instant depth for the outgoing filter ride, one value in `0..=1` per evenly spaced
    /// control point spanning the **outgoing PCM supplied**, first sample to last. Absent leaves
    /// the ride at full depth.
    pub duck_curve: Option<Vec<f64>>,
    /// Attach every scored candidate to the result's summary.
    pub diagnostics: bool,
}

impl TransitionRequest {
    pub(crate) fn constraints(&self) -> TransitionConstraints {
        TransitionConstraints {
            outgoing: self.outgoing.to_earmark(),
            incoming: self.incoming.to_earmark(),
            beat_lengths: self
                .beat_lengths
                .clone()
                .filter(|lengths| !lengths.is_empty()),
        }
    }
}

/// A transition the caller has already chosen in full.
#[derive(Clone, Debug)]
pub struct SelectedPlan {
    pub outgoing_start: f64,
    pub incoming_start: f64,
    pub duration: f64,
    pub beats: u32,
    pub outgoing_bpm: f64,
    pub incoming_bpm: f64,
    pub target_bpm: f64,
    pub outgoing_tempo_ratio: f64,
    pub incoming_tempo_ratio: f64,
    pub outgoing_pitch_semitones: Option<f64>,
    pub incoming_pitch_semitones: Option<f64>,
    pub strategy: String,
}

impl SelectedPlan {
    /// Rejects a plan the exact-render path could never execute, so a binding can answer a
    /// caller mistake as one rather than as a refusal the caller would read as "this pairing
    /// does not work".
    pub fn validate(&self) -> Result<(), String> {
        strategy(&self.strategy).map(|_| ())
    }

    /// Converts only the public, stable strategy vocabulary. The exact-render API is a contract
    /// boundary, so aliases and fuzzy matching would hide caller mistakes and could silently
    /// execute a different DSP shape.
    pub(crate) fn to_earmark(&self) -> Result<SelectedTransition, String> {
        Ok(SelectedTransition {
            outgoing_start: self.outgoing_start,
            incoming_start: self.incoming_start,
            duration: self.duration,
            beats: self.beats,
            outgoing_bpm: self.outgoing_bpm as f32,
            incoming_bpm: self.incoming_bpm as f32,
            target_bpm: self.target_bpm as f32,
            outgoing_tempo_ratio: self.outgoing_tempo_ratio as f32,
            incoming_tempo_ratio: self.incoming_tempo_ratio as f32,
            outgoing_pitch_semitones: self.outgoing_pitch_semitones.unwrap_or(0.0) as f32,
            incoming_pitch_semitones: self.incoming_pitch_semitones.unwrap_or(0.0) as f32,
            strategy: strategy(&self.strategy)?,
        })
    }
}

fn strategy(name: &str) -> Result<TransitionStrategy, String> {
    match name {
        "equal_power_crossfade" => Ok(TransitionStrategy::EqualPowerCrossfade),
        "beatmatched_crossfade" => Ok(TransitionStrategy::BeatmatchedCrossfade),
        "bass_swap" => Ok(TransitionStrategy::BassSwap),
        "filtered_blend" => Ok(TransitionStrategy::FilteredBlend),
        "short_fade" => Ok(TransitionStrategy::ShortFade),
        other => Err(format!("unknown transition strategy '{other}'")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
                TransitionStrategy::EqualPowerCrossfade,
            ),
            (
                "beatmatched_crossfade",
                TransitionStrategy::BeatmatchedCrossfade,
            ),
            ("bass_swap", TransitionStrategy::BassSwap),
            ("filtered_blend", TransitionStrategy::FilteredBlend),
            ("short_fade", TransitionStrategy::ShortFade),
        ];
        for (name, expected) in cases {
            assert_eq!(selected(name).to_earmark().unwrap().strategy, expected);
        }
    }

    #[test]
    fn selected_transition_conversion_preserves_exact_timing_and_ratios() {
        let selected = selected("filtered_blend").to_earmark().unwrap();

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
            selected("surprise_drop").to_earmark().unwrap_err(),
            "unknown transition strategy 'surprise_drop'"
        );
    }

    /// A window needs both bounds to mean anything, and an all-absent region must constrain
    /// nothing rather than collapsing to a zero-width window at the origin.
    #[test]
    fn a_half_specified_window_is_treated_as_absent() {
        let region = RegionWindow {
            start_earliest: Some(10.0),
            ..RegionWindow::default()
        }
        .to_earmark();
        assert!(region.start_within.is_none());
        assert!(region.end_within.is_none());
        assert!(RegionWindow::default().to_earmark().is_unconstrained());
    }

    #[test]
    fn an_empty_beat_length_list_is_no_opinion_rather_than_no_transition() {
        let request = TransitionRequest {
            beat_lengths: Some(Vec::new()),
            ..TransitionRequest::default()
        };
        assert!(request.constraints().beat_lengths.is_none());
    }
}
