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

//! Prepare a finite stereo overlap and its native-rate incoming handoff.
//! `mix` is the whole decision and render path on every platform; hosts only decode.
use crate::{analysis, evidence, lock, models::Models, planner, song::{self, Song}};
use earmark::AudioBuffer;
use earmark::audio::resample::resample;
use earmark::dsp::filters::FilterKind;
use orchard_transition_core::depth_curve;
use serde::{Deserialize, Serialize};

/// What the host knows about the pair, besides the audio.
#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MixRequest {
    pub outgoing_duration: f64,
    #[serde(default)]
    pub incoming_duration: f64,
    #[serde(default)]
    pub current_track: serde_json::Value,
    #[serde(default)]
    pub next_track: serde_json::Value,
    #[serde(default = "default_fade")]
    pub fade_seconds: f64,
    #[serde(default)]
    pub album_sequential: bool,
    pub position: f64,
}
fn default_fade() -> f64 { 6.0 }

/// The desktop worker's request: the pair plus the loopback streams to decode.
#[cfg(feature = "desktop")]
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    pub outgoing_url: String,
    pub incoming_url: String,
    /// Render at the outgoing song's decoded rate, for a Connect target splicing into its own player.
    #[serde(default)]
    pub source_rate: bool,
    #[serde(flatten)]
    pub mix: MixRequest,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Prepared {
    pub outgoing_start: f64,
    pub incoming_start: f64,
    pub incoming_resume: f64,
    /// Where the host starts the incoming player so it reaches `incoming_resume` as the
    /// overlap ends. The host's splice clock runs one incoming second per output second.
    pub incoming_cue: f64,
    pub duration: f64,
    pub strategy: String,
    /// Shared overlap tempo; 0 when the tracks are not beatmatched.
    pub target_bpm: f64,
    pub vocal_mask: bool,
    /// Outgoing beats repeated under the mix; 0 without a loop.
    pub loop_beats: u32,
    /// Milliseconds the incoming cue moved so both kicks land together.
    pub kick_lock: f64,
    pub warning: Option<String>,
    /// Rate of the rendered samples.
    pub rate: u32,
    /// The incoming song's decoded rate, which `incoming_resume` frames are counted in.
    pub incoming_rate: u32,
}

/// Decodes both loopback streams, then mixes them. Returns stereo f32 at 48 kHz.
#[cfg(feature = "desktop")]
pub fn prepare(models: &mut Models, request: Request) -> Result<(Prepared, Vec<f32>), String> {
    validate(&request.mix)?;
    let tail = song::Windows { head: false, tail: true };
    let head = song::Windows { head: true, tail: false };
    let outgoing = crate::decode::song(&request.outgoing_url, tail)?;
    let incoming = crate::decode::song(&request.incoming_url, head)?;
    let (mut prepared, samples) = mix(models, &request.mix, &outgoing, &incoming)?;
    if !request.source_rate { return Ok((prepared, samples)); }
    let samples = resample_to(&mut prepared, samples, outgoing.rate)?;
    Ok((prepared, samples))
}

/// Resamples a finished render for a player running at `rate`; the plan itself stays at 48 kHz.
pub fn resample_to(prepared: &mut Prepared, samples: Vec<f32>, rate: u32) -> Result<Vec<f32>, String> {
    if rate == prepared.rate { return Ok(samples); }
    if !(8_000..=192_000).contains(&rate) { return Err("Unsupported playback rate".into()); }
    let audio = AudioBuffer::from_interleaved(&samples, 2, prepared.rate).map_err(|e| e.to_string())?;
    let samples = resample(&audio, rate).map_err(|e| e.to_string())?.to_interleaved();
    prepared.rate = rate;
    prepared.duration = samples.len() as f64 / 2.0 / f64::from(rate);
    Ok(samples)
}

fn validate(request: &MixRequest) -> Result<(), String> {
    if !request.outgoing_duration.is_finite()
        || request.outgoing_duration < 45.0
        || !request.position.is_finite()
        || request.position < 0.0
    {
        return Err("Adaptive mix requires a finite music track of at least 45 seconds".into());
    }
    Ok(())
}

/// Whole-track planner evidence for one deck: V2's analysis, Beat This bar phase, bass weight.
pub fn deck(models: &mut Models, song: &Song) -> Result<serde_json::Value, String> {
    // ORCHARD_MIX_BEATS=earmark plans from Earmark's own beat grid, for A/B listening.
    let earmark_only = std::env::var("ORCHARD_MIX_BEATS").is_ok_and(|v| v == "earmark");
    let track = &song.track;
    let grids: Vec<_> = if earmark_only { Vec::new() } else {
        track.beat_windows.iter().map(|(audio, offset)| (models.beats(audio), *offset)).collect()
    };
    let mut value = analysis::track(track, &grids)?;
    value["bassCurve"] = evidence::bass_curve(&track.mono, song::ANALYSIS_RATE);
    value["subBassRatio"] = evidence::sub_bass_ratio(&track.mono, song::ANALYSIS_RATE).into();
    Ok(value)
}

