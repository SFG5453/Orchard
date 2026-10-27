/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Prepare a finite stereo overlap and its native-rate incoming handoff.
//! `mix` is the whole decision and render path on every platform; hosts only decode.
use crate::{analysis, apple, evidence, lock, measured::{self, Measured}, models::Models, planner, song::{self, Song}};
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
    /// Apple Music's audio analysis of each song, sent only for songs played from Apple Music.
    /// It replaces the beat model's bar phase and the model vocals.
    #[serde(default)]
    pub outgoing_apple: Option<serde_json::Value>,
    #[serde(default)]
    pub incoming_apple: Option<serde_json::Value>,
    /// Evidence the host kept from an earlier mix of each song (`measured::Measured`).
    #[serde(default)]
    pub outgoing_measured: Option<serde_json::Value>,
    #[serde(default)]
    pub incoming_measured: Option<serde_json::Value>,
}
fn default_fade() -> f64 { 6.0 }

/// Evidence measured during a mix, which the host keeps by track id. Hosts receive it with
/// every response, a refusal or error included.
#[derive(Default)]
pub struct Measurements {
    pub outgoing: Option<serde_json::Value>,
    pub incoming: Option<serde_json::Value>,
}

impl Measurements {
    pub fn attach(self, response: &mut serde_json::Value) {
        if let Some(value) = self.outgoing { response["outgoingMeasured"] = value; }
        if let Some(value) = self.incoming { response["incomingMeasured"] = value; }
    }
}

impl MixRequest {
    /// False when Apple's analysis covers the beat grid and vocals of both songs.
    pub fn needs_models(&self) -> bool {
        let covered = |analysis: &Option<serde_json::Value>| analysis.as_ref()
            .is_some_and(|analysis| apple::grid(analysis).is_some() && analysis["vocalActivity"].is_array());
        !(covered(&self.outgoing_apple) && covered(&self.incoming_apple))
    }
}

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
    /// Also return unprocessed clips around both splice points (see [`References`]).
    #[serde(default)]
    pub references: bool,
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
    /// The shared planner's technique, e.g. `bass_swap`, `drop_in` or `beatless_bridge`.
    pub technique: String,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub references: Option<References>,
}

/// Mono clips of the analysed audio at `song::RENDER_RATE`, outgoing first, for a host that
/// plays another copy of each song and finds the splice points in it by correlation.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct References {
    /// Song time of each clip's first sample.
    pub outgoing_start: f64,
    pub outgoing_frames: usize,
    pub incoming_start: f64,
    pub incoming_frames: usize,
}

/// Seconds of outgoing audio kept around the mix start, and incoming audio from before its cue
/// to past the handoff.
#[cfg(feature = "desktop")]
const REFERENCE_LEAD: f64 = 6.0;
#[cfg(feature = "desktop")]
const REFERENCE_TAIL: f64 = 1.0;
#[cfg(feature = "desktop")]
const INCOMING_LEAD: f64 = 5.0;
#[cfg(feature = "desktop")]
const INCOMING_TAIL: f64 = 2.0;

#[cfg(feature = "desktop")]
fn mono_span(window: &AudioBuffer, offset: f64, from: f64, to: f64) -> (f64, Vec<f32>) {
    let rate = f64::from(song::RENDER_RATE);
    let start = (((from - offset) * rate).max(0.0) as usize).min(window.frames());
    let end = (((to - offset) * rate).max(0.0) as usize).clamp(start, window.frames());
    let channels = window.channel_count();
    let samples = (start..end)
        .map(|i| (0..channels).map(|c| window.channel(c)[i]).sum::<f32>() / channels as f32)
        .collect();
    (offset + start as f64 / rate, samples)
}

#[cfg(feature = "desktop")]
fn references(prepared: &mut Prepared, outgoing: &Song, incoming: &Song) -> Vec<f32> {
    let start = prepared.outgoing_start;
    let (outgoing_start, mut samples) =
        mono_span(&outgoing.tail, outgoing.tail_offset, start - REFERENCE_LEAD, start + REFERENCE_TAIL);
    let cue = prepared.incoming_cue;
    let (incoming_start, clip) =
        mono_span(&incoming.head, 0.0, cue - INCOMING_LEAD, prepared.incoming_resume + INCOMING_TAIL);
    prepared.references = Some(References {
        outgoing_start, outgoing_frames: samples.len(), incoming_start, incoming_frames: clip.len(),
    });
    samples.extend(clip);
    samples
}

