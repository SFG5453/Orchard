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
use std::sync::{Mutex, OnceLock};

use earmark::analysis::{
    BEAT_SPECTROGRAM_HOP, BEAT_SPECTROGRAM_MELS, BEAT_SPECTROGRAM_SAMPLE_RATE, BeatSpectrogram,
    VOCAL_SPECTROGRAM_BINS, VOCAL_SPECTROGRAM_CHANNELS, VOCAL_SPECTROGRAM_HOP,
    VOCAL_SPECTROGRAM_SAMPLE_RATE, VocalSpectrogram, WholeTrackAnalysis, WholeTrackAnalyzer,
};

/// Persisted Orchard analysis/cache contract. The language changed; its meaning did not.
pub const ANALYSIS_VERSION: u32 = 13;

#[napi(module_exports)]
#[allow(dead_code)]
fn export_analysis_version(mut exports: Object) -> Result<()> {
    exports.set_named_property("analysisVersion", ANALYSIS_VERSION)
}

static ANALYZER_POOL: OnceLock<Mutex<Vec<WholeTrackAnalyzer>>> = OnceLock::new();

fn with_analyzer<T>(
    operation: impl FnOnce(&mut WholeTrackAnalyzer) -> earmark::Result<T>,
) -> earmark::Result<T> {
    let pool = ANALYZER_POOL.get_or_init(|| Mutex::new(Vec::new()));
    let mut analyzer = pool
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .pop()
        .map_or_else(WholeTrackAnalyzer::new, Ok)?;
    let result = operation(&mut analyzer);
    pool.lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .push(analyzer);
    result
}

fn analysis_error(error: earmark::CrossfadeError) -> Error {
    Error::new(Status::GenericFailure, error.to_string())
}

fn compact(value: f64) -> f64 {
    (value * 10_000.0).round() / 10_000.0
}

fn compact_vec(values: Vec<f64>) -> Vec<f64> {
    values.into_iter().map(compact).collect()
}

#[napi(object)]
pub struct JsEnergyPoint {
    pub time: f64,
    pub energy: f64,
}

#[napi(object)]
pub struct JsPhrase {
    pub start: f64,
    pub end: f64,
    pub r#type: String,
    pub confidence: f64,
}

#[napi(object)]
pub struct JsMixCuePoint {
    pub time: f64,
    pub score: f64,
    pub r#type: String,
}

#[napi(object)]
pub struct JsMeterEvidence {
    pub beats_per_bar: u32,
    pub confidence: f64,
    pub source: String,
}

#[napi(object)]
pub struct JsTransitionFeatureFrame {
    pub time: f64,
    pub energy: f64,
    pub low: f64,
    pub mid: f64,
    pub high: f64,
    pub vocal: f64,
    pub novelty: f64,
    pub transient_density: f64,
    pub stability: f64,
}

#[napi(object)]
pub struct JsStructuralBoundaryCandidate {
    pub time: f64,
    pub observed_time: f64,
    pub confidence: f64,
    pub source: String,
    pub novelty_peak: f64,
    pub energy_delta: f64,
    pub low_delta: f64,
    pub vocal_delta: f64,
    pub stability_before: f64,
    pub stability_after: f64,
    pub downbeat_distance: f64,
}

#[napi(object)]
pub struct JsTrackAnalysis {
    pub analysis_version: u32,
    pub duration: f64,
    pub bpm: f64,
    pub beat_interval: f64,
    pub first_beat: f64,
    pub beat_confidence: f64,
    pub beats: Vec<f64>,
    pub downbeats: Vec<f64>,
    pub phrase_boundaries: Vec<f64>,
    pub phrases: Vec<JsPhrase>,
    pub key: String,
    pub key_confidence: f64,
    pub chroma: Vec<f64>,
    pub audible_start_time: f64,
    pub pickup_time: f64,
    pub pickup_confidence: f64,
    pub mix_in_time: f64,
    pub mix_in_confidence: f64,
    pub intro_end_time: f64,
    pub outro_start_time: f64,
    pub content_end_time: f64,
    pub mix_out_time: f64,
    pub loudness_lufs: f64,
    pub peak_dbfs: f64,
    pub dynamic_range_db: f64,
    pub energy_curve: Vec<JsEnergyPoint>,
    pub low_energy_curve: Vec<JsEnergyPoint>,
    pub mid_energy_curve: Vec<JsEnergyPoint>,
    pub high_energy_curve: Vec<JsEnergyPoint>,
    pub vocal_activity_mask: Vec<f64>,
    pub vocal_probability: f64,
    pub instrumental_probability: f64,
    pub mix_in_candidates: Vec<JsMixCuePoint>,
    pub mix_out_candidates: Vec<JsMixCuePoint>,
    pub meter: JsMeterEvidence,
    pub transition_feature_frames: Vec<JsTransitionFeatureFrame>,
    pub structural_boundary_candidates: Vec<JsStructuralBoundaryCandidate>,
}

