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

//! N-API bindings over `orchard-transition-core`, which owns everything the
//! engine needs that earmark must not know about. Everything here is
//! marshalling: this crate holds only what is specific to JavaScript.
//!
//! Android reaches the same core through UniFFI, so a rule that lives here
//! rather than in the core is a rule only one platform obeys.
//!
//! All times are expressed on the *slice's* timeline, not the track's. The
//! renderer decodes and slices before handing PCM over, so a mix-out anchor at
//! 191 s of a track that was sliced from 175 s arrives here as 16 s.
//!
//! Rendering refuses rather than throwing whenever the pairing simply cannot be
//! made, because the caller's fallback -- the ordinary crossfade -- is a
//! perfectly good transition. Only structurally invalid input throws.

use napi::bindgen_prelude::*;
use napi_derive::napi;
use orchard_transition_core as core;

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

impl JsTransitionSource {
    /// Copies the PCM off the JS heap, so the render can run on a worker thread.
    fn take(self, label: &str) -> Result<core::Source> {
        let source = core::Source {
            channels: self
                .channels
                .iter()
                .map(|channel| channel.as_ref().to_vec())
                .collect(),
            sample_rate: self.sample_rate as u32,
            bpm: self.bpm as f32,
            beats: self.beats,
            downbeats: self.downbeats,
        };
        core::validate_pcm(&source.channels, self.sample_rate, label)
            .map_err(|reason| Error::new(Status::InvalidArg, reason))?;
        Ok(source)
    }
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

impl JsRegionConstraint {
    fn take(value: Option<Self>) -> core::RegionWindow {
        let Some(value) = value else {
            return core::RegionWindow::default();
        };
        core::RegionWindow {
            start_earliest: value.start_earliest,
            start_latest: value.start_latest,
            end_earliest: value.end_earliest,
            end_latest: value.end_latest,
        }
    }
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
    fn refused(reason: core::Refusal) -> Self {
        Self {
            rendered: false,
            rejected: reason,
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

    fn rendered(result: core::Rendered) -> Self {
        Self {
            rendered: true,
            rejected: String::new(),
            channels: result.channels.into_iter().map(Float32Array::new).collect(),
            sample_rate: result.sample_rate as f64,
            duration: result.duration,
            beats: result.beats,
            strategy: result.strategy,
            outgoing_start: result.outgoing_start,
            incoming_start: result.incoming_start,
            outgoing_resume: result.outgoing_resume,
            incoming_resume: result.incoming_resume,
            outgoing_tempo_ratio: result.outgoing_tempo_ratio,
            incoming_tempo_ratio: result.incoming_tempo_ratio,
            target_bpm: result.target_bpm,
            summary: result.summary,
        }
    }

    fn from(outcome: std::result::Result<core::Rendered, core::Refusal>) -> Self {
        outcome.map_or_else(Self::refused, Self::rendered)
    }
}

pub struct RenderTransition {
    outgoing: core::Source,
    incoming: core::Source,
    request: core::TransitionRequest,
}

impl Task for RenderTransition {
    type Output = JsTransitionResult;
    type JsValue = JsTransitionResult;

    fn compute(&mut self) -> Result<Self::Output> {
        Ok(JsTransitionResult::from(core::render_constrained(
            &self.outgoing,
            &self.incoming,
            &self.request,
        )))
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(output)
    }
}

/// Worker task for the exact plan path. It owns no beat grid or constraints:
/// those would be alternate choices, and the caller has already made them.
pub struct RenderPlannedTransition {
    outgoing: core::Source,
    incoming: core::Source,
    selected: core::SelectedPlan,
    duck_points: Option<Vec<f64>>,
}

impl Task for RenderPlannedTransition {
    type Output = JsTransitionResult;
    type JsValue = JsTransitionResult;

    fn compute(&mut self) -> Result<Self::Output> {
        Ok(JsTransitionResult::from(core::render_selected(
            &self.outgoing,
            &self.incoming,
            &self.selected,
            self.duck_points.as_deref(),
        )))
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(output)
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
        outgoing: outgoing.take("outgoing")?,
        incoming: incoming.take("incoming")?,
        request: core::TransitionRequest {
            outgoing: JsRegionConstraint::take(options.outgoing),
            incoming: JsRegionConstraint::take(options.incoming),
            beat_lengths: options.beat_lengths,
            duck_curve: options.duck_curve.filter(|points| !points.is_empty()),
            diagnostics: options.diagnostics.unwrap_or(false),
        },
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
    let selected = core::SelectedPlan {
        outgoing_start: plan.outgoing_start,
        incoming_start: plan.incoming_start,
        duration: plan.duration,
        beats: plan.beats,
        outgoing_bpm: plan.outgoing_bpm,
        incoming_bpm: plan.incoming_bpm,
        target_bpm: plan.target_bpm,
        outgoing_tempo_ratio: plan.outgoing_tempo_ratio,
        incoming_tempo_ratio: plan.incoming_tempo_ratio,
        outgoing_pitch_semitones: plan.outgoing_pitch_semitones,
        incoming_pitch_semitones: plan.incoming_pitch_semitones,
        strategy: plan.strategy,
    };
    // A strategy name the engine does not know is a caller mistake, not a pairing that cannot be
    // made, so it throws here rather than resolving to a refusal the caller would act on by
    // quietly falling back.
    selected
        .validate()
        .map_err(|reason| Error::new(Status::InvalidArg, reason))?;

    Ok(AsyncTask::new(RenderPlannedTransition {
        outgoing: outgoing.take("outgoing")?,
        incoming: incoming.take("incoming")?,
        selected,
        duck_points: options.duck_curve.filter(|points| !points.is_empty()),
    }))
}