/// Decodes both loopback streams, then mixes them. Returns stereo f32 at 48 kHz, then the
/// reference clips when the request asks for them.
#[cfg(feature = "desktop")]
pub fn prepare(models: Option<&mut Models>, request: Request, measured: &mut Measurements)
    -> Result<(Prepared, Vec<f32>, Vec<f32>), String> {
    validate(&request.mix)?;
    let tail = song::Windows { head: false, tail: true };
    let head = song::Windows { head: true, tail: false };
    let outgoing = crate::decode::song(&request.outgoing_url, tail)?;
    let incoming = crate::decode::song(&request.incoming_url, head)?;
    let (mut prepared, samples) = mix(models, &request.mix, &outgoing, &incoming, measured)?;
    let clips = if request.references { references(&mut prepared, &outgoing, &incoming) } else { Vec::new() };
    if !request.source_rate { return Ok((prepared, samples, clips)); }
    let samples = resample_to(&mut prepared, samples, outgoing.rate)?;
    Ok((prepared, samples, clips))
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

/// Whole-track planner evidence for one deck: V2's analysis, bar phase from Apple's grid or the
/// beat model, bass weight, and local chroma over `span`, the window a mix can use. `stored` is
/// the host's evidence for this song; the second value is fresh evidence for the host to keep.
pub fn deck(models: Option<&mut Models>, song: &Song, span: (f64, f64), apple: Option<&serde_json::Value>,
            stored: Option<&serde_json::Value>) -> Result<(serde_json::Value, Option<serde_json::Value>), String> {
    // ORCHARD_MIX_BEATS=earmark plans from Earmark's own beat grid, for A/B listening.
    let earmark_only = std::env::var("ORCHARD_MIX_BEATS").is_ok_and(|v| v == "earmark");
    let track = &song.track;
    let apple_grid = apple.and_then(apple::grid);
    // Only the beat model is slow enough to keep; Apple's grid changes with its analysis.
    let audio = (!earmark_only && apple_grid.is_none() && models.is_some()).then(|| measured::audio_key(track));
    let (entry, fresh) = match audio.zip(stored).and_then(|(audio, stored)| Measured::from_json(stored, audio)) {
        Some(entry) => (entry, false),
        None => {
            let grids: Vec<_> = match (apple_grid, models) {
                _ if earmark_only => Vec::new(),
                // Apple's grid covers the whole track, so it votes from offset zero.
                (Some(grid), _) => vec![(Ok(grid), 0.0)],
                (None, Some(models)) => track.beat_windows.iter().map(|(audio, offset)| (models.beats(audio), *offset)).collect(),
                (None, None) => Vec::new(),
            };
            let (mut deck, chroma) = analysis::measure(track, &grids)?;
            deck["bassCurve"] = evidence::bass_curve(&track.mono, song::ANALYSIS_RATE);
            deck["subBassRatio"] = evidence::sub_bass_ratio(&track.mono, song::ANALYSIS_RATE).into();
            (Measured { audio: audio.unwrap_or_default(), deck, chroma }, audio.is_some())
        }
    };
    let mut value = entry.deck.clone();
    value["chromaCurve"] = evidence::chroma_curve(&entry.chroma, span);
    Ok((value, fresh.then(|| entry.to_json())))
}

/// Plans and renders the pair. Returns the plan and the overlap as interleaved stereo f32
/// at `song::RENDER_RATE`.
/// Vocal share per vocal model frame across a render window starting at track time `start`.
fn vocal_frames(models: Option<&mut Models>, apple: Option<&serde_json::Value>, window: &AudioBuffer, start: f64)
    -> Result<Vec<f64>, String> {
    if let Some(frames) = apple.and_then(|analysis| apple::vocal_frames(analysis, start, window.duration())) {
        return Ok(frames);
    }
    evidence::vocal_frames(models.ok_or("Vocal model is unavailable")?, window)
}

/// `models` may be None when [`MixRequest::needs_models`] is false.
/// `measured` collects fresh evidence as soon as it exists, so a refused pair still returns it.
pub fn mix(mut models: Option<&mut Models>, request: &MixRequest, outgoing_song: &Song, incoming_song: &Song,
           measured: &mut Measurements) -> Result<(Prepared, Vec<f32>), String> {
    validate(request)?;
    if outgoing_song.tail.is_empty() || incoming_song.head.is_empty() {
        return Err("Songs were decoded without render windows".into());
    }
    // Planning sees V2's whole-track evidence at 44.1 kHz.
    let (outgoing_apple, incoming_apple) = (request.outgoing_apple.as_ref(), request.incoming_apple.as_ref());
    let (mut current, fresh) = deck(models.as_deref_mut(), outgoing_song, (outgoing_song.tail_offset, f64::INFINITY),
        outgoing_apple, request.outgoing_measured.as_ref())?;
    measured.outgoing = fresh;
    let (mut next, fresh) = deck(models.as_deref_mut(), incoming_song, (0.0, incoming_song.head.duration()),
        incoming_apple, request.incoming_measured.as_ref())?;
    measured.incoming = fresh;
    let decoded = incoming_song.track.duration;
    // The render windows share the analysis timeline and match playback sample for sample.
    let offset = outgoing_song.tail_offset;
    let outgoing = outgoing_song.tail.clone();
    let incoming = &incoming_song.head;
    // Measure vocals where a mix can happen so the planner hears real voices.
    // A missing model leaves the frame estimate in charge.
    let outgoing_vocals = vocal_frames(models.as_deref_mut(), outgoing_apple, &outgoing, offset);
    let warning = outgoing_vocals.as_ref().err().cloned();
    if let Ok(frames) = &outgoing_vocals {
        current["vocalCurve"] = evidence::vocal_curve(frames, offset);
        if let Ok(frames) = vocal_frames(models.as_deref_mut(), incoming_apple, incoming, 0.0) {
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
    let planner::Planned { mut plan, looped, technique } = planner::plan(&mut engine, &outgoing, incoming, &input, offset)?;
    let beat_loop = looped.as_ref().map(|(beat_loop, _)| *beat_loop);
    let outgoing = looped.map_or(outgoing, |(_, audio)| audio);
    // Line the kicks up on the audio itself; beat grids sit tens of milliseconds off them.
    // A bridge has no beat on either side to line up.
    let kick_lock = plan.strategy.is_beatmatched().then(|| lock::kick_lock(&outgoing, incoming, &plan))
        .flatten().filter(|shift| {
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
        technique,
        target_bpm: if plan.strategy.is_beatmatched() { f64::from(plan.target_bpm) } else { 0.0 },
        vocal_mask: masked,
        loop_beats: beat_loop.map_or(0, |beat_loop| beat_loop.beats),
        kick_lock: kick_lock.map_or(0.0, |shift| (shift * 1000.0).round()),
        warning,
        rate: song::RENDER_RATE,
        incoming_rate: incoming_song.rate,
        references: None,
    }, samples))
}
