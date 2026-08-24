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

uniffi::setup_scaffolding!("orchard_earmark");

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
    if pcm.len() % (SAMPLE_BYTES * channels) != 0 {
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
                .chunks_exact(SAMPLE_BYTES)
                .map(|sample| f32::from_le_bytes(sample.try_into().expect("four bytes")))
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
