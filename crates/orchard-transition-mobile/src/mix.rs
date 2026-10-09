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

//! JNI for `MixNative`: the desktop adaptive-mix worker's code, called in-process.
//! Kotlin decodes and plays; analysis, Best Mix, the QuickJS planner and the render are
//! `orchard-adaptive-mix`, byte for byte the code the desktop worker runs.
//!
//! Handles are boxed Rust values owned by Kotlin, which frees each exactly once.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};
use orchard_adaptive_mix::{best_mix, models::Models, prepare, song::{Song, SongBuilder, Windows}};
use serde_json::{Value, json};
use std::sync::OnceLock;

fn error_json(message: impl std::fmt::Display) -> String {
    json!({ "error": message.to_string() }).to_string()
}

fn string(env: &mut JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value).map_or(std::ptr::null_mut(), |s| s.into_raw())
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    env.get_string(value).map(Into::into).map_err(|e| e.to_string())
}

/// # Safety
/// `handle` must come from the matching `Box::into_raw` and still be live.
unsafe fn borrow<'a, T>(handle: jlong) -> Option<&'a mut T> {
    unsafe { (handle as *mut T).as_mut() }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeSongBegin(
    _env: JNIEnv<'_>, _class: JClass<'_>, rate: jint, head: jboolean, tail: jboolean,
) -> jlong {
    SongBuilder::new(rate.max(0) as u32, Windows { head: head != 0, tail: tail != 0 })
        .map_or(0, |builder| Box::into_raw(Box::new(builder)) as jlong)
}

/// Interleaved stereo at the rate given to `nativeSongBegin`. False drops nothing; the
/// caller frees the builder and gives up on the song.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeSongPush(
    env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong, samples: JFloatArray<'_>, count: jint,
) -> jboolean {
    let Some(builder) = (unsafe { borrow::<SongBuilder>(handle) }) else { return 0 };
    let length = env.get_array_length(&samples).unwrap_or(0);
    if count < 0 || count > length {
        return 0;
    }
    let mut values = vec![0.0f32; count as usize];
    if env.get_float_array_region(&samples, 0, &mut values).is_err() {
        return 0;
    }
    builder.push(&values).is_ok() as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeBuilderFree(
    _env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut SongBuilder) });
    }
}

/// Consumes the builder. Returns a song handle, or 0 when the decode was unusable.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeSongFinish(
    _env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong,
) -> jlong {
    if handle == 0 {
        return 0;
    }
    let builder = unsafe { Box::from_raw(handle as *mut SongBuilder) };
    builder.finish().map_or(0, |song| Box::into_raw(Box::new(song)) as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeSongDuration(
    _env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong,
) -> jdouble {
    unsafe { borrow::<Song>(handle) }.map_or(0.0, |song| song.track.duration)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeSongFree(
    _env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Song) });
    }
}

/// Desktop's `bestMixAnalyze` response for a decoded song, or `{"error":…}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeBestMixAnalyze(
    mut env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong, duration: jdouble,
) -> jstring {
    let output = match unsafe { borrow::<Song>(handle) } {
        Some(song) => best_mix::analyze_track(&song.track, duration)
            .map_or_else(error_json, |value| value.to_string()),
        None => error_json("Missing song"),
    };
    string(&mut env, &output)
}

/// Desktop's `bestMixSortLazy`: `{"order":[…]}` or `{"error":…}`. Full edges come from
/// `pairs.pair(left, right)`, which answers like the desktop host does to `needPair`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeBestMixSort(
    mut env: JNIEnv<'_>, _class: JClass<'_>, summaries: JString<'_>, initial: JString<'_>,
    pairs: JObject<'_>,
) -> jstring {
    let result = (|| -> Result<Value, String> {
        let summaries: Value = serde_json::from_str(&read_string(&mut env, &summaries)?)
            .map_err(|e| e.to_string())?;
        let initial: Value = serde_json::from_str(&read_string(&mut env, &initial)?)
            .map_err(|e| e.to_string())?;
        let summaries = summaries.as_array().ok_or("Missing summaries")?;
        let order = best_mix::sort_lazy(summaries, &initial, |left, right| {
            let left = left.map_or(-1, |index| index as i32);
            let reply = env.call_method(&pairs, "pair", "(II)Ljava/lang/String;",
                &[JValue::Int(left), JValue::Int(right as i32)])
                .and_then(|value| value.l())
                .map_err(|e| e.to_string())?;
            let reply = read_string(&mut env, &JString::from(reply))?;
            let pair: Value = serde_json::from_str(&reply).map_err(|e| e.to_string())?;
            if let Some(error) = pair["error"].as_str() { return Err(error.into()); }
            Ok((pair["left"].clone(), pair["right"].clone()))
        })?;
        Ok(json!({ "order": order }))
    })();
    // A Kotlin exception from the callback must not cross back into Rust's next JNI call.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    let output = result.map_or_else(error_json, |value| value.to_string());
    string(&mut env, &output)
}