/// Plans and renders the pair. Returns the plan and the overlap as interleaved stereo f32
/// at `song::RENDER_RATE`.
pub fn mix(models: &mut Models, request: &MixRequest, outgoing_song: &Song, incoming_song: &Song)
    -> Result<(Prepared, Vec<f32>), String> {
    validate(request)?;
    if outgoing_song.tail.is_empty() || incoming_song.head.is_empty() {
        return Err("Songs were decoded without render windows".into());
    }
    // Planning sees V2's whole-track evidence at 44.1 kHz.
    let mut current = deck(models, outgoing_song)?;
    let mut next = deck(models, incoming_song)?;
    let decoded = incoming_song.track.duration;
    // The render windows share the analysis timeline and match playback sample for sample.
    let offset = outgoing_song.tail_offset;
    let outgoing = outgoing_song.tail.clone();
    let incoming = &incoming_song.head;
    // Measure vocals where a mix can happen so the planner hears real voices.
    // A missing model leaves the frame estimate in charge.
    let outgoing_vocals = evidence::vocal_frames(models, &outgoing);
    let warning = outgoing_vocals.as_ref().err().cloned();
    if let Ok(frames) = &outgoing_vocals {
        current["vocalCurve"] = evidence::vocal_curve(frames, offset);
        if let Ok(frames) = evidence::vocal_frames(models, incoming) {
            next["vocalCurve"] = evidence::vocal_curve(&frames, 0.0);
        }
    }
    let incoming_duration = if request.incoming_duration.is_finite() {
        request.incoming_duration.max(decoded)
    } else { decoded };
    let input = serde_json::json!({
        "analysis": current, "nextAnalysis": next,
        "duration": request.outgoing_duration, "nextDuration": incoming_duration,
        "currentTime": request.position, "mode": "smart", "tempoRamp": true,
        "fadeSeconds": request.fade_seconds, "minFadeSeconds": 1,
        "albumSequential": request.album_sequential,
        "currentTrack": request.current_track, "nextTrack": request.next_track,
        // Overlaps must fit the decoded render windows.
        "outgoingWindowStart": offset, "incomingWindowEnd": incoming.duration(),
    });
    let mut engine = planner::engine()?;
    let planner::Planned { mut plan, looped } = planner::plan(&mut engine, &outgoing, incoming, &input, offset)?;
    let beat_loop = looped.as_ref().map(|(beat_loop, _)| *beat_loop);
    let outgoing = looped.map_or(outgoing, |(_, audio)| audio);
    // Line the kicks up on the audio itself; beat grids sit tens of milliseconds off them.
    let kick_lock = lock::kick_lock(&outgoing, incoming, &plan).filter(|shift| {
        let start = plan.incoming_start + shift;
        let end = start + plan.duration * plan.incoming_tempo_ratio as f64;
        start >= 0.0 && end - plan.duration >= 0.0 && end <= incoming.duration()
    });
    if let Some(shift) = kick_lock {
        plan.incoming_start += shift;
    }
    // The low-pass rides the vocals that actually play, loop included.
    let outgoing_vocals = outgoing_vocals.map(|frames| match beat_loop {
        Some(beat_loop) => beat_loop.remap(&frames, evidence::VOCAL_FRAME, outgoing.duration()),
        None => frames,
    });
    let mut masked = false;
    if let Ok(frames) = &outgoing_vocals
        && plan.filters.outgoing.iter().any(|filter| filter.kind == FilterKind::LowPass)
        && let Some(curve) = depth_curve(
            evidence::crop(frames, plan.outgoing_start, plan.outgoing_end()), 0.0, 1.0)
    {
        for filter in &mut plan.filters.outgoing {
            if filter.kind == FilterKind::LowPass {
                filter.depth = Some(curve.clone());
            }
        }
        masked = true;
    }
    let rendered = engine
        .render(&outgoing, incoming, &plan)
        .map_err(|e| e.to_string())?;
    let audio = rendered.audio;
    let samples = audio.to_interleaved();
    if samples.iter().any(|sample| !sample.is_finite()) {
        return Err("Rendered overlap contains nonfinite samples".into());
    }
    let duration = audio.frames() as f64 / song::RENDER_RATE as f64;
    // A slowed incoming side consumes less than the overlap; the planner keeps it cueable.
    let incoming_cue = rendered.incoming_resume - duration;
    if incoming_cue < -1e-3 {
        return Err("Transition hands off before the incoming track can be cued".into());
    }
    Ok((Prepared {
        outgoing_start: offset + plan.outgoing_start,
        incoming_start: plan.incoming_start,
        incoming_resume: rendered.incoming_resume,
        incoming_cue: incoming_cue.max(0.0),
        duration,
        strategy: plan.strategy.describe().to_owned(),
        target_bpm: if plan.beats > 0 { f64::from(plan.target_bpm) } else { 0.0 },
        vocal_mask: masked,
        loop_beats: beat_loop.map_or(0, |beat_loop| beat_loop.beats),
        kick_lock: kick_lock.map_or(0.0, |shift| (shift * 1000.0).round()),
        warning,
        rate: song::RENDER_RATE,
        incoming_rate: incoming_song.rate,
    }, samples))
}