impl From<WholeTrackAnalysis> for JsTrackAnalysis {
    fn from(result: WholeTrackAnalysis) -> Self {
        let vocal_probability = compact(result.vocal_probability);
        let instrumental_probability = compact(result.instrumental_probability);
        Self {
            analysis_version: ANALYSIS_VERSION,
            duration: compact(result.duration),
            bpm: compact(result.bpm),
            beat_interval: compact(result.beat_interval),
            first_beat: compact(result.first_beat),
            beat_confidence: compact(result.beat_confidence),
            beats: compact_vec(result.beats),
            downbeats: compact_vec(result.downbeats),
            phrase_boundaries: compact_vec(result.phrase_boundaries),
            phrases: result
                .phrases
                .into_iter()
                .map(|phrase| JsPhrase {
                    start: compact(phrase.start),
                    end: compact(phrase.end),
                    r#type: phrase.kind,
                    confidence: compact(phrase.confidence),
                })
                .collect(),
            key: result.key,
            key_confidence: compact(result.key_confidence),
            chroma: compact_vec(result.chroma),
            audible_start_time: compact(result.audible_start_time),
            pickup_time: compact(result.pickup_time),
            pickup_confidence: compact(result.pickup_confidence),
            mix_in_time: compact(result.mix_in_time),
            mix_in_confidence: compact(result.mix_in_confidence),
            intro_end_time: compact(result.intro_end_time),
            outro_start_time: compact(result.outro_start_time),
            content_end_time: compact(result.content_end_time),
            mix_out_time: compact(result.mix_out_time),
            loudness_lufs: compact(result.loudness_lufs),
            peak_dbfs: compact(result.peak_dbfs),
            dynamic_range_db: compact(result.dynamic_range_db),
            energy_curve: js_curve(result.energy_curve),
            low_energy_curve: js_curve(result.low_energy_curve),
            mid_energy_curve: js_curve(result.mid_energy_curve),
            high_energy_curve: js_curve(result.high_energy_curve),
            vocal_activity_mask: compact_vec(result.vocal_activity_mask),
            vocal_probability,
            instrumental_probability,
            mix_in_candidates: result
                .mix_in_candidates
                .into_iter()
                .map(|cue| JsMixCuePoint {
                    time: compact(cue.time),
                    score: compact(cue.score),
                    r#type: cue.kind,
                })
                .collect(),
            mix_out_candidates: result
                .mix_out_candidates
                .into_iter()
                .map(|cue| JsMixCuePoint {
                    time: compact(cue.time),
                    score: compact(cue.score),
                    r#type: cue.kind,
                })
                .collect(),
            meter: JsMeterEvidence {
                beats_per_bar: result.meter.beats_per_bar,
                confidence: compact(result.meter.confidence),
                source: result.meter.source,
            },
            transition_feature_frames: result
                .transition_feature_frames
                .into_iter()
                .map(|frame| JsTransitionFeatureFrame {
                    time: compact(frame.time),
                    energy: compact(frame.energy),
                    low: compact(frame.low),
                    mid: compact(frame.mid),
                    high: compact(frame.high),
                    vocal: compact(frame.vocal),
                    novelty: compact(frame.novelty),
                    transient_density: compact(frame.transient_density),
                    stability: compact(frame.stability),
                })
                .collect(),
            structural_boundary_candidates: result
                .structural_boundary_candidates
                .into_iter()
                .map(|boundary| JsStructuralBoundaryCandidate {
                    time: compact(boundary.time),
                    observed_time: compact(boundary.observed_time),
                    confidence: compact(boundary.confidence),
                    source: boundary.source,
                    novelty_peak: compact(boundary.novelty_peak),
                    energy_delta: compact(boundary.energy_delta),
                    low_delta: compact(boundary.low_delta),
                    vocal_delta: compact(boundary.vocal_delta),
                    stability_before: compact(boundary.stability_before),
                    stability_after: compact(boundary.stability_after),
                    downbeat_distance: compact(boundary.downbeat_distance),
                })
                .collect(),
        }
    }
}

fn js_curve(points: Vec<earmark::analysis::EnergyPoint>) -> Vec<JsEnergyPoint> {
    points
        .into_iter()
        .map(|point| JsEnergyPoint {
            time: compact(point.time),
            energy: compact(point.energy),
        })
        .collect()
}

pub struct AnalyzeAudioTask {
    samples: Vec<f32>,
    sample_rate: f64,
    duration: f64,
}

impl Task for AnalyzeAudioTask {
    type Output = WholeTrackAnalysis;
    type JsValue = JsTrackAnalysis;

    fn compute(&mut self) -> Result<Self::Output> {
        with_analyzer(|analyzer| analyzer.analyze(&self.samples, self.sample_rate, self.duration))
            .map_err(analysis_error)
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(output.into())
    }
}

