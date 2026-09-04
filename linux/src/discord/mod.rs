mod enrichment;
mod model;

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use discord_rich_presence::{DiscordIpc, DiscordIpcClient};

use enrichment::DiscordEnrichment;
pub use model::DiscordPresencePayload;
use model::{ActivityOptions, animated_artwork_url, build_activity};

const APPLICATION_ID: &str = "1531666622312353803";

#[derive(Clone)]
pub struct DiscordPresence {
    shared: Arc<Shared>,
}

struct Shared {
    client: Mutex<Option<DiscordIpcClient>>,
    enrichment: DiscordEnrichment,
    request_id: AtomicU64,
    last_error_at: Mutex<Option<Instant>>,
}

impl DiscordPresence {
    pub fn new() -> Result<Self> {
        Ok(Self {
            shared: Arc::new(Shared {
                client: Mutex::new(None),
                enrichment: DiscordEnrichment::new()
                    .context("could not create the Discord enrichment client")?,
                request_id: AtomicU64::new(0),
                last_error_at: Mutex::new(None),
            }),
        })
    }

    pub async fn set(&self, presence: DiscordPresencePayload) {
        let request_id = self.shared.request_id.fetch_add(1, Ordering::SeqCst) + 1;
        if !presence.has_title() {
            self.clear().await;
            return;
        }
        let shared = self.shared.clone();
        let immediate = presence.clone();
        let sent = tauri::async_runtime::spawn_blocking(move || {
            shared.send(&immediate, &ActivityOptions::default())
        })
        .await;
        match sent {
            Ok(Ok(())) => self.spawn_enrichment(presence, request_id),
            Ok(Err(error)) => self.shared.log_error(&error),
            Err(error) => self.shared.log_error(&error.to_string()),
        }
    }

    pub async fn clear(&self) {
        self.shared.request_id.fetch_add(1, Ordering::SeqCst);
        let shared = self.shared.clone();
        match tauri::async_runtime::spawn_blocking(move || shared.clear()).await {
            Ok(Ok(())) => {}
            Ok(Err(error)) => self.shared.log_error(&error),
            Err(error) => self.shared.log_error(&error.to_string()),
        }
    }

    fn spawn_enrichment(&self, presence: DiscordPresencePayload, request_id: u64) {
        let shared = self.shared.clone();
        tauri::async_runtime::spawn(async move {
            let animated_url = animated_artwork_url(&presence.animated_artwork_url);
            let (artwork_ready, song_link_url) = tokio::join!(
                shared.enrichment.warm_artwork(&animated_url),
                shared.enrichment.resolve_song_link(&presence)
            );
            if shared.request_id.load(Ordering::SeqCst) != request_id {
                return;
            }
            let options = ActivityOptions {
                artwork_url: if artwork_ready {
                    animated_url
                } else {
                    presence.artwork_url.clone()
                },
                song_link_url,
            };
            let update_shared = shared.clone();
            let update = tauri::async_runtime::spawn_blocking(move || {
                if update_shared.request_id.load(Ordering::SeqCst) != request_id {
                    return Ok(());
                }
                update_shared.send(&presence, &options)
            })
            .await;
            match update {
                Ok(Ok(())) => {}
                Ok(Err(error)) => shared.log_error(&error),
                Err(error) => shared.log_error(&error.to_string()),
            }
        });
    }
}

impl Shared {
    fn send(
        &self,
        presence: &DiscordPresencePayload,
        options: &ActivityOptions,
    ) -> Result<(), String> {
        let Some(activity) = build_activity(presence, options) else {
            return self.clear();
        };
        let mut slot = self.client.lock().unwrap();
        if slot.is_none() {
            let mut client = DiscordIpcClient::new(APPLICATION_ID);
            client.connect().map_err(|error| error.to_string())?;
            *slot = Some(client);
        }
        let result = slot
            .as_mut()
            .expect("Discord client was initialized")
            .set_activity(activity)
            .map_err(|error| error.to_string());
        if result.is_err() {
            close_client(&mut slot);
        }
        result
    }

    fn clear(&self) -> Result<(), String> {
        let mut slot = self.client.lock().unwrap();
        let Some(client) = slot.as_mut() else {
            return Ok(());
        };
        let result = client.clear_activity().map_err(|error| error.to_string());
        if result.is_err() {
            close_client(&mut slot);
        }
        result
    }

    fn log_error(&self, error: &str) {
        let mut last_error_at = self.last_error_at.lock().unwrap();
        if last_error_at.is_some_and(|last| last.elapsed() < Duration::from_secs(30)) {
            return;
        }
        *last_error_at = Some(Instant::now());
        eprintln!("Discord RPC unavailable: {error}");
    }
}

impl Drop for Shared {
    fn drop(&mut self) {
        if let Ok(mut slot) = self.client.lock() {
            close_client(&mut slot);
        }
    }
}

fn close_client(slot: &mut Option<DiscordIpcClient>) {
    if let Some(mut client) = slot.take() {
        let _ = client.close();
    }
}
