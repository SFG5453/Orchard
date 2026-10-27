/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! The exact transition the shared planner chose, in the shape earmark renders.

use earmark::{SelectedTransition, TransitionStrategy};

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
    /// The tempo ratios are averages of one glide from the outgoing to the incoming BPM.
    pub tempo_ramp: bool,
    pub strategy: String,
    pub handoff_fraction: Option<f64>,
    pub bed_position: Option<f64>,
    pub bass_swap_fraction: Option<f64>,
    pub filter_sweep: Option<f64>,
    /// Fractions of the overlap each side's fade moves over; `None` fades across all of it.
    pub outgoing_fade_span: Option<(f64, f64)>,
    pub incoming_fade_span: Option<(f64, f64)>,
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
    pub fn to_earmark(&self) -> Result<SelectedTransition, String> {
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
            tempo_ramp: self.tempo_ramp,
            strategy: strategy(&self.strategy)?,
        })
    }
}

fn strategy(name: &str) -> Result<TransitionStrategy, String> {
    match name {
        "equal_power_crossfade" => Ok(TransitionStrategy::EqualPowerCrossfade),
        "beatmatched_crossfade" => Ok(TransitionStrategy::BeatmatchedCrossfade),
        // A muffle is a bass swap whose filters `apply_selected_shape` rebuilds.
        "bass_swap" | "muffle_swap" => Ok(TransitionStrategy::BassSwap),
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
}
