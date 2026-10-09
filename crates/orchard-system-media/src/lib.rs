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

//! Orchard's runtime-neutral adapter for system media controls.
//!
//! [`playwire`] owns the platform implementations. This crate owns only
//! Orchard's state and command vocabulary, keeping Qt and the C ABI out of the
//! media backend itself.

use std::time::Duration;

use playwire::{Capabilities, Event, MediaControls, PlaybackState, PlayerConfig, Repeat, Track};

/// Object-path prefix used for Orchard tracks on MPRIS.
const TRACK_ID_PREFIX: &str = "/dev/sfg/orchard/track";

/// Configuration needed when registering Orchard with the operating system.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Options {
    /// Human-readable player name.
    pub display_name: String,
    /// Final component of the Linux MPRIS bus name.
    pub bus_name: String,
    /// Desktop file basename used by Linux shells.
    pub desktop_entry: String,
    /// Native window handle required by Windows SMTC.
    pub window_handle: Option<u64>,
    /// Windows AppUserModelID shared with the process and Start Menu shortcut.
    pub app_media_id: Option<String>,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            display_name: "Orchard".to_string(),
            bus_name: "Orchard".to_string(),
            desktop_entry: "dev.sfg.orchard".to_string(),
            window_handle: None,
            app_media_id: None,
        }
    }
}

/// Metadata for the currently loaded track.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TrackState {
    /// Stable provider track identifier.
    pub id: String,
    /// Display title.
    pub title: String,
    /// Every credited artist.
    pub artists: Vec<String>,
    /// Album title.
    pub album: String,
    /// Artwork URL understood by the platform backend.
    pub artwork_url: String,
    /// Link to the track in its originating service.
    pub url: String,
}

/// Complete snapshot published to the operating system.
#[derive(Clone, Debug, PartialEq)]
pub struct MediaState {
    /// Loaded track, or `None` when stopped and empty.
    pub track: Option<TrackState>,
    /// Whether playback is currently advancing.
    pub playing: bool,
    /// Whether a next queue item exists.
    pub can_go_next: bool,
    /// Whether a previous queue item exists.
    pub can_go_previous: bool,
    /// Whether the current item supports seeking.
    pub can_seek: bool,
    /// Current playback position in seconds.
    pub position_seconds: f64,
    /// Duration in seconds, or `None` for live/unknown-length media.
    pub duration_seconds: Option<f64>,
    /// Volume in the inclusive range 0.0 through 1.0.
    pub volume: f64,
    /// Current repeat mode.
    pub repeat: RepeatMode,
    /// Whether queue shuffling is enabled.
    pub shuffle: bool,
}

impl Default for MediaState {
    fn default() -> Self {
        Self {
            track: None,
            playing: false,
            can_go_next: false,
            can_go_previous: false,
            can_seek: false,
            position_seconds: 0.0,
            duration_seconds: None,
            volume: 1.0,
            repeat: RepeatMode::Off,
            shuffle: false,
        }
    }
}

/// Orchard's queue repeat modes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum RepeatMode {
    /// Stop after the queue ends.
    #[default]
    Off,
    /// Repeat the current track.
    One,
    /// Repeat the entire queue.
    Queue,
}

/// A command received from a media key or system media surface.
#[derive(Clone, Debug, PartialEq)]
pub enum Command {
    /// Start or resume playback.
    Play,
    /// Pause playback.
    Pause,
    /// Toggle playback.
    PlayPause,
    /// Stop playback.
    Stop,
    /// Advance to the next track.
    Next,
    /// Return to the previous track.
    Previous,
    /// Seek to an absolute position in seconds.
    SeekTo(f64),
    /// Seek by a signed number of seconds.
    SeekBy(f64),
    /// Set volume to a value from 0.0 through 1.0.
    SetVolume(f64),
    /// Enable or disable shuffle.
    SetShuffle(bool),
    /// Change the repeat mode.
    SetRepeat(RepeatMode),
    /// Open a URI supplied by the platform.
    OpenUri(String),
    /// Bring Orchard to the foreground.
    Raise,
    /// Quit Orchard.
    Quit,
    /// A command added by a newer playwire version.
    Unknown,
}

/// Orchard's handle to the operating-system media integration.
#[derive(Debug)]
pub struct SystemMediaControls {
    controls: MediaControls,
}

