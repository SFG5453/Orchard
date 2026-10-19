/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! C ABI for the Apple Music player page: stream songs in, mix, splice 1x clips over the
//! render windows, and read the plan and render back. Single-threaded; the page owns all calls.
use orchard_adaptive_mix::{models::Models, prepare, song::{self, Song, SongBuilder, Windows}};
use std::cell::RefCell;

thread_local! {
    static RESULT_JSON: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
    static RESULT_PCM: RefCell<Vec<f32>> = const { RefCell::new(Vec::new()) };
}

fn set_result(value: serde_json::Value, pcm: Vec<f32>) {
    RESULT_JSON.with(|json| *json.borrow_mut() = value.to_string().into_bytes());
    RESULT_PCM.with(|samples| *samples.borrow_mut() = pcm);
}

#[unsafe(no_mangle)]
pub extern "C" fn mk_alloc(bytes: usize) -> *mut u8 {
    let mut buffer = Vec::<u8>::with_capacity(bytes);
    let pointer = buffer.as_mut_ptr();
    std::mem::forget(buffer);
    pointer
}

/// # Safety
/// `pointer` came from `mk_alloc(bytes)`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_free(pointer: *mut u8, bytes: usize) {
    drop(unsafe { Vec::from_raw_parts(pointer, 0, bytes) });
}

#[unsafe(no_mangle)]
pub extern "C" fn mk_builder(native_rate: u32, head: i32, tail: i32) -> *mut SongBuilder {
    match SongBuilder::new(native_rate, Windows { head: head != 0, tail: tail != 0 }) {
        Ok(builder) => Box::into_raw(Box::new(builder)),
        Err(_) => std::ptr::null_mut(),
    }
}

/// # Safety
/// `builder` is live and `samples` holds `len` floats.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_push(builder: *mut SongBuilder, samples: *const f32, len: usize) -> i32 {
    let samples = unsafe { std::slice::from_raw_parts(samples, len) };
    match unsafe { &mut *builder }.push(samples) { Ok(()) => 0, Err(_) => 1 }
}

/// Consumes the builder. Null on failure; the error lands in the result JSON.
///
/// # Safety
/// `builder` came from `mk_builder` and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_finish(builder: *mut SongBuilder) -> *mut Song {
    match unsafe { Box::from_raw(builder) }.finish() {
        Ok(song) => Box::into_raw(Box::new(song)),
        Err(e) => { set_result(serde_json::json!({"error": e}), Vec::new()); std::ptr::null_mut() }
    }
}

/// Drops a builder that will not be finished.
///
/// # Safety
/// `builder` came from `mk_builder` and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_drop_builder(builder: *mut SongBuilder) {
    drop(unsafe { Box::from_raw(builder) });
}

/// # Safety
/// `song` came from `mk_finish` and is not used again.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_drop_song(song: *mut Song) {
    drop(unsafe { Box::from_raw(song) });
}

/// # Safety
/// `song` is live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_song_duration(song: *const Song) -> f64 {
    unsafe { &*song }.track.duration
}

