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

//! Everything the transition engine needs that earmark itself must not know
//! about, in plain Rust with no binding types anywhere in it.
//!
//! Both bindings -- N-API for the desktop, UniFFI for Android -- are pure
//! marshalling shells over this crate, so the two platforms run the same engine
//! by construction rather than by review. That matters most for the duck-curve
//! rules in [`duck`]: a copy that drifts still renders, still passes any test
//! that checks a render succeeded, and sounds like mud.
//!
//! All times are expressed on the *slice's* timeline, not the track's. Callers
//! decode and slice before handing PCM over, so a mix-out anchor at 191 s of a
//! track that was sliced from 175 s arrives here as 16 s.
//!
//! Rendering refuses rather than failing whenever the pairing simply cannot be
//! made, because the caller's fallback -- the ordinary crossfade -- is a
//! perfectly good transition.

mod curve;
mod duck;
mod plan;
mod source;

pub use curve::depth_curve;
pub use plan::{RegionWindow, SelectedPlan, TransitionRequest};
pub use source::{MAX_SECONDS, Source, validate_pcm};

use earmark::dsp::automation::{AutomationCurve, AutomationPoint, CurveShape};
use earmark::dsp::filters::FilterKind;
use earmark::{
    CrossfadeError, EngineConfig, SmartCrossfadeEngine, TransitionOutput, TransitionPlan,
};

/// Why a pairing could not be made, in the engine's own words. Callers treat
/// this as "use the ordinary crossfade", never as an error.
pub type Refusal = String;

/// A finished overlap plus where each track had reached when it ended.
pub struct Rendered {
    pub channels: Vec<Vec<f32>>,
    pub sample_rate: u32,
    /// Length of the rendered overlap in seconds.
    pub duration: f64,
    pub beats: u32,
    /// One of the engine's strategy names, for logging.
    pub strategy: String,
    pub outgoing_start: f64,
    pub incoming_start: f64,
    /// Where each track had reached when the transition ended, on the slice timeline.
    pub outgoing_resume: f64,
    pub incoming_resume: f64,
    pub outgoing_tempo_ratio: f64,
    pub incoming_tempo_ratio: f64,
    pub target_bpm: f64,
    pub summary: String,
}

impl Rendered {
    fn new(plan: &TransitionPlan, output: &TransitionOutput) -> Self {
        Self {
            channels: (0..output.audio.channel_count())
                .map(|index| output.audio.channel(index).to_vec())
                .collect(),
            sample_rate: output.audio.sample_rate(),
            duration: plan.duration,
            beats: plan.beats,
            strategy: plan.strategy.describe().to_string(),
            outgoing_start: plan.outgoing_start,
            incoming_start: plan.incoming_start,
            outgoing_resume: output.outgoing_resume,
            incoming_resume: output.incoming_resume,
            outgoing_tempo_ratio: plan.outgoing_tempo_ratio as f64,
            incoming_tempo_ratio: plan.incoming_tempo_ratio as f64,
            target_bpm: plan.target_bpm as f64,
            summary: plan.summary(),
        }
    }
}

fn describe(error: CrossfadeError) -> Refusal {
    error.to_string()
}

/// Plans a transition inside the caller's constraints and renders it.
pub fn render_constrained(
    outgoing: &Source,
    incoming: &Source,
    request: &TransitionRequest,
) -> Result<Rendered, Refusal> {
    let outgoing_audio = outgoing.audio().map_err(describe)?;
    let incoming_audio = incoming.audio().map_err(describe)?;
    let outgoing_grid = outgoing.grid().map_err(describe)?;
    let incoming_grid = incoming.grid().map_err(describe)?;

    let config = EngineConfig {
        collect_diagnostics: request.diagnostics,
        ..EngineConfig::default()
    };
    let mut engine = SmartCrossfadeEngine::new(config).map_err(describe)?;

    let mut plan = engine
        .analyze_constrained(
            &outgoing_audio,
            &incoming_audio,
            &outgoing_grid,
            &incoming_grid,
            &request.constraints(),
        )
        .map_err(describe)?;
    duck::apply_across_slice(
        &mut plan,
        request.duck_curve.as_deref(),
        outgoing_audio.duration(),
    );

    let output = engine
        .render(&outgoing_audio, &incoming_audio, &plan)
        .map_err(describe)?;
    Ok(Rendered::new(&plan, &output))
}

