use std::sync::Mutex;
use std::time::Duration;

use playwire::{Capabilities, Event, MediaControls, PlaybackState, PlayerConfig, Repeat, Track};
use serde::Deserialize;
use serde_json::{Value, json};
use tauri::{AppHandle, Emitter};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemMediaState {
    pub track: Option<SystemMediaTrack>,
    #[serde(default)]
    pub is_playing: bool,
    #[serde(default)]
    pub current_time: f64,
    pub duration_seconds: Option<f64>,
    #[serde(default = "one")]
    pub volume: f64,
    pub repeat_mode: Option<String>,
    #[serde(default)]
    pub shuffle_enabled: bool,
    #[serde(default)]
    pub can_go_next: bool,
    #[serde(default)]
    pub can_go_previous: bool,
    #[serde(default = "yes")]
    pub can_seek: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemMediaTrack {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub artists: Vec<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub thumbnail: Option<String>,
}

pub struct SystemMedia {
    controls: Mutex<Option<MediaControls>>,
    startup_error: Option<String>,
}

impl SystemMedia {
    pub fn new(app: AppHandle) -> Self {
        let config = PlayerConfig::new("Orchard")
            .desktop_entry("dev.sfg.orchard")
            .track_id_prefix("/dev/sfg/orchard/track");
        match MediaControls::new(config, move |event| {
            let _ = app.emit("system-media-command", event_payload(event));
        }) {
            Ok(controls) => Self {
                controls: Mutex::new(Some(controls)),
                startup_error: None,
            },
            Err(error) => Self {
                controls: Mutex::new(None),
                startup_error: Some(error.to_string()),
            },
        }
    }

    pub fn set_state(&self, input: SystemMediaState) -> Result<(), String> {
        if let Some(error) = &self.startup_error {
            return Err(error.clone());
        }
        let track = input.track.map(|track| Track {
            id: track.id,
            title: track.title,
            artists: if track.artists.is_empty() {
                track.artist.into_iter().collect()
            } else {
                track.artists
            },
            album: track.album.unwrap_or_default(),
            artwork_url: track.thumbnail.unwrap_or_default(),
            url: String::new(),
        });
        let duration = input
            .duration_seconds
            .filter(|value| value.is_finite() && *value > 0.0)
            .map(Duration::from_secs_f64);
        let position = if input.current_time.is_finite() {
            input.current_time.max(0.0)
        } else {
            0.0
        };
        let state = PlaybackState {
            track,
            playing: input.is_playing,
            position: Duration::from_secs_f64(position),
            duration,
            volume: input.volume,
            repeat: match input.repeat_mode.as_deref() {
                Some("one") => Repeat::One,
                Some("all") => Repeat::All,
                _ => Repeat::Off,
            },
            shuffle: input.shuffle_enabled,
            capabilities: Capabilities {
                can_go_next: input.can_go_next,
                can_go_previous: input.can_go_previous,
                can_seek: input.can_seek,
            },
        };
        let mut controls = self.controls.lock().map_err(|error| error.to_string())?;
        controls
            .as_mut()
            .ok_or_else(|| "Playwire is unavailable".to_string())?
            .set_state(&state)
            .map_err(|error| error.to_string())
    }
}

fn event_payload(event: Event) -> Value {
    match event {
        Event::Play => json!({ "type": "play" }),
        Event::Pause => json!({ "type": "pause" }),
        Event::PlayPause => json!({ "type": "play-pause" }),
        Event::Stop => json!({ "type": "stop" }),
        Event::Next => json!({ "type": "next" }),
        Event::Previous => json!({ "type": "previous" }),
        Event::SeekTo(value) => json!({ "type": "seek", "value": value.as_secs_f64() }),
        Event::SeekBy(value) => json!({ "type": "seek-relative", "value": value }),
        Event::SetVolume(value) => json!({ "type": "set-volume", "value": value }),
        Event::SetShuffle(value) => json!({ "type": "set-shuffle", "value": value }),
        Event::SetRepeat(value) => {
            json!({ "type": "set-repeat-mode", "value": format!("{value:?}").to_lowercase() })
        }
        Event::OpenUri(uri) => json!({ "type": "open-uri", "value": uri }),
        Event::Raise => json!({ "type": "raise" }),
        Event::Quit => json!({ "type": "quit" }),
        _ => json!({ "type": "unknown" }),
    }
}

const fn one() -> f64 {
    1.0
}
const fn yes() -> bool {
    true
}
