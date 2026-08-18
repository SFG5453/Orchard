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

//! N-API bindings over the playwire crate, which owns the MPRIS/SMTC/Now Playing
//! implementations. Everything here is marshalling: this crate holds only what
//! is specific to Orchard.

use std::time::Duration;

use napi::bindgen_prelude::*;
use napi::threadsafe_function::ThreadsafeFunctionCallMode;
use napi_derive::napi;

use playwire::{Capabilities, Event, MediaControls, PlaybackState, PlayerConfig, Repeat, Track};

/// Object-path prefix for `mpris:trackid`. Namespaced to Orchard so two players
/// can never collide in a client's metadata cache.
const TRACK_ID_PREFIX: &str = "/dev/sfg/orchard/track";

#[napi(object)]
#[derive(Clone, Default)]
pub struct JsTrack {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub artists: Vec<String>,
    pub album: String,
    pub thumbnail: String,
}

/// Mirrors `systemMediaPayload()` in `src/app/platform/systemMediaActions.js`.
#[napi(object)]
pub struct JsMediaState {
    pub track: Option<JsTrack>,
    pub is_playing: bool,
    pub can_go_next: bool,
    pub can_go_previous: bool,
    pub can_seek: bool,
    pub current_time: f64,
    pub duration_seconds: f64,
    pub volume: f64,
    /// `"off"`, `"one"` or `"queue"`.
    pub repeat_mode: String,
    pub shuffle_enabled: bool,
}

/// The renderer-facing command shape. `handleSystemMediaCommand()` switches on
/// `type`; N-API has no untyped union, so the payload is split by type and the
/// Electron main process collapses it back to a single `value`.
#[napi(object)]
pub struct JsCommand {
    pub r#type: String,
    pub number_value: Option<f64>,
    pub bool_value: Option<bool>,
    pub string_value: Option<String>,
}

#[napi(object)]
pub struct JsOptions {
    pub display_name: String,
    pub dbus_name: String,
    pub desktop_entry: String,
    /// Windows only: the HWND from `BrowserWindow.getNativeWindowHandle()`.
    pub hwnd: Option<f64>,
}

fn seconds(value: f64) -> Duration {
    if value.is_finite() && value > 0.0 {
        Duration::from_secs_f64(value)
    } else {
        Duration::ZERO
    }
}

fn to_playback_state(state: &JsMediaState) -> PlaybackState {
    let track = state.track.as_ref().map(|track| {
        let artists = if track.artists.is_empty() {
            if track.artist.is_empty() {
                Vec::new()
            } else {
                vec![track.artist.clone()]
            }
        } else {
            track.artists.clone()
        };

        Track {
            id: track.id.clone(),
            title: track.title.clone(),
            artists,
            album: track.album.clone(),
            artwork_url: track.thumbnail.clone(),
            url: if track.id.is_empty() {
                String::new()
            } else {
                format!("https://music.youtube.com/watch?v={}", track.id)
            },
        }
    });

    PlaybackState {
        track,
        playing: state.is_playing,
        position: seconds(state.current_time),
        // A live stream reports no duration, which is what tells the backends to
        // omit a length and leave the scrubber alone.
        duration: (state.duration_seconds.is_finite() && state.duration_seconds > 0.0)
            .then(|| seconds(state.duration_seconds)),
        volume: state.volume,
        repeat: match state.repeat_mode.as_str() {
            "one" => Repeat::One,
            "queue" => Repeat::All,
            _ => Repeat::Off,
        },
        shuffle: state.shuffle_enabled,
        capabilities: Capabilities {
            can_go_next: state.can_go_next,
            can_go_previous: state.can_go_previous,
            can_seek: state.can_seek,
        },
    }
}

fn to_command(event: Event) -> JsCommand {
    let (kind, number_value, bool_value, string_value) = match event {
        Event::Play => ("play", None, None, None),
        Event::Pause => ("pause", None, None, None),
        Event::PlayPause => ("play-pause", None, None, None),
        Event::Stop => ("stop", None, None, None),
        Event::Next => ("next", None, None, None),
        Event::Previous => ("previous", None, None, None),
        Event::SeekTo(position) => ("seek", Some(position.as_secs_f64()), None, None),
        Event::SeekBy(offset) => ("seek-relative", Some(offset), None, None),
        Event::SetVolume(volume) => ("set-volume", Some(volume), None, None),
        Event::SetShuffle(shuffle) => ("set-shuffle", None, Some(shuffle), None),
        Event::SetRepeat(repeat) => (
            "set-repeat-mode",
            None,
            None,
            Some(
                match repeat {
                    Repeat::Off => "off",
                    Repeat::One => "one",
                    Repeat::All => "queue",
                }
                .to_string(),
            ),
        ),
        Event::Raise => ("raise", None, None, None),
        Event::Quit => ("quit", None, None, None),
        // playwire's Event is #[non_exhaustive]; Orchard has no use for OpenUri.
        _ => ("unknown", None, None, None),
    };

    JsCommand {
        r#type: kind.to_string(),
        number_value,
        bool_value,
        string_value,
    }
}

#[napi]
pub struct SystemMediaControls {
    controls: Option<MediaControls>,
}

#[napi]
impl SystemMediaControls {
    #[napi(constructor)]
    pub fn new(options: JsOptions, on_command: Function<JsCommand, ()>) -> Result<Self> {
        let callback = on_command
            .build_threadsafe_function::<JsCommand>()
            .build()?;

        let mut config = PlayerConfig::new(options.display_name)
            .desktop_entry(options.desktop_entry)
            .track_id_prefix(TRACK_ID_PREFIX)
            .supported_mime_types(vec![
                "audio/mpeg".to_string(),
                "audio/mp4".to_string(),
                "audio/webm".to_string(),
                "video/mp4".to_string(),
                "video/webm".to_string(),
            ]);
        config.bus_name = options.dbus_name;
        if let Some(hwnd) = options.hwnd {
            config.hwnd = Some(hwnd as u64);
        }

        let controls = MediaControls::new(config, move |event| {
            callback.call(to_command(event), ThreadsafeFunctionCallMode::NonBlocking);
        })
        .map_err(|error| Error::from_reason(error.to_string()))?;

        Ok(Self {
            controls: Some(controls),
        })
    }

    #[napi]
    pub fn set_state(&mut self, state: JsMediaState) -> Result<()> {
        let Some(controls) = self.controls.as_mut() else {
            return Ok(());
        };

        controls
            .set_state(&to_playback_state(&state))
            .map_err(|error| Error::from_reason(error.to_string()))
    }

    /// Safe to call twice; teardown races with window destruction on quit.
    #[napi]
    pub fn stop(&mut self) {
        if let Some(mut controls) = self.controls.take() {
            controls.detach();
        }
    }
}