static RUNTIME: OnceLock<Result<(), String>> = OnceLock::new();

/// Binds `ort` to the ONNX Runtime the app package ships (already loaded by the JVM).
fn runtime() -> Result<(), String> {
    RUNTIME.get_or_init(|| {
        ort::init_from("libonnxruntime.so").map_err(|e| e.to_string())?.commit();
        Ok(())
    }).clone()
}

/// Loads Beat This and UMX from model bytes on `threads` CPU threads. 0 with no models.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeModelsLoad(
    env: JNIEnv<'_>, _class: JClass<'_>, beat: JByteArray<'_>, vocal: JByteArray<'_>, threads: jint,
) -> jlong {
    let loaded = (|| -> Result<Models, String> {
        runtime()?;
        let beat = env.convert_byte_array(&beat).map_err(|e| e.to_string())?;
        let vocal = env.convert_byte_array(&vocal).map_err(|e| e.to_string())?;
        Models::from_memory(&beat, &vocal, threads.clamp(1, 8) as usize)
    })();
    loaded.map_or(0, |models| Box::into_raw(Box::new(models)) as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeModelsFree(
    _env: JNIEnv<'_>, _class: JClass<'_>, handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Models) });
    }
}

/// Desktop's adaptive-mix worker response for one pair, framed for Kotlin: a big-endian
/// u32 header length, the header JSON, then interleaved stereo f32 LE at `rate`.
/// Must run on a thread with at least 4 MiB of stack: QuickJS plans in it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sfg_orchard_mobile_playback_smart_MixNative_nativeMix(
    mut env: JNIEnv<'_>, _class: JClass<'_>, models: jlong, request: JString<'_>,
    outgoing: jlong, incoming: jlong, rate: jint,
) -> jbyteArray {
    let (header, pcm) = match frame_mix(&mut env, models, &request, outgoing, incoming, rate) {
        Ok((mut header, samples)) => {
            let pcm: Vec<u8> = samples.iter().flat_map(|v| v.to_le_bytes()).collect();
            header["bytes"] = pcm.len().into();
            (header.to_string(), pcm)
        }
        Err(error) => (error_json(error), Vec::new()),
    };
    let mut framed = Vec::with_capacity(4 + header.len() + pcm.len());
    framed.extend_from_slice(&(header.len() as u32).to_be_bytes());
    framed.extend_from_slice(header.as_bytes());
    framed.extend_from_slice(&pcm);
    env.byte_array_from_slice(&framed).map_or(std::ptr::null_mut(), |array| array.into_raw())
}

fn frame_mix(env: &mut JNIEnv<'_>, models: jlong, request: &JString<'_>, outgoing: jlong,
             incoming: jlong, rate: jint) -> Result<(Value, Vec<f32>), String> {
    let request: prepare::MixRequest = serde_json::from_str(&read_string(env, request)?)
        .map_err(|e| e.to_string())?;
    let models = unsafe { borrow::<Models>(models) }.ok_or("Adaptive mix models are unavailable")?;
    let outgoing = unsafe { borrow::<Song>(outgoing) }.ok_or("Missing outgoing song")?;
    let incoming = unsafe { borrow::<Song>(incoming) }.ok_or("Missing incoming song")?;
    let (mut prepared, samples) = prepare::mix(models, &request, outgoing, incoming)?;
    // The plan is made at 48 kHz like desktop's; only the finished audio meets the player's rate.
    let samples = prepare::resample_to(&mut prepared, samples, u32::try_from(rate).unwrap_or(0))?;
    let header = serde_json::to_value(prepared).map_err(|e| e.to_string())?;
    Ok((header, samples))
}