impl SystemMediaControls {
    /// Registers Orchard and begins listening for system commands.
    pub fn new(
        options: Options,
        on_command: impl Fn(Command) + Send + Sync + 'static,
    ) -> playwire::Result<Self> {
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
        config.bus_name = options.bus_name;
        if let Some(window_handle) = options.window_handle {
            config = config.hwnd(window_handle);
        }
        if let Some(app_media_id) = options.app_media_id {
            config = config.app_media_id(app_media_id);
        }

        let controls = MediaControls::new(config, move |event| on_command(to_command(event)))?;
        Ok(Self { controls })
    }

    /// Publishes a complete playback snapshot.
    pub fn set_state(&mut self, state: &MediaState) -> playwire::Result<()> {
        self.controls.set_state(&to_playback_state(state))
    }

    /// Releases the platform registration early.
    pub fn stop(&mut self) {
        self.controls.detach();
    }
}

fn seconds(value: f64) -> Duration {
    if value.is_finite() && value > 0.0 {
        Duration::from_secs_f64(value)
    } else {
        Duration::ZERO
    }
}

fn to_playback_state(state: &MediaState) -> PlaybackState {
    PlaybackState {
        track: state.track.as_ref().map(|track| Track {
            id: track.id.clone(),
            title: track.title.clone(),
            artists: track.artists.clone(),
            album: track.album.clone(),
            artwork_url: track.artwork_url.clone(),
            url: track.url.clone(),
        }),
        playing: state.playing,
        position: seconds(state.position_seconds),
        duration: state
            .duration_seconds
            .filter(|value| value.is_finite() && *value > 0.0)
            .map(seconds),
        volume: state.volume,
        repeat: match state.repeat {
            RepeatMode::Off => Repeat::Off,
            RepeatMode::One => Repeat::One,
            RepeatMode::Queue => Repeat::All,
        },
        shuffle: state.shuffle,
        capabilities: Capabilities {
            can_go_next: state.can_go_next,
            can_go_previous: state.can_go_previous,
            can_seek: state.can_seek,
        },
    }
}

fn to_command(event: Event) -> Command {
    match event {
        Event::Play => Command::Play,
        Event::Pause => Command::Pause,
        Event::PlayPause => Command::PlayPause,
        Event::Stop => Command::Stop,
        Event::Next => Command::Next,
        Event::Previous => Command::Previous,
        Event::SeekTo(position) => Command::SeekTo(position.as_secs_f64()),
        Event::SeekBy(offset) => Command::SeekBy(offset),
        Event::SetVolume(volume) => Command::SetVolume(volume),
        Event::SetShuffle(shuffle) => Command::SetShuffle(shuffle),
        Event::SetRepeat(repeat) => Command::SetRepeat(match repeat {
            Repeat::Off => RepeatMode::Off,
            Repeat::One => RepeatMode::One,
            Repeat::All => RepeatMode::Queue,
        }),
        Event::OpenUri(uri) => Command::OpenUri(uri),
        Event::Raise => Command::Raise,
        Event::Quit => Command::Quit,
        _ => Command::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_times_are_safe_and_unknown_duration_stays_absent() {
        let state = MediaState {
            position_seconds: f64::NAN,
            duration_seconds: Some(-1.0),
            ..MediaState::default()
        };

        let state = to_playback_state(&state);
        assert_eq!(state.position, Duration::ZERO);
        assert_eq!(state.duration, None);
    }

    #[test]
    fn preserves_metadata_capabilities_and_queue_repeat() {
        let state = MediaState {
            track: Some(TrackState {
                id: "video-id".into(),
                title: "Title".into(),
                artists: vec!["Artist A".into(), "Artist B".into()],
                album: "Album".into(),
                artwork_url: "https://example.test/cover.jpg".into(),
                url: "https://music.youtube.com/watch?v=video-id".into(),
            }),
            playing: true,
            can_go_next: true,
            can_go_previous: false,
            can_seek: true,
            position_seconds: 15.5,
            duration_seconds: Some(180.0),
            volume: 0.75,
            repeat: RepeatMode::Queue,
            shuffle: true,
        };

        let state = to_playback_state(&state);
        assert_eq!(state.track.as_ref().unwrap().artists.len(), 2);
        assert!(state.playing);
        assert_eq!(state.position, Duration::from_secs_f64(15.5));
        assert_eq!(state.duration, Some(Duration::from_secs(180)));
        assert_eq!(state.repeat, Repeat::All);
        assert!(state.shuffle);
        assert!(!state.capabilities.can_go_previous);
    }
}