fn apply_selected_shape(plan: &mut TransitionPlan, selected: &SelectedPlan) {
    if let Some(swap_fraction) = selected.bass_swap_fraction {
        let swap_at = (swap_fraction as f32).clamp(0.05, 0.95);
        let swap_start = (swap_at - 0.05).max(0.0);
        let swap_end = (swap_at + 0.05).min(1.0);
        for filter in &mut plan.filters.outgoing {
            if filter.kind == FilterKind::HighPass {
                filter.cutoff = AutomationCurve::from_points(vec![
                    AutomationPoint::new(0.0, 20.0, CurveShape::Linear),
                    AutomationPoint::new(swap_start, 20.0, CurveShape::EqualPowerIn),
                    AutomationPoint::new(swap_end, 200.0, CurveShape::Linear),
                    AutomationPoint::new(1.0, 200.0, CurveShape::Linear),
                ]);
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
        plan.filters.outgoing.retain(|f| f.kind != FilterKind::LowPass);
    }
}

/// Renders the caller's exact transition without invoking earmark analysis,
/// candidate generation, scoring, or strategy selection.
pub fn render_selected(
    outgoing: &Source,
    incoming: &Source,
    selected: &SelectedPlan,
    duck_curve: Option<&[f64]>,
) -> Result<Rendered, Refusal> {
    let selected_earmark = selected.to_earmark()?;
    let outgoing_audio = outgoing.audio().map_err(describe)?;
    let incoming_audio = incoming.audio().map_err(describe)?;

    let mut engine = SmartCrossfadeEngine::new(EngineConfig::default()).map_err(describe)?;
    let mut plan = engine
        .plan_selected(&outgoing_audio, &incoming_audio, &selected_earmark)
        .map_err(describe)?;
    apply_selected_shape(&mut plan, selected);
    duck::apply_across_overlap(&mut plan, duck_curve);

    let output = engine
        .render(&outgoing_audio, &incoming_audio, &plan)
        .map_err(describe)?;
    Ok(Rendered::new(&plan, &output))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn side(seconds: usize, sample_rate: u32) -> Source {
        Source {
            channels: vec![vec![0.0; seconds * sample_rate as usize]; 2],
            sample_rate,
            bpm: 120.0,
            beats: Vec::new(),
            downbeats: Vec::new(),
        }
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
            strategy: strategy.to_string(),
            handoff_fraction: None,
            bed_position: None,
            bass_swap_fraction: None,
            filter_sweep: None,
        }
    }

    #[test]
    fn planned_render_returns_the_exact_requested_timing() {
        let source = side(20, 8_000);
        let result = render_selected(&source, &source, &selected("beatmatched_crossfade"), None)
            .expect("a matching pair should render");

        assert_eq!(result.outgoing_start, 4.0);
        assert_eq!(result.incoming_start, 2.0);
        assert_eq!(result.duration, 8.0);
        assert_eq!(result.beats, 16);
        assert_eq!(result.outgoing_resume, 12.0);
        assert_eq!(result.incoming_resume, 10.0);
        assert_eq!(result.outgoing_tempo_ratio, 1.0);
        assert_eq!(result.incoming_tempo_ratio, 1.0);
    }

    #[test]
    fn planned_render_refusal_keeps_the_earmark_reason() {
        let source = side(20, 8_000);
        let mut choice = selected("beatmatched_crossfade");
        choice.outgoing_tempo_ratio = 1.05;
        let reason = match render_selected(&source, &source, &choice, None) {
            Ok(_) => panic!("excessive stretch unexpectedly rendered"),
            Err(reason) => reason,
        };

        assert_eq!(
            reason,
            "invalid selected transition: tempo ratio deviation 0.0500 exceeds the transparent limit 0.0400"
        );
    }
}