/// Runs prepare::mix; the page calls it through WebAssembly.promising so model calls can await.
///
/// # Safety
/// `request` holds `len` bytes of JSON and both songs are live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_mix(request: *const u8, len: usize, outgoing: *const Song, incoming: *const Song) -> i32 {
    let run = || -> Result<(serde_json::Value, Vec<f32>), String> {
        let text = unsafe { std::slice::from_raw_parts(request, len) };
        let request: prepare::MixRequest = serde_json::from_slice(text).map_err(|e| e.to_string())?;
        let mut models = Models::new()?;
        let (prepared, pcm) = prepare::mix(&mut models, &request, unsafe { &*outgoing }, unsafe { &*incoming })?;
        Ok((serde_json::to_value(prepared).map_err(|e| e.to_string())?, pcm))
    };
    match run() {
        Ok((value, pcm)) => { set_result(value, pcm); 0 }
        Err(e) => { set_result(serde_json::json!({"error": e}), Vec::new()); 1 }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn mk_result_json() -> *const u8 { RESULT_JSON.with(|json| json.borrow().as_ptr()) }
#[unsafe(no_mangle)]
pub extern "C" fn mk_result_json_len() -> usize { RESULT_JSON.with(|json| json.borrow().len()) }
#[unsafe(no_mangle)]
pub extern "C" fn mk_result_pcm() -> *const f32 { RESULT_PCM.with(|pcm| pcm.borrow().as_ptr()) }
#[unsafe(no_mangle)]
pub extern "C" fn mk_result_pcm_len() -> usize { RESULT_PCM.with(|pcm| pcm.borrow().len()) }

fn mono(audio: &earmark::AudioBuffer, step: usize) -> Vec<f32> {
    let (left, right) = (audio.channel(0), audio.channel(audio.channel_count().min(2) - 1));
    (0..left.len() / step)
        .map(|i| (i * step..i * step + step).map(|j| left[j] + right[j]).sum::<f32>() / (2 * step) as f32)
        .collect()
}

fn best_lag(window: &[f32], clip: &[f32], nominal: isize, search: isize) -> Option<(isize, f32)> {
    let clip_energy: f32 = clip.iter().map(|v| v * v).sum();
    let mut best: Option<(isize, f32)> = None;
    for lag in -search..=search {
        let start = nominal + lag;
        if start < 0 || start as usize + clip.len() > window.len() { continue; }
        let part = &window[start as usize..start as usize + clip.len()];
        let (dot, energy) = part.iter().zip(clip).fold((0.0f32, 0.0f32), |(d, e), (a, b)| (d + a * b, e + a * a));
        let score = dot / (energy * clip_energy).sqrt().max(1e-12);
        if best.is_none_or(|(_, s)| score > s) { best = Some((lag, score)); }
    }
    best
}

/// Where `clip` sits in `window` near frame `nominal`: within 2 s at 1.5 kHz, then 12 kHz, then exact.
fn align(window: &earmark::AudioBuffer, clip: &earmark::AudioBuffer, nominal: isize) -> Result<(usize, f32), String> {
    let (coarse, _) = best_lag(&mono(window, 32), &mono(clip, 32), nominal / 32, 3000).ok_or("no overlap with the window")?;
    let base = (nominal / 32 + coarse) * 32;
    let (middle, _) = best_lag(&mono(window, 4), &mono(clip, 4), base / 4, 24).ok_or("no overlap with the window")?;
    let base = (base / 4 + middle) * 4;
    let (fine, score) = best_lag(&mono(window, 1), &mono(clip, 1), base, 8).ok_or("no exact overlap")?;
    Ok(((base + fine) as usize, score))
}

/// Splices full-band audio captured at 1x over a render window. `start` is the clip's
/// approximate song time; the exact spot comes from correlating against the window.
/// Returns the correction in seconds, or NaN when the clip does not fit.
///
/// # Safety
/// `song` is live and `samples` holds `len` interleaved stereo floats.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mk_patch(song: *mut Song, tail: i32, start: f64, samples: *const f32, len: usize, rate: u32) -> f64 {
    let run = || -> Result<f64, String> {
        let song = unsafe { &mut *song };
        let data = unsafe { std::slice::from_raw_parts(samples, len) };
        let clip = earmark::AudioBuffer::from_interleaved(data, 2, rate).map_err(|e| e.to_string())?;
        let clip = earmark::audio::resample::resample(&clip, song::RENDER_RATE).map_err(|e| e.to_string())?;
        // Resampler edges ring; keep the clip's settled middle.
        let edge = (0.02 * song::RENDER_RATE as f64) as usize;
        if clip.frames() < 4 * edge { return Err("clip too short".into()); }
        let clip = clip.slice(edge, clip.frames() - 2 * edge);
        let offset = if tail != 0 { song.tail_offset } else { 0.0 };
        let window = if tail != 0 { &mut song.tail } else { &mut song.head };
        let nominal = ((start - offset) * song::RENDER_RATE as f64) as isize + edge as isize;
        let (at, score) = align(window, &clip, nominal)?;
        if score < 0.8 { return Err(format!("clip does not match the window (r={score:.3})")); }
        let fade = (0.01 * song::RENDER_RATE as f64) as usize;
        let frames = clip.frames();
        for c in 0..window.channel_count() {
            let source = clip.channel(c.min(clip.channel_count() - 1)).to_vec();
            let target = &mut window.planar_mut()[c][at..at + frames];
            for (i, (t, s)) in target.iter_mut().zip(&source).enumerate() {
                let weight = (i.min(frames - 1 - i) as f32 / fade as f32).min(1.0);
                *t = *t * (1.0 - weight) + s * weight;
            }
        }
        Ok((at as f64 - nominal as f64) / song::RENDER_RATE as f64)
    };
    match run() {
        Ok(correction) => correction,
        Err(e) => { set_result(serde_json::json!({"error": e}), Vec::new()); f64::NAN }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn mk_render_rate() -> u32 { song::RENDER_RATE }
