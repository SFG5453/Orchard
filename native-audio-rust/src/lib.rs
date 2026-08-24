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

//! N-API bindings over the earmark crate, which owns the transition engine.
//! Everything here is marshalling: this crate holds only what is specific to
//! Orchard.
//!
//! All times are expressed on the *slice's* timeline, not the track's. The
//! renderer decodes and slices before handing PCM over, so a mix-out anchor at
//! 191 s of a track that was sliced from 175 s arrives here as 16 s.
//!
//! Rendering refuses rather than throwing whenever the pairing simply cannot be
//! made, because the caller's fallback -- the ordinary crossfade -- is a
//! perfectly good transition. Only structurally invalid input throws.

mod convert;

use earmark::dsp::filters::FilterKind;
use earmark::{
    AudioBuffer, BeatAnalysis, EngineConfig, SelectedTransition, SmartCrossfadeEngine,
    TransitionConstraints, TransitionOutput, TransitionPlan,
};
use napi::bindgen_prelude::*;
use napi_derive::napi;

/// One side of the transition: planar PCM plus the beat grid the caller already has.
#[napi(object)]
pub struct JsTransitionSource {
    pub channels: Vec<Float32Array>,
    pub sample_rate: f64,
    pub bpm: f64,
    /// Beat times in seconds, relative to the start of `channels`.
    pub beats: Vec<f64>,
    /// Downbeat times in seconds, relative to the start of `channels`. Candidate placement and
    /// phrase alignment both key off these, so a grid without them scores blind on structure.
    pub downbeats: Vec<f64>,
}

/// Where a transition may begin and end on one track. Each window needs both of its bounds; a
/// half-specified window is treated as absent.
#[napi(object)]
pub struct JsRegionConstraint {
    pub start_earliest: Option<f64>,
    pub start_latest: Option<f64>,
    pub end_earliest: Option<f64>,
    pub end_latest: Option<f64>,
}

#[napi(object)]
pub struct JsTransitionOptions {
    pub outgoing: Option<JsRegionConstraint>,
    pub incoming: Option<JsRegionConstraint>,
    /// Restricts the transition length. Values the engine does not already allow are ignored.
    pub beat_lengths: Option<Vec<u32>>,
    /// Per-instant depth for the outgoing filter ride, one value in `0..=1` per evenly spaced
    /// control point spanning the **outgoing PCM supplied**, first sample to last.
    ///
    /// It spans the slice rather than the transition because the caller has to measure before
    /// this call, and what the transition *is* is what this call decides. The engine crops the
    /// curve to whatever region it picks. Absent leaves the ride at full depth.
    pub duck_curve: Option<Vec<f64>>,
    /// Attach every scored candidate to the result's summary.
    pub diagnostics: Option<bool>,
}

/// Exact caller-selected plan. napi-rs exposes these snake-case Rust fields as
/// camelCase JavaScript properties.
#[napi(object)]
pub struct JsSelectedTransition {
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

#[napi(object)]
pub struct JsPlannedTransitionOptions {
    /// Per-instant depth across the already-selected outgoing overlap.
    pub duck_curve: Option<Vec<f64>>,
}

/// Mirrors the shape the C++ renderer returned, so the IPC handler is unchanged in kind:
/// `rendered` false plus a `rejected` reason means "use the ordinary crossfade instead".
#[napi(object)]
pub struct JsTransitionResult {
    pub rendered: bool,
    pub rejected: String,
    pub channels: Vec<Float32Array>,
    pub sample_rate: f64,
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

impl JsTransitionResult {
    fn refused(reason: impl Into<String>) -> Self {
        Self {
            rendered: false,
            rejected: reason.into(),
            channels: Vec::new(),
            sample_rate: 0.0,
            duration: 0.0,
            beats: 0,
            strategy: String::new(),
            outgoing_start: 0.0,
            incoming_start: 0.0,
            outgoing_resume: 0.0,
            incoming_resume: 0.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            target_bpm: 0.0,
            summary: String::new(),
        }
    }

