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

//! Stable, deliberately small C ABI between the Qt host and Orchard's Rust core.
//!
//! Qt types never cross this boundary. Rich requests will use owned byte buffers
//! containing versioned messages; the initial ABI only exposes enough metadata
//! to prove that the real transition crate is linked into the application.

mod audio_engine;
mod media_crypto;

pub use audio_engine::*;
pub use media_crypto::*;
pub use orchard_youtube_extractor::ffi::*;

use std::ffi::{CStr, CString, c_char, c_void};
use std::ptr;
use std::slice;

use orchard_system_media::{
    Command, MediaState, Options, RepeatMode, SystemMediaControls, TrackState,
};
use orchard_transition_core::MAX_SECONDS;

/// Increment only when the C ABI changes incompatibly.
#[unsafe(no_mangle)]
pub extern "C" fn orchard_core_abi_version() -> u32 {
    1
}

/// Confirms that the migrated smart-crossfade implementation is linked.
#[unsafe(no_mangle)]
pub extern "C" fn orchard_smart_crossfade_max_seconds() -> f64 {
    MAX_SECONDS
}

/// Current version of the system-media C API.
#[unsafe(no_mangle)]
pub extern "C" fn orchard_system_media_api_version() -> u32 {
    2
}

/// Opaque owner for Orchard's playwire-backed media controls.
pub struct OrchardSystemMediaHandle {
    controls: Option<SystemMediaControls>,
    last_error: CString,
}

/// Platform registration options supplied by the Qt host.
#[repr(C)]
pub struct OrchardSystemMediaOptions {
    pub display_name: *const c_char,
    pub bus_name: *const c_char,
    pub desktop_entry: *const c_char,
    pub window_handle: u64,
    pub has_window_handle: u8,
    pub app_media_id: *const c_char,
}

/// Track metadata supplied by the Qt host.
#[repr(C)]
pub struct OrchardSystemMediaTrack {
    pub id: *const c_char,
    pub title: *const c_char,
    pub artists: *const *const c_char,
    pub artist_count: usize,
    pub album: *const c_char,
    pub artwork_url: *const c_char,
    pub url: *const c_char,
}

/// Complete playback state supplied by the Qt host.
#[repr(C)]
pub struct OrchardSystemMediaState {
    pub track: *const OrchardSystemMediaTrack,
    pub playing: u8,
    pub can_go_next: u8,
    pub can_go_previous: u8,
    pub can_seek: u8,
    pub position_seconds: f64,
    pub duration_seconds: f64,
    pub has_duration: u8,
    pub volume: f64,
    pub repeat_mode: u32,
    pub shuffle: u8,
}

/// Command sent from playwire to the Qt host.
///
/// All string pointers are valid only for the duration of the callback.
#[repr(C)]
pub struct OrchardSystemMediaCommand {
    pub kind: *const c_char,
    pub number_value: f64,
    pub has_number_value: u8,
    pub bool_value: u8,
    pub has_bool_value: u8,
    pub string_value: *const c_char,
}

/// Callback invoked from the platform thread that delivered a media command.
pub type OrchardSystemMediaCallback =
    Option<unsafe extern "C" fn(*mut c_void, *const OrchardSystemMediaCommand)>;

fn owned_c_string(value: *const c_char) -> String {
    if value.is_null() {
        return String::new();
    }

    // SAFETY: The C ABI requires non-null string pointers to reference a
    // NUL-terminated string for the duration of the call.
    unsafe { CStr::from_ptr(value) }
        .to_string_lossy()
        .into_owned()
}

fn error_string(message: impl ToString) -> CString {
    CString::new(message.to_string().replace('\0', "�"))
        .expect("the replacement removes interior NUL bytes")
}

