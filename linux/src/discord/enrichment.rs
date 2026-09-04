use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use reqwest::{Client, Url};
use serde_json::Value;

use super::model::{DiscordPresencePayload, SongLinkInput, normalized_url};

const SONG_LINKS_ORIGIN: &str = "https://songlinks.sfg545.dev";
const CACHE_LIMIT: usize = 80;

pub struct DiscordEnrichment {
    http: Client,
    song_links: Mutex<Cache<Option<String>>>,
    artwork: Mutex<Cache<ArtworkCacheEntry>>,
}

#[derive(Clone)]
enum ArtworkCacheEntry {
    Ready,
    RetryAfter(Instant),
}

impl DiscordEnrichment {
    pub fn new() -> Result<Self, reqwest::Error> {
        Ok(Self {
            http: Client::builder()
                .user_agent("Orchard Discord RPC")
                .build()?,
            song_links: Mutex::new(Cache::default()),
            artwork: Mutex::new(Cache::default()),
        })
    }

    pub async fn resolve_song_link(&self, presence: &DiscordPresencePayload) -> String {
        let Some(input) = presence.song_link_input() else {
            return String::new();
        };
        let key = input.cache_key();
        if let Some(cached) = self.song_links.lock().unwrap().get(&key) {
            return cached.unwrap_or_default();
        }
        let resolved = match self.fetch_song_link(&input).await {
            Ok(url) => url,
            Err(error) => {
                eprintln!("Could not resolve Discord song link: {error}");
                None
            }
        };
        self.song_links
            .lock()
            .unwrap()
            .insert(key, resolved.clone());
        resolved.unwrap_or_default()
    }

    pub async fn warm_artwork(&self, url: &str) -> bool {
        if url.is_empty() {
            return false;
        }
        if let Some(cached) = self.artwork.lock().unwrap().get(url) {
            match cached {
                ArtworkCacheEntry::Ready => return true,
                ArtworkCacheEntry::RetryAfter(retry_at) if retry_at > Instant::now() => {
                    return false;
                }
                ArtworkCacheEntry::RetryAfter(_) => {}
            }
        }
        let ready = match self
            .http
            .get(url)
            .header("accept", "image/gif")
            .timeout(Duration::from_secs(90))
            .send()
            .await
        {
            Ok(response) if response.status().is_success() => response.bytes().await.is_ok(),
            Ok(response) => {
                eprintln!(
                    "Could not prepare Discord artwork: HTTP {}",
                    response.status()
                );
                false
            }
            Err(error) => {
                eprintln!("Could not prepare Discord artwork: {error}");
                false
            }
        };
        let entry = if ready {
            ArtworkCacheEntry::Ready
        } else {
            ArtworkCacheEntry::RetryAfter(Instant::now() + Duration::from_secs(30))
        };
        self.artwork.lock().unwrap().insert(url.to_owned(), entry);
        ready
    }

    async fn fetch_song_link(&self, input: &SongLinkInput) -> Result<Option<String>, String> {
        let mut url = Url::parse(SONG_LINKS_ORIGIN).map_err(|error| error.to_string())?;
        url.set_path("/resolve");
        input.append_query(&mut url);
        let response = self
            .http
            .get(url)
            .header("accept", "application/json")
            .timeout(Duration::from_secs(8))
            .send()
            .await
            .map_err(|error| error.to_string())?;
        if !response.status().is_success() {
            return Err(format!("song link worker returned {}", response.status()));
        }
        let data: Value =
            serde_json::from_str(&response.text().await.map_err(|error| error.to_string())?)
                .map_err(|error| error.to_string())?;
        if data.get("ok").and_then(Value::as_bool) != Some(true) {
            return Ok(None);
        }
        let Some(value) = data.get("shareUrl").and_then(Value::as_str) else {
            return Ok(None);
        };
        let base = Url::parse(SONG_LINKS_ORIGIN).map_err(|error| error.to_string())?;
        let absolute = base.join(value).map_err(|error| error.to_string())?;
        Ok(normalized_url(absolute.as_str()).map(|url| url.to_string()))
    }
}

struct Cache<T> {
    values: HashMap<String, T>,
    order: VecDeque<String>,
}

impl<T> Default for Cache<T> {
    fn default() -> Self {
        Self {
            values: HashMap::new(),
            order: VecDeque::new(),
        }
    }
}

impl<T: Clone> Cache<T> {
    fn get(&self, key: &str) -> Option<T> {
        self.values.get(key).cloned()
    }

    fn insert(&mut self, key: String, value: T) {
        if !self.values.contains_key(&key) {
            self.order.push_back(key.clone());
        }
        self.values.insert(key, value);
        while self.values.len() > CACHE_LIMIT {
            if let Some(oldest) = self.order.pop_front() {
                self.values.remove(&oldest);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cache_evicts_the_oldest_entry() {
        let mut cache = Cache::default();
        for index in 0..=CACHE_LIMIT {
            cache.insert(index.to_string(), index);
        }
        assert_eq!(cache.get("0"), None);
        assert_eq!(cache.get(&CACHE_LIMIT.to_string()), Some(CACHE_LIMIT));
    }
}
