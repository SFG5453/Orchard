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

//! UniFFI bindings over `orchard-transition-core`, the same core the desktop's
//! N-API addon drives. Everything here is marshalling: a rule that lives in this
//! file rather than in the core is a rule only Android obeys.
//!
//! Calls are synchronous and will occupy the calling thread for seconds. Android
//! renders on its own executor well ahead of the seam, so a blocking call is
//! what the caller already wanted; anything else would put a second scheduler
//! between the render and its deadline.
//!
//! All times are on the *slice's* timeline. The caller decodes a region around
//! the anchor and hands that over, so a cue at 191 s of a track sliced from
//! 175 s arrives here as 16 s -- and every time coming back out is on the same
//! timeline, which is what the caller adds its slice offset to.

use orchard_transition_core as core;
use std::sync::{Mutex, OnceLock};

use earmark::analysis::{
    BEAT_SPECTROGRAM_HOP, BEAT_SPECTROGRAM_MELS, BEAT_SPECTROGRAM_SAMPLE_RATE,
    VOCAL_SPECTROGRAM_BINS, VOCAL_SPECTROGRAM_CHANNELS, VOCAL_SPECTROGRAM_FFT,
    VOCAL_SPECTROGRAM_HOP, VOCAL_SPECTROGRAM_SAMPLE_RATE, WholeTrackAnalysis, WholeTrackAnalyzer,
};
use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JFloatArray};
use jni::sys::{jdouble, jfloatArray, jint, jstring};
use serde_json::json;

uniffi::setup_scaffolding!("orchard_earmark");

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

fn read_floats(env: &mut JNIEnv<'_>, input: &JFloatArray<'_>) -> jni::errors::Result<Vec<f32>> {
    let mut values = vec![0.0; env.get_array_length(input)? as usize];
    env.get_float_array_region(input, 0, &mut values)?;
    Ok(values)
}

fn float_array(env: &mut JNIEnv<'_>, values: &[f32]) -> jfloatArray {
    let Ok(output) = env.new_float_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&output, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    output.into_raw()
}

fn empty_float_array(env: &mut JNIEnv<'_>) -> jfloatArray {
    float_array(env, &[])
}