fn ffi_options(options: *const OrchardSystemMediaOptions) -> Options {
    if options.is_null() {
        return Options::default();
    }

    // SAFETY: The caller keeps the options structure and its strings alive for
    // this call. Every value is copied before returning.
    let options = unsafe { &*options };
    Options {
        display_name: owned_c_string(options.display_name),
        bus_name: owned_c_string(options.bus_name),
        desktop_entry: owned_c_string(options.desktop_entry),
        window_handle: (options.has_window_handle != 0).then_some(options.window_handle),
        app_media_id: (!options.app_media_id.is_null())
            .then(|| owned_c_string(options.app_media_id)),
    }
}

fn ffi_track(track: *const OrchardSystemMediaTrack) -> Option<TrackState> {
    if track.is_null() {
        return None;
    }

    // SAFETY: The caller keeps the track, pointer array, and pointed-to strings
    // alive for this call. The data is copied into owned Rust values.
    let track = unsafe { &*track };
    let artists = if track.artists.is_null() || track.artist_count == 0 {
        Vec::new()
    } else {
        // SAFETY: The C ABI requires `artists` to contain `artist_count` valid
        // pointers when the count is non-zero.
        unsafe { slice::from_raw_parts(track.artists, track.artist_count) }
            .iter()
            .map(|artist| owned_c_string(*artist))
            .collect()
    };

    Some(TrackState {
        id: owned_c_string(track.id),
        title: owned_c_string(track.title),
        artists,
        album: owned_c_string(track.album),
        artwork_url: owned_c_string(track.artwork_url),
        url: owned_c_string(track.url),
    })
}

fn ffi_state(state: &OrchardSystemMediaState) -> MediaState {
    MediaState {
        track: ffi_track(state.track),
        playing: state.playing != 0,
        can_go_next: state.can_go_next != 0,
        can_go_previous: state.can_go_previous != 0,
        can_seek: state.can_seek != 0,
        position_seconds: state.position_seconds,
        duration_seconds: (state.has_duration != 0).then_some(state.duration_seconds),
        volume: state.volume,
        repeat: match state.repeat_mode {
            1 => RepeatMode::One,
            2 => RepeatMode::Queue,
            _ => RepeatMode::Off,
        },
        shuffle: state.shuffle != 0,
    }
}

fn emit_command(
    callback: unsafe extern "C" fn(*mut c_void, *const OrchardSystemMediaCommand),
    context: usize,
    command: Command,
) {
    let mut number_value = 0.0;
    let mut has_number_value = 0;
    let mut bool_value = 0;
    let mut has_bool_value = 0;
    let mut owned_string = None;

    let kind = match command {
        Command::Play => c"play",
        Command::Pause => c"pause",
        Command::PlayPause => c"play-pause",
        Command::Stop => c"stop",
        Command::Next => c"next",
        Command::Previous => c"previous",
        Command::SeekTo(value) => {
            number_value = value;
            has_number_value = 1;
            c"seek"
        }
        Command::SeekBy(value) => {
            number_value = value;
            has_number_value = 1;
            c"seek-relative"
        }
        Command::SetVolume(value) => {
            number_value = value;
            has_number_value = 1;
            c"set-volume"
        }
        Command::SetShuffle(value) => {
            bool_value = u8::from(value);
            has_bool_value = 1;
            c"set-shuffle"
        }
        Command::SetRepeat(value) => {
            owned_string = Some(error_string(match value {
                RepeatMode::Off => "off",
                RepeatMode::One => "one",
                RepeatMode::Queue => "queue",
            }));
            c"set-repeat-mode"
        }
        Command::OpenUri(value) => {
            owned_string = Some(error_string(value));
            c"open-uri"
        }
        Command::Raise => c"raise",
        Command::Quit => c"quit",
        Command::Unknown => c"unknown",
    };

    let command = OrchardSystemMediaCommand {
        kind: kind.as_ptr(),
        number_value,
        has_number_value,
        bool_value,
        has_bool_value,
        string_value: owned_string
            .as_ref()
            .map_or(ptr::null(), |value| value.as_ptr()),
    };

    // SAFETY: The caller supplied this callback. It is invoked synchronously;
    // the command and any owned string remain alive until it returns.
    unsafe { callback(context as *mut c_void, &command) };
}

