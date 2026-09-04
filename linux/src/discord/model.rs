use std::time::{SystemTime, UNIX_EPOCH};

use discord_rich_presence::activity::{
    Activity, ActivityType, Assets, Button, StatusDisplayType, Timestamps,
};
use reqwest::Url;
use serde::Deserialize;

pub const ORCHARD_PROJECT_URL: &str = "https://sfg545.dev/orchard";
const ARTWORK_PROXY_ORIGIN: &str = "https://artwork-proxy.sfg545.dev";
const ARTWORK_VERSION: &str = "7";

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct DiscordPresencePayload {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub isrc: String,
    pub youtube_video_id: String,
    pub thumbnail_url: String,
    pub artwork_url: String,
    pub animated_artwork_url: String,
    pub is_playing: bool,
    pub current_time: f64,
    pub duration: f64,
    pub duration_seconds: f64,
}

#[derive(Clone, Debug, Default)]
pub struct ActivityOptions {
    pub artwork_url: String,
    pub song_link_url: String,
}

impl DiscordPresencePayload {
    pub fn has_title(&self) -> bool {
        !trim_text(&self.title, "").is_empty()
    }

    pub fn song_link_input(&self) -> Option<SongLinkInput> {
        let title = trim_text(&self.title, "");
        let artist = trim_text(&self.artist, "");
        if title.is_empty() || artist.is_empty() {
            return None;
        }
        let duration = positive(self.duration_seconds)
            .or_else(|| positive(self.duration))
            .unwrap_or_default();
        Some(SongLinkInput {
            title,
            artist,
            album: trim_text(&self.album, ""),
            isrc: trim_text(&self.isrc, "").to_uppercase(),
            youtube_video_id: self.youtube_video_id.trim().to_owned(),
            duration_seconds: duration.round() as u64,
            thumbnail_url: normalized_image_url(if self.thumbnail_url.trim().is_empty() {
                &self.artwork_url
            } else {
                &self.thumbnail_url
            }),
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SongLinkInput {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub isrc: String,
    pub youtube_video_id: String,
    pub duration_seconds: u64,
    pub thumbnail_url: String,
}

impl SongLinkInput {
    pub fn cache_key(&self) -> String {
        format!(
            "{}\n{}\n{}\n{}\n{}\n{}\n{}",
            self.title,
            self.artist,
            self.album,
            self.isrc,
            self.youtube_video_id,
            self.duration_seconds,
            self.thumbnail_url
        )
    }

    pub fn append_query(&self, url: &mut Url) {
        let mut query = url.query_pairs_mut();
        query.append_pair("title", &self.title);
        query.append_pair("artist", &self.artist);
        if !self.album.is_empty() {
            query.append_pair("album", &self.album);
        }
        if !self.isrc.is_empty() {
            query.append_pair("isrc", &self.isrc);
        }
        if !self.youtube_video_id.is_empty() {
            query.append_pair("youtubeVideoId", &self.youtube_video_id);
        }
        if self.duration_seconds > 0 {
            query.append_pair("durationSeconds", &self.duration_seconds.to_string());
        }
        if !self.thumbnail_url.is_empty() {
            query.append_pair("thumbnailUrl", &self.thumbnail_url);
        }
    }
}

pub fn build_activity(
    presence: &DiscordPresencePayload,
    options: &ActivityOptions,
) -> Option<Activity<'static>> {
    if !presence.has_title() {
        return None;
    }
    let title = trim_text(&presence.title, "Playing music");
    let artist = trim_text(&presence.artist, "Orchard");
    let album = trim_text(&presence.album, "");
    let details = if presence.is_playing {
        title
    } else {
        format!("Paused - {title}")
    };
    let artwork = normalized_image_url(if options.artwork_url.is_empty() {
        &presence.artwork_url
    } else {
        &options.artwork_url
    });
    let mut assets = Assets::new().small_text(if presence.is_playing {
        "Playing"
    } else {
        "Paused"
    });
    if !artwork.is_empty() {
        assets = assets.large_image(artwork);
    }
    if !album.is_empty() {
        assets = assets.large_text(album);
    }

    let mut activity = Activity::new()
        .name(if artist.is_empty() {
            "Orchard".to_owned()
        } else {
            artist.clone()
        })
        .activity_type(ActivityType::Listening)
        .status_display_type(StatusDisplayType::Details)
        .details(details)
        .state(artist)
        .assets(assets);
    if presence.is_playing {
        activity = activity.timestamps(playback_timestamps(presence, unix_millis()));
    }

    let mut buttons = Vec::with_capacity(2);
    if let Some(url) = normalized_url(&options.song_link_url) {
        buttons.push(Button::new("Listen on Your Platform", url.to_string()));
    }
    buttons.push(Button::new("View the Orchard Project", ORCHARD_PROJECT_URL));
    Some(activity.buttons(buttons))
}

fn playback_timestamps(presence: &DiscordPresencePayload, now: i64) -> Timestamps {
    let current = positive(presence.current_time).unwrap_or_default();
    let duration = positive(presence.duration).unwrap_or_default();
    let mut timestamps = Timestamps::new().start(now - (current * 1000.0).round() as i64);
    if duration > current {
        timestamps = timestamps.end(now + ((duration - current) * 1000.0).round() as i64);
    }
    timestamps
}

pub fn animated_artwork_url(value: &str) -> String {
    let Some(source) = normalized_url(value) else {
        return String::new();
    };
    if source.scheme() != "https"
        || source.host_str() != Some("mvod.itunes.apple.com")
        || !source.path().to_ascii_lowercase().ends_with(".mp4")
    {
        return String::new();
    }
    let mut proxy = Url::parse(ARTWORK_PROXY_ORIGIN).expect("constant proxy URL is valid");
    proxy.set_path("/convert.gif");
    proxy
        .query_pairs_mut()
        .append_pair("v", ARTWORK_VERSION)
        .append_pair("url", source.as_str());
    proxy.to_string()
}

pub fn normalized_image_url(value: &str) -> String {
    let Some(url) = normalized_url(value) else {
        return String::new();
    };
    let path = url.path().to_ascii_lowercase();
    if [".mp4", ".webm", ".mov", ".m4v"]
        .iter()
        .any(|extension| path.ends_with(extension))
    {
        String::new()
    } else {
        url.to_string()
    }
}

pub fn normalized_url(value: &str) -> Option<Url> {
    let url = Url::parse(value.trim()).ok()?;
    matches!(url.scheme(), "http" | "https").then_some(url)
}

fn trim_text(value: &str, fallback: &str) -> String {
    let source = if value.trim().is_empty() {
        fallback
    } else {
        value
    };
    source
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(128)
        .collect()
}

fn positive(value: f64) -> Option<f64> {
    (value.is_finite() && value > 0.0).then_some(value)
}

fn unix_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn builds_listening_activity_with_playback_timestamps() {
        let payload = DiscordPresencePayload {
            title: "  A   Song ".into(),
            artist: "An Artist".into(),
            album: "An Album".into(),
            artwork_url: "https://example.com/cover.jpg".into(),
            is_playing: true,
            current_time: 10.0,
            duration: 100.0,
            ..Default::default()
        };
        let value = serde_json::to_value(build_activity(&payload, &ActivityOptions::default()))
            .expect("activity serializes");
        assert_eq!(value["details"], "A Song");
        assert_eq!(value["state"], "An Artist");
        assert_eq!(value["type"], 2);
        assert_eq!(
            value["assets"]["large_image"],
            "https://example.com/cover.jpg"
        );
        assert_eq!(value["buttons"][0]["url"], ORCHARD_PROJECT_URL);
        assert!(value["timestamps"]["end"].as_i64().unwrap() > unix_millis());
    }

    #[test]
    fn accepts_only_itunes_mp4_for_animated_artwork() {
        let url = animated_artwork_url("https://mvod.itunes.apple.com/a/b/cover.mp4");
        let parsed = Url::parse(&url).unwrap();
        assert_eq!(parsed.host_str(), Some("artwork-proxy.sfg545.dev"));
        assert_eq!(
            parsed.query_pairs().find(|(key, _)| key == "v").unwrap().1,
            "7"
        );
        assert_eq!(animated_artwork_url("https://example.com/cover.mp4"), "");
        assert_eq!(normalized_image_url("https://example.com/cover.webm"), "");
    }

    #[test]
    fn normalizes_song_link_input() {
        let input = DiscordPresencePayload {
            title: " Song ".into(),
            artist: " Artist ".into(),
            isrc: "usabc123".into(),
            duration: 123.6,
            thumbnail_url: "https://example.com/cover.jpg".into(),
            ..Default::default()
        }
        .song_link_input()
        .unwrap();
        assert_eq!(input.title, "Song");
        assert_eq!(input.isrc, "USABC123");
        assert_eq!(input.duration_seconds, 124);
        assert_eq!(json!(input.thumbnail_url), "https://example.com/cover.jpg");
    }
}