#[napi(ts_return_type = "Promise<JsTrackAnalysis>")]
pub fn analyze(
    samples: Float32Array,
    sample_rate: f64,
    duration: f64,
) -> Result<AsyncTask<AnalyzeAudioTask>> {
    if samples.is_empty()
        || !sample_rate.is_finite()
        || sample_rate < 1000.0
        || !duration.is_finite()
        || duration <= 0.0
    {
        return Err(Error::new(
            Status::InvalidArg,
            "audio samples, sample rate, and duration must be valid",
        ));
    }
    Ok(AsyncTask::new(AnalyzeAudioTask {
        samples: samples.as_ref().to_vec(),
        sample_rate,
        duration,
    }))
}

#[napi(object)]
pub struct JsBeatSpectrogram {
    pub values: Float32Array,
    pub frames: u32,
    pub mels: u32,
    pub frames_per_second: f64,
}

pub struct BeatSpectrogramTask {
    samples: Vec<f32>,
    sample_rate: f64,
}

impl Task for BeatSpectrogramTask {
    type Output = BeatSpectrogram;
    type JsValue = JsBeatSpectrogram;

    fn compute(&mut self) -> Result<Self::Output> {
        with_analyzer(|analyzer| analyzer.beat_spectrogram(&self.samples, self.sample_rate))
            .map_err(analysis_error)
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(JsBeatSpectrogram {
            values: Float32Array::new(output.values),
            frames: output.frames as u32,
            mels: BEAT_SPECTROGRAM_MELS as u32,
            frames_per_second: BEAT_SPECTROGRAM_SAMPLE_RATE / BEAT_SPECTROGRAM_HOP as f64,
        })
    }
}

#[napi(ts_return_type = "Promise<JsBeatSpectrogram>")]
pub fn beat_spectrogram(
    samples: Float32Array,
    sample_rate: f64,
) -> Result<AsyncTask<BeatSpectrogramTask>> {
    if samples.is_empty() || !sample_rate.is_finite() || sample_rate < 1000.0 {
        return Err(Error::new(
            Status::InvalidArg,
            "audio samples and sample rate must be valid",
        ));
    }
    Ok(AsyncTask::new(BeatSpectrogramTask {
        samples: samples.as_ref().to_vec(),
        sample_rate,
    }))
}

#[napi(object)]
pub struct JsVocalSpectrogram {
    pub values: Float32Array,
    pub frames: u32,
    pub channels: u32,
    pub bins: u32,
    pub frames_per_second: f64,
}

pub struct VocalSpectrogramTask {
    channels: Vec<Vec<f32>>,
    sample_rate: f64,
}

impl Task for VocalSpectrogramTask {
    type Output = VocalSpectrogram;
    type JsValue = JsVocalSpectrogram;

    fn compute(&mut self) -> Result<Self::Output> {
        let channels: Vec<&[f32]> = self.channels.iter().map(Vec::as_slice).collect();
        with_analyzer(|analyzer| analyzer.vocal_spectrogram(&channels, self.sample_rate))
            .map_err(analysis_error)
    }

    fn resolve(&mut self, _env: Env, output: Self::Output) -> Result<Self::JsValue> {
        Ok(JsVocalSpectrogram {
            values: Float32Array::new(output.values),
            frames: output.frames as u32,
            channels: VOCAL_SPECTROGRAM_CHANNELS as u32,
            bins: VOCAL_SPECTROGRAM_BINS as u32,
            frames_per_second: VOCAL_SPECTROGRAM_SAMPLE_RATE / VOCAL_SPECTROGRAM_HOP as f64,
        })
    }
}

#[napi(ts_return_type = "Promise<JsVocalSpectrogram>")]
pub fn vocal_spectrogram(
    channels: Vec<Float32Array>,
    sample_rate: f64,
) -> Result<AsyncTask<VocalSpectrogramTask>> {
    if channels.iter().any(|channel| channel.as_ref().is_empty())
        || !sample_rate.is_finite()
        || sample_rate < 1000.0
    {
        return Err(Error::new(
            Status::InvalidArg,
            "channels and sample rate must be valid",
        ));
    }
    Ok(AsyncTask::new(VocalSpectrogramTask {
        channels: channels
            .iter()
            .map(|channel| channel.as_ref().to_vec())
            .collect(),
        sample_rate,
    }))
}

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
    pub handoff_fraction: Option<f64>,
    pub bed_position: Option<f64>,
    pub bass_swap_fraction: Option<f64>,
    pub filter_sweep: Option<f64>,
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
        handoff_fraction: plan.handoff_fraction,
        bed_position: plan.bed_position,
        bass_swap_fraction: plan.bass_swap_fraction,
        filter_sweep: plan.filter_sweep,
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