/// Creates and registers Orchard's system media controls.
///
/// A non-null handle is returned even when the platform registration fails so
/// callers can inspect [`orchard_system_media_last_error`].
///
/// # Safety
///
/// `options` must be null or point to a valid options structure whose strings
/// remain valid for this call. The callback and its context must remain valid
/// until the returned handle is destroyed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_system_media_create(
    options: *const OrchardSystemMediaOptions,
    callback: OrchardSystemMediaCallback,
    callback_context: *mut c_void,
) -> *mut OrchardSystemMediaHandle {
    let options = ffi_options(options);
    let context = callback_context as usize;
    let result = SystemMediaControls::new(options, move |command| {
        if let Some(callback) = callback {
            emit_command(callback, context, command);
        }
    });

    let (controls, last_error) = match result {
        Ok(controls) => (Some(controls), error_string("")),
        Err(error) => (None, error_string(error)),
    };

    Box::into_raw(Box::new(OrchardSystemMediaHandle {
        controls,
        last_error,
    }))
}

/// Reports whether the playwire platform registration is active.
///
/// # Safety
///
/// `handle` must be null or a live handle returned by
/// [`orchard_system_media_create`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_system_media_is_attached(
    handle: *const OrchardSystemMediaHandle,
) -> u8 {
    if handle.is_null() {
        return 0;
    }

    // SAFETY: Non-null handles are created and owned by this ABI.
    u8::from(unsafe { &*handle }.controls.is_some())
}

/// Publishes the latest playback snapshot.
///
/// # Safety
///
/// `handle` must be a live handle and `state` must point to a valid state whose
/// nested pointers remain valid for this call. Neither pointer may be null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_system_media_set_state(
    handle: *mut OrchardSystemMediaHandle,
    state: *const OrchardSystemMediaState,
) -> u8 {
    if handle.is_null() || state.is_null() {
        return 0;
    }

    // SAFETY: Non-null handles are created and owned by this ABI, and the
    // caller keeps `state` alive for this call.
    let handle = unsafe { &mut *handle };
    let state = ffi_state(unsafe { &*state });
    let Some(controls) = handle.controls.as_mut() else {
        return 0;
    };

    match controls.set_state(&state) {
        Ok(()) => {
            handle.last_error = error_string("");
            1
        }
        Err(error) => {
            handle.last_error = error_string(error);
            0
        }
    }
}

/// Returns the last platform error, or an empty string when there is none.
///
/// The pointer remains valid until the next mutable operation on the handle or
/// until the handle is destroyed.
///
/// # Safety
///
/// `handle` must be null or a live handle returned by
/// [`orchard_system_media_create`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_system_media_last_error(
    handle: *const OrchardSystemMediaHandle,
) -> *const c_char {
    if handle.is_null() {
        return c"invalid system media handle".as_ptr();
    }

    // SAFETY: Non-null handles are created and owned by this ABI.
    unsafe { &*handle }.last_error.as_ptr()
}

/// Detaches from the platform service without destroying the handle.
///
/// # Safety
///
/// `handle` must be null or a live handle returned by
/// [`orchard_system_media_create`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_system_media_stop(handle: *mut OrchardSystemMediaHandle) {
    if handle.is_null() {
        return;
    }

    // SAFETY: Non-null handles are created and owned by this ABI.
    let handle = unsafe { &mut *handle };
    if let Some(mut controls) = handle.controls.take() {
        controls.stop();
    }
}

/// Stops the platform integration and releases the opaque handle.
///
/// # Safety
///
/// `handle` must be null or a live handle returned by
/// [`orchard_system_media_create`] that has not already been destroyed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn orchard_system_media_destroy(handle: *mut OrchardSystemMediaHandle) {
    if handle.is_null() {
        return;
    }

    // SAFETY: Ownership of a handle returned by `create` is transferred back
    // exactly once through this function.
    let mut handle = unsafe { Box::from_raw(handle) };
    if let Some(mut controls) = handle.controls.take() {
        controls.stop();
    }
}