    fn rendered(plan: &TransitionPlan, output: &TransitionOutput) -> Self {
        Self {
            rendered: true,
            rejected: String::new(),
            channels: (0..output.audio.channel_count())
                .map(|index| Float32Array::new(output.audio.channel(index).to_vec()))
                .collect(),
            sample_rate: output.audio.sample_rate() as f64,
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

/// PCM copied off the JS heap, so the render can run on a worker thread.
struct Side {
    channels: Vec<Vec<f32>>,
    sample_rate: u32,
    bpm: f32,
    beats: Vec<f64>,
    downbeats: Vec<f64>,
}

impl Side {
    fn take(source: JsTransitionSource, label: &str) -> Result<Self> {
        let channels: Vec<Vec<f32>> = source
            .channels
            .iter()
            .map(|channel| channel.as_ref().to_vec())
            .collect();
        convert::validate_pcm(&channels, source.sample_rate, label)
            .map_err(|reason| Error::new(Status::InvalidArg, reason))?;
        Ok(Self {
            channels,
            sample_rate: source.sample_rate as u32,
            bpm: source.bpm as f32,
            beats: source.beats,
            downbeats: source.downbeats,
        })
    }

    fn audio(&self) -> std::result::Result<AudioBuffer, earmark::CrossfadeError> {
        AudioBuffer::new(self.channels.clone(), self.sample_rate)
    }

    fn grid(&self) -> std::result::Result<BeatAnalysis, earmark::CrossfadeError> {
        BeatAnalysis::new(self.bpm, self.beats.clone(), self.downbeats.clone())
    }
}

pub struct RenderTransition {
    outgoing: Side,
    incoming: Side,
    constraints: TransitionConstraints,
    duck_points: Option<Vec<f64>>,
    diagnostics: bool,
}

impl Task for RenderTransition {
    type Output = JsTransitionResult;
    type JsValue = JsTransitionResult;

    fn compute(&mut self) -> Result<Self::Output> {
        Ok(self.run().unwrap_or_else(JsTransitionResult::refused))
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(output)
    }
}

impl RenderTransition {
    /// Returns `Err(reason)` for anything the caller should treat as a refusal.
    fn run(&mut self) -> std::result::Result<JsTransitionResult, String> {
        let describe = |error: earmark::CrossfadeError| error.to_string();

        let outgoing = self.outgoing.audio().map_err(describe)?;
        let incoming = self.incoming.audio().map_err(describe)?;
        let outgoing_beats = self.outgoing.grid().map_err(describe)?;
        let incoming_beats = self.incoming.grid().map_err(describe)?;

        let config = EngineConfig {
            collect_diagnostics: self.diagnostics,
            ..EngineConfig::default()
        };
        let mut engine = SmartCrossfadeEngine::new(config).map_err(describe)?;

        let mut plan = engine
            .analyze_constrained(
                &outgoing,
                &incoming,
                &outgoing_beats,
                &incoming_beats,
                &self.constraints,
            )
            .map_err(describe)?;
        self.apply_duck_curve(&mut plan, outgoing.duration());

        let output = engine
            .render(&outgoing, &incoming, &plan)
            .map_err(describe)?;

        Ok(JsTransitionResult::rendered(&plan, &output))
    }

    /// Hands the caller's measurement to the outgoing low-pass ride, if the planner chose one.
    ///
    /// Only the low-pass. That filter is the one whose job is to get the outgoing track out of
    /// the way, so how far it travels is exactly what a presence measurement should govern. A
    /// bass swap's high-pass is a structural hand-over -- it is what stops two kick drums sharing
    /// the bottom octave -- and scaling *it* by the same curve would let a quiet moment hold the
    /// outgoing low end in place under a track that has already taken over. A strategy that rides
    /// nothing at all leaves the curve unused rather than forcing a filter that was not planned.
    fn apply_duck_curve(&self, plan: &mut TransitionPlan, source_duration: f64) {
        let Some(points) = &self.duck_points else {
            return;
        };
        let rides = plan
            .filters
            .outgoing
            .iter()
            .any(|filter| filter.kind == FilterKind::LowPass);
        if !rides || source_duration <= 0.0 {
            return;
        }
        // The measurement spans the whole outgoing slice; the transition is the part of it the
        // planner settled on.
        let from = plan.outgoing_start / source_duration;
        let to = plan.outgoing_end() / source_duration;
        let Some(curve) = convert::depth_curve(points, from, to) else {
            return;
        };
        for filter in &mut plan.filters.outgoing {
            if filter.kind == FilterKind::LowPass {
                filter.depth = Some(curve.clone());
            }
        }
    }
}

/// Worker task for the exact plan path. It owns no beat grid or constraints:
/// those would be alternate choices, and the caller has already made them.
pub struct RenderPlannedTransition {
    outgoing: Side,
    incoming: Side,
    selected: SelectedTransition,
    duck_points: Option<Vec<f64>>,
}

impl Task for RenderPlannedTransition {
    type Output = JsTransitionResult;
    type JsValue = JsTransitionResult;

    fn compute(&mut self) -> Result<Self::Output> {
        Ok(self.run().unwrap_or_else(JsTransitionResult::refused))
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(output)
    }
}

impl RenderPlannedTransition {
    fn run(&mut self) -> std::result::Result<JsTransitionResult, String> {
        let describe = |error: earmark::CrossfadeError| error.to_string();
        let outgoing = self.outgoing.audio().map_err(describe)?;
        let incoming = self.incoming.audio().map_err(describe)?;
        let mut engine = SmartCrossfadeEngine::new(EngineConfig::default()).map_err(describe)?;
        let mut plan = engine
            .plan_selected(&outgoing, &incoming, &self.selected)
            .map_err(describe)?;
        self.apply_duck_curve(&mut plan);
        let output = engine
            .render(&outgoing, &incoming, &plan)
            .map_err(describe)?;
        Ok(JsTransitionResult::rendered(&plan, &output))
    }

    fn apply_duck_curve(&self, plan: &mut TransitionPlan) {
        let Some(points) = &self.duck_points else {
            return;
        };
        let Some(curve) = convert::depth_curve(points, 0.0, 1.0) else {
            return;
        };
        for filter in &mut plan.filters.outgoing {
            if filter.kind == FilterKind::LowPass {
                filter.depth = Some(curve.clone());
            }
        }
    }
}

/// Plans a transition inside the caller's constraints and renders it.
#[napi(ts_return_type = "Promise<JsTransitionResult>")]
pub fn render_transition(
    outgoing: JsTransitionSource,
    incoming: JsTransitionSource,
    options: JsTransitionOptions,
) -> Result<AsyncTask<RenderTransition>> {
    Ok(AsyncTask::new(RenderTransition {
        outgoing: Side::take(outgoing, "outgoing")?,
        incoming: Side::take(incoming, "incoming")?,
        constraints: convert::constraints(&options),
        duck_points: options.duck_curve.filter(|points| !points.is_empty()),
        diagnostics: options.diagnostics.unwrap_or(false),
    }))
}

/// Renders the caller's exact transition without invoking Earmark analysis,
/// candidate generation, scoring, or strategy selection.
#[napi(ts_return_type = "Promise<JsTransitionResult>")]
pub fn render_planned_transition(
    outgoing: JsTransitionSource,
    incoming: JsTransitionSource,
    plan: JsSelectedTransition,
    options: JsPlannedTransitionOptions,
) -> Result<AsyncTask<RenderPlannedTransition>> {
    let selected = convert::selected_transition(&plan)
        .map_err(|reason| Error::new(Status::InvalidArg, reason))?;
    Ok(AsyncTask::new(RenderPlannedTransition {
        outgoing: Side::take(outgoing, "outgoing")?,
        incoming: Side::take(incoming, "incoming")?,
        selected,
        duck_points: options.duck_curve.filter(|points| !points.is_empty()),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use earmark::dsp::automation::{AutomationCurve, CurveShape};
    use earmark::dsp::fade::FadeCurve;
    use earmark::dsp::filters::FilterAutomation;
    use earmark::types::transition::{FadePlan, FilterPlan};
    use earmark::TransitionStrategy;

    fn selected(strategy: TransitionStrategy) -> earmark::SelectedTransition {
        earmark::SelectedTransition {
            outgoing_start: 4.0,
            incoming_start: 2.0,
            duration: 8.0,
            beats: 16,
            outgoing_bpm: 120.0,
            incoming_bpm: 120.0,
            target_bpm: 120.0,
            outgoing_tempo_ratio: 1.0,
            incoming_tempo_ratio: 1.0,
            outgoing_pitch_semitones: 0.0,
            incoming_pitch_semitones: 0.0,
            strategy,
        }
    }

    fn side() -> Side {
        Side {
            channels: vec![vec![0.0; 1024]; 2],
            sample_rate: 44_100,
            bpm: 120.0,
            beats: Vec::new(),
            downbeats: Vec::new(),
        }
    }

    fn task(duck_points: Option<Vec<f64>>) -> RenderTransition {
        RenderTransition {
            outgoing: side(),
            incoming: side(),
            constraints: TransitionConstraints::NONE,
            duck_points,
            diagnostics: false,
        }
    }

    fn planned_task(choice: earmark::SelectedTransition) -> RenderPlannedTransition {
        let render_side = || Side {
            channels: vec![vec![0.0; 20 * 8_000]; 2],
            sample_rate: 8_000,
            bpm: 120.0,
            beats: Vec::new(),
            downbeats: Vec::new(),
        };
        RenderPlannedTransition {
            outgoing: render_side(),
            incoming: render_side(),
            selected: choice,
            duck_points: None,
        }
    }

    #[test]
    fn planned_render_returns_the_exact_requested_timing() {
        let result = planned_task(selected(TransitionStrategy::BeatmatchedCrossfade))
            .run()
            .unwrap();

        assert!(result.rendered);
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
        let mut choice = selected(TransitionStrategy::BeatmatchedCrossfade);
        choice.outgoing_tempo_ratio = 1.05;
        let reason = match planned_task(choice).run() {
            Ok(_) => panic!("excessive stretch unexpectedly rendered"),
            Err(reason) => reason,
        };

        assert_eq!(
            reason,
            "invalid selected transition: tempo ratio deviation 0.0500 exceeds the transparent limit 0.0400"
        );
    }

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
        task(Some(vec![0.0, 0.5, 1.0])).apply_duck_curve(plan, 10.0);
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
        task(Some(vec![0.0, 0.0, 0.0])).apply_duck_curve(plan, 10.0);
        assert!(plan.filters.outgoing[0].depth.is_none());
        assert!(plan.filters.incoming[0].depth.is_none());
    }

    #[test]
    fn a_strategy_that_rides_nothing_is_left_alone() {
        let plan = &mut plan_with(
            FilterPlan::default(),
            TransitionStrategy::EqualPowerCrossfade,
        );
        task(Some(vec![0.5; 8])).apply_duck_curve(plan, 10.0);
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
        task(None).apply_duck_curve(plan, 10.0);
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
        task(Some(points)).apply_duck_curve(plan, 10.0);

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