fn mobile_analysis_json(result: &WholeTrackAnalysis) -> String {
    let curve = |points: &[earmark::analysis::EnergyPoint]| {
        points
            .iter()
            .map(|point| json!({ "t": point.time, "e": point.energy }))
            .collect::<Vec<_>>()
    };
    let cues = |points: &[earmark::analysis::MixCuePoint]| {
        points
            .iter()
            .map(|point| json!({ "t": point.time, "s": point.score, "y": point.kind }))
            .collect::<Vec<_>>()
    };
    json!({
        "duration": result.duration,
        "bpm": result.bpm,
        "beatInterval": result.beat_interval,
        "firstBeat": result.first_beat,
        "beatConfidence": result.beat_confidence,
        "key": result.key,
        "keyConfidence": result.key_confidence,
        "audibleStartTime": result.audible_start_time,
        "pickupTime": result.pickup_time,
        "introEndTime": result.intro_end_time,
        "outroStartTime": result.outro_start_time,
        "contentEndTime": result.content_end_time,
        "mixInTime": result.mix_in_time,
        "mixOutTime": result.mix_out_time,
        "vocalProbability": result.vocal_probability,
        "instrumentalProbability": result.instrumental_probability,
        "downbeats": result.downbeats,
        "phraseBoundaries": result.phrase_boundaries,
        "vocalActivityMask": result.vocal_activity_mask,
        "energyCurve": curve(&result.energy_curve),
        "lowEnergyCurve": curve(&result.low_energy_curve),
        "mixInCandidates": cues(&result.mix_in_candidates),
        "mixOutCandidates": cues(&result.mix_out_candidates),
    })
    .to_string()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_TrackFeatures_nativeAnalyze(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    samples: JFloatArray<'_>,
    sample_rate: jdouble,
    duration: jdouble,
) -> jstring {
    let json = read_floats(&mut env, &samples)
        .ok()
        .and_then(|samples| {
            with_analyzer(|analyzer| analyzer.analyze(&samples, sample_rate, duration)).ok()
        })
        .map_or_else(|| "{}".to_owned(), |result| mobile_analysis_json(&result));
    env.new_string(json)
        .map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_TrackFeatures_nativeSampleRate(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jdouble {
    11_025.0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeCompute(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    samples: JFloatArray<'_>,
    sample_rate: jdouble,
) -> jfloatArray {
    let Some(spectrogram) = read_floats(&mut env, &samples).ok().and_then(|samples| {
        with_analyzer(|analyzer| analyzer.beat_spectrogram(&samples, sample_rate)).ok()
    }) else {
        return empty_float_array(&mut env);
    };
    float_array(&mut env, &spectrogram.values)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeMelCount(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    BEAT_SPECTROGRAM_MELS as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeSampleRate(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jdouble {
    BEAT_SPECTROGRAM_SAMPLE_RATE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeHop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    BEAT_SPECTROGRAM_HOP as jint
}

fn vocal_window(
    env: &mut JNIEnv<'_>,
    left: &JFloatArray<'_>,
    right: &JFloatArray<'_>,
    offset: jint,
    length: jint,
    sample_rate: jdouble,
) -> Option<earmark::analysis::VocalSpectrogram> {
    if offset < 0 || length <= 0 {
        return None;
    }
    let left = read_floats(env, left).ok()?;
    let right = read_floats(env, right).ok()?;
    let end = offset as usize + length as usize;
    if end > left.len() || end > right.len() {
        return None;
    }
    with_analyzer(|analyzer| {
        analyzer.vocal_spectrogram(
            &[&left[offset as usize..end], &right[offset as usize..end]],
            sample_rate,
        )
    })
    .ok()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeCompute(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    left: JFloatArray<'_>,
    right: JFloatArray<'_>,
    offset: jint,
    length: jint,
    sample_rate: jdouble,
) -> jfloatArray {
    let Some(spectrogram) = vocal_window(&mut env, &left, &right, offset, length, sample_rate)
    else {
        return empty_float_array(&mut env);
    };
    float_array(&mut env, &spectrogram.values)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeComputeInto(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    left: JFloatArray<'_>,
    right: JFloatArray<'_>,
    offset: jint,
    length: jint,
    sample_rate: jdouble,
    destination: JByteBuffer<'_>,
    frame_stride: jint,
) -> jint {
    if frame_stride <= 0 {
        return 0;
    }
    let Some(spectrogram) = vocal_window(&mut env, &left, &right, offset, length, sample_rate)
    else {
        return 0;
    };
    let stride = frame_stride as usize;
    if spectrogram.frames == 0 || spectrogram.frames > stride {
        return 0;
    }
    let required = VOCAL_SPECTROGRAM_CHANNELS * VOCAL_SPECTROGRAM_BINS * stride;
    let Ok(capacity) = env.get_direct_buffer_capacity(&destination) else {
        return 0;
    };
    let Ok(address) = env.get_direct_buffer_address(&destination) else {
        return 0;
    };
    if capacity < required * size_of::<f32>()
        || !(address as usize).is_multiple_of(align_of::<f32>())
    {
        return 0;
    }
    // JNI guarantees the direct-buffer address remains valid for this call. Capacity and
    // alignment were checked above, and no Java code can run concurrently on this synchronized
    // model input buffer, so this is the sole unsafe operation in the analyzer migration.
    let output = unsafe { std::slice::from_raw_parts_mut(address.cast::<f32>(), required) };
    output.fill(0.0);
    for channel in 0..VOCAL_SPECTROGRAM_CHANNELS {
        for bin in 0..VOCAL_SPECTROGRAM_BINS {
            let row = channel * VOCAL_SPECTROGRAM_BINS + bin;
            let source =
                &spectrogram.values[row * spectrogram.frames..(row + 1) * spectrogram.frames];
            output[row * stride..row * stride + spectrogram.frames].copy_from_slice(source);
        }
    }
    spectrogram.frames as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeBins(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    VOCAL_SPECTROGRAM_BINS as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeSampleRate(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jdouble {
    VOCAL_SPECTROGRAM_SAMPLE_RATE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeHop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    VOCAL_SPECTROGRAM_HOP as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeFftSize(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    VOCAL_SPECTROGRAM_FFT as jint
}

/// Structurally invalid input: the caller built the request wrong. Distinct from a refusal,
/// which is a verdict on the audio and arrives as [`TransitionResult::rendered`] false.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TransitionError {
    #[error("{reason}")]
    InvalidInput { reason: String },
}

impl TransitionError {
    fn invalid(reason: String) -> Self {
        Self::InvalidInput { reason }
    }
}

/// One side of the transition: planar PCM plus the beat grid the caller already has.
#[derive(uniffi::Record)]
pub struct TransitionSource {
    /// Planar 32-bit float PCM, little-endian: all of channel 0, then all of channel 1, and so on.
    ///
    /// Bytes rather than a sequence of floats because UniFFI maps a sequence to a Kotlin
    /// `List<Float>`. A twenty-second stereo slice is 1.8 million samples, and boxing each one
    /// costs tens of megabytes of device heap to describe data that is about to be copied
    /// wholesale anyway. A `ByteArray` crosses as a primitive array.
    pub pcm: Vec<u8>,
    pub channel_count: u32,
    pub sample_rate: u32,
    pub bpm: f64,
    /// Beat times in seconds, relative to the start of `pcm`.
    pub beats: Vec<f64>,
    /// Downbeat times in seconds, relative to the start of `pcm`. Candidate placement and phrase
    /// alignment both key off these, so a grid without them scores blind on structure.
    pub downbeats: Vec<f64>,
}

impl TransitionSource {
    fn take(self, label: &str) -> Result<core::Source, TransitionError> {
        let source = core::Source {
            channels: planar(&self.pcm, self.channel_count, label)?,
            sample_rate: self.sample_rate,
            bpm: self.bpm as f32,
            beats: self.beats,
            downbeats: self.downbeats,
        };
        source.validate(label).map_err(TransitionError::invalid)?;
        Ok(source)
    }
}

/// Splits interleaved-by-channel little-endian bytes into the planar vectors the engine works on.
fn planar(pcm: &[u8], channel_count: u32, label: &str) -> Result<Vec<Vec<f32>>, TransitionError> {
    const SAMPLE_BYTES: usize = size_of::<f32>();

    let channels = channel_count as usize;
    if channels == 0 {
        return Err(TransitionError::invalid(format!(
            "{label} PCM has no channels"
        )));
    }
    if !pcm.len().is_multiple_of(SAMPLE_BYTES * channels) {
        return Err(TransitionError::invalid(format!(
            "{label} PCM is {} bytes, which is not a whole number of {channels}-channel frames",
            pcm.len()
        )));
    }

    let frames = pcm.len() / SAMPLE_BYTES / channels;
    Ok(pcm
        .chunks_exact(frames * SAMPLE_BYTES)
        .map(|channel| {
            channel
                .as_chunks::<SAMPLE_BYTES>()
                .0
                .iter()
                .map(|sample| f32::from_le_bytes(*sample))
                .collect()
        })
        .collect())
}

/// The inverse of [`planar`], for handing the finished overlap back.
fn interleave_channels(channels: &[Vec<f32>]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(channels.iter().map(Vec::len).sum::<usize>() * 4);
    for channel in channels {
        for sample in channel {
            bytes.extend_from_slice(&sample.to_le_bytes());
        }
    }
    bytes
}

/// Where a transition may begin and end on one track. Each window needs both of its bounds; a
/// half-specified window is treated as absent.
///
/// A window **replaces** the engine's own search region rather than narrowing it, so a cue
/// outside the default region is honoured. What it cannot do is bend the beat grid: every
/// reachable end sits a whole number of bars from a downbeat, so a window narrower than that
/// lattice legitimately matches nothing and the engine refuses rather than drifting to the
/// nearest fit. At 122 BPM the lattice is 1.97 s, so half a bar is the smallest window worth
/// asking for.
#[derive(uniffi::Record, Default)]
pub struct RegionConstraint {
    pub start_earliest: Option<f64>,
    pub start_latest: Option<f64>,
    pub end_earliest: Option<f64>,
    pub end_latest: Option<f64>,
}

impl RegionConstraint {
    fn take(value: Option<Self>) -> core::RegionWindow {
        let value = value.unwrap_or_default();
        core::RegionWindow {
            start_earliest: value.start_earliest,
            start_latest: value.start_latest,
            end_earliest: value.end_earliest,
            end_latest: value.end_latest,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TransitionOptions {
    pub outgoing: Option<RegionConstraint>,
    pub incoming: Option<RegionConstraint>,
    /// Restricts the transition length to a subset of the lengths the engine already allows.
    /// A list none of whose values it allows admits no transition at all, so the engine refuses;
    /// leave this absent to let it choose from the phrase structure it measured.
    pub beat_lengths: Option<Vec<u32>>,
    /// Per-instant depth for the outgoing filter ride, one value in `0..=1` per evenly spaced
    /// control point spanning the **outgoing PCM supplied**, first sample to last.
    ///
    /// It spans the slice rather than the transition because the caller has to measure before
    /// this call, and what the transition *is* is what this call decides. The engine crops the
    /// curve to whatever region it picks. Absent leaves the ride at full depth.
    pub duck_curve: Option<Vec<f64>>,
    /// Attach every scored candidate to the result's summary.
    pub diagnostics: bool,
}

/// Exact caller-selected plan, for the path that renders without planning.
#[derive(uniffi::Record)]
pub struct SelectedTransition {
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
    /// One of `equal_power_crossfade`, `beatmatched_crossfade`, `bass_swap`, `filtered_blend`,
    /// `short_fade`. Anything else is an error rather than a refusal.
    pub strategy: String,
    pub handoff_fraction: Option<f64>,
    pub bed_position: Option<f64>,
    pub bass_swap_fraction: Option<f64>,
    pub filter_sweep: Option<f64>,
}

impl SelectedTransition {
    fn take(self) -> Result<core::SelectedPlan, TransitionError> {
        let plan = core::SelectedPlan {
            outgoing_start: self.outgoing_start,
            incoming_start: self.incoming_start,
            duration: self.duration,
            beats: self.beats,
            outgoing_bpm: self.outgoing_bpm,
            incoming_bpm: self.incoming_bpm,
            target_bpm: self.target_bpm,
            outgoing_tempo_ratio: self.outgoing_tempo_ratio,
            incoming_tempo_ratio: self.incoming_tempo_ratio,
            outgoing_pitch_semitones: self.outgoing_pitch_semitones,
            incoming_pitch_semitones: self.incoming_pitch_semitones,
            strategy: self.strategy,
            handoff_fraction: self.handoff_fraction,
            bed_position: self.bed_position,
            bass_swap_fraction: self.bass_swap_fraction,
            filter_sweep: self.filter_sweep,
        };
        plan.validate().map_err(TransitionError::invalid)?;
        Ok(plan)
    }
}

/// A finished overlap, or the reason there is none.
///
/// `rendered` false with a `rejected` reason means "use the ordinary crossfade instead", which is
/// what the caller would have done anyway. Every time below is on the slice timeline.
#[derive(uniffi::Record)]
pub struct TransitionResult {
    pub rendered: bool,
    pub rejected: String,
    /// The finished overlap, in the same planar little-endian layout as [`TransitionSource::pcm`].
    pub pcm: Vec<u8>,
    pub channel_count: u32,
    pub sample_rate: u32,
    /// Length of the rendered overlap in seconds.
    pub duration: f64,
    pub beats: u32,
    /// One of the engine's strategy names, for logging.
    pub strategy: String,
    /// Where the engine actually placed the transition, which is what the caller schedules
    /// against -- not the anchor it asked for.
    pub outgoing_start: f64,
    pub incoming_start: f64,
    /// Where each track had reached when the transition ended.
    pub outgoing_resume: f64,
    pub incoming_resume: f64,
    pub outgoing_tempo_ratio: f64,
    pub incoming_tempo_ratio: f64,
    pub target_bpm: f64,
    pub summary: String,
}

impl TransitionResult {
    fn refused(reason: core::Refusal) -> Self {
        Self {
            rendered: false,
            rejected: reason,
            pcm: Vec::new(),
            channel_count: 0,
            sample_rate: 0,
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
            pcm: interleave_channels(&result.channels),
            channel_count: result.channels.len() as u32,
            sample_rate: result.sample_rate,
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

    fn from(outcome: Result<core::Rendered, core::Refusal>) -> Self {
        outcome.map_or_else(Self::refused, Self::rendered)
    }
}

/// Plans a transition inside the caller's constraints and renders it.
#[uniffi::export]
pub fn render_transition(
    outgoing: TransitionSource,
    incoming: TransitionSource,
    options: TransitionOptions,
) -> Result<TransitionResult, TransitionError> {
    let outgoing = outgoing.take("outgoing")?;
    let incoming = incoming.take("incoming")?;
    let request = core::TransitionRequest {
        outgoing: RegionConstraint::take(options.outgoing),
        incoming: RegionConstraint::take(options.incoming),
        beat_lengths: options.beat_lengths,
        // Omitted rather than passed empty when the model had no opinion, so the engine's own
        // "no curve means full depth" default applies.
        duck_curve: options.duck_curve.filter(|points| !points.is_empty()),
        diagnostics: options.diagnostics,
    };
    Ok(TransitionResult::from(core::render_constrained(
        &outgoing, &incoming, &request,
    )))
}

/// Renders the caller's exact transition without invoking earmark analysis, candidate
/// generation, scoring, or strategy selection.
#[uniffi::export]
pub fn render_planned_transition(
    outgoing: TransitionSource,
    incoming: TransitionSource,
    plan: SelectedTransition,
    duck_curve: Option<Vec<f64>>,
) -> Result<TransitionResult, TransitionError> {
    let plan = plan.take()?;
    let outgoing = outgoing.take("outgoing")?;
    let incoming = incoming.take("incoming")?;
    let duck_curve = duck_curve.filter(|points| !points.is_empty());
    Ok(TransitionResult::from(core::render_selected(
        &outgoing,
        &incoming,
        &plan,
        duck_curve.as_deref(),
    )))
}
