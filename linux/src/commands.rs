use anyhow::{Context, Result};
use serde_json::Value;
use tauri::{Manager, State};

use crate::discord::{DiscordPresence, DiscordPresencePayload};
use crate::media::{AudioDeck, AudioEngineConfig, AudioSnapshot, NativeAudio};
use crate::session_state::SessionState;
use crate::system_media::{SystemMedia, SystemMediaState};
use crate::transitions::{TransitionOptions, TransitionResult, TransitionSource};

#[tauri::command]
fn renderer_error(message: String) {
    eprintln!("renderer startup error: {message}");
}

#[tauri::command]
async fn discord_set_presence(
    discord: State<'_, DiscordPresence>,
    presence: DiscordPresencePayload,
) -> Result<(), String> {
    discord.set(presence).await;
    Ok(())
}

#[tauri::command]
async fn discord_clear_presence(discord: State<'_, DiscordPresence>) -> Result<(), String> {
    discord.clear().await;
    Ok(())
}

#[tauri::command]
async fn auth_status(app: tauri::AppHandle) -> Result<Value, String> {
    crate::auth::status(&app).await
}

#[tauri::command]
async fn auth_login(app: tauri::AppHandle) -> Result<Value, String> {
    crate::auth::login(&app, false).await
}

#[tauri::command]
async fn auth_switch_account(app: tauri::AppHandle) -> Result<Value, String> {
    crate::auth::login(&app, true).await
}

#[tauri::command]
async fn auth_logout(app: tauri::AppHandle) -> Result<Value, String> {
    crate::auth::logout(&app).await
}

#[tauri::command]
async fn audio_load(
    audio: State<'_, NativeAudio>,
    deck: AudioDeck,
    source: String,
) -> Result<AudioSnapshot, String> {
    audio.load(deck, source).await
}

#[tauri::command]
fn audio_play(audio: State<'_, NativeAudio>, deck: AudioDeck) -> Result<(), String> {
    audio.play(deck)
}

#[tauri::command]
fn audio_pause(audio: State<'_, NativeAudio>, deck: AudioDeck) -> Result<(), String> {
    audio.pause(deck)
}

#[tauri::command]
fn audio_clear(audio: State<'_, NativeAudio>, deck: AudioDeck) -> Result<(), String> {
    audio.clear(deck)
}

#[tauri::command]
fn audio_seek(audio: State<'_, NativeAudio>, deck: AudioDeck, seconds: f64) -> Result<(), String> {
    audio.seek(deck, seconds)
}

#[tauri::command]
fn audio_set_volume(
    audio: State<'_, NativeAudio>,
    deck: AudioDeck,
    volume: f32,
) -> Result<(), String> {
    audio.set_volume(deck, volume)
}

#[tauri::command]
fn audio_set_rate(audio: State<'_, NativeAudio>, deck: AudioDeck, rate: f32) -> Result<(), String> {
    audio.set_rate(deck, rate)
}

#[tauri::command]
fn audio_state(audio: State<'_, NativeAudio>, deck: AudioDeck) -> Result<AudioSnapshot, String> {
    audio.state(deck)
}

#[tauri::command]
fn audio_set_engine_config(
    audio: State<'_, NativeAudio>,
    config: AudioEngineConfig,
) -> Result<(), String> {
    audio.set_engine_config(config)
}

#[tauri::command]
fn audio_set_auto_eq_gains(audio: State<'_, NativeAudio>, gains: Vec<f32>) -> Result<(), String> {
    audio.set_auto_eq_gains(gains)
}

#[tauri::command]
fn audio_set_track_gain(
    audio: State<'_, NativeAudio>,
    deck: AudioDeck,
    gain_db: f32,
) -> Result<(), String> {
    audio.set_track_gain(deck, gain_db)
}

#[tauri::command]
fn session_state_all(session: State<'_, SessionState>) -> Result<Value, String> {
    serde_json::to_value(session.all()?).map_err(|error| error.to_string())
}

#[tauri::command]
fn session_state_set(
    session: State<'_, SessionState>,
    key: String,
    value: Value,
) -> Result<bool, String> {
    session.set(key, value)
}

#[tauri::command]
fn system_media_set(media: State<'_, SystemMedia>, state: SystemMediaState) -> Result<(), String> {
    media.set_state(state)
}

#[tauri::command]
async fn transition_render(
    outgoing: TransitionSource,
    incoming: TransitionSource,
    options: TransitionOptions,
) -> Result<TransitionResult, String> {
    tokio::task::spawn_blocking(move || crate::transitions::render(outgoing, incoming, options))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
fn window_minimize(window: tauri::WebviewWindow) -> Result<bool, String> {
    window.minimize().map_err(|error| error.to_string())?;
    window.is_minimized().map_err(|error| error.to_string())
}

#[tauri::command]
fn window_toggle_maximize(window: tauri::WebviewWindow) -> Result<bool, String> {
    if window.is_maximized().map_err(|error| error.to_string())? {
        window.unmaximize().map_err(|error| error.to_string())?;
    } else {
        window.maximize().map_err(|error| error.to_string())?;
    }
    window.is_maximized().map_err(|error| error.to_string())
}

#[tauri::command]
fn window_set_fullscreen(window: tauri::WebviewWindow, fullscreen: bool) -> Result<bool, String> {
    window
        .set_fullscreen(fullscreen)
        .map_err(|error| error.to_string())?;
    window.is_fullscreen().map_err(|error| error.to_string())
}

#[tauri::command]
fn window_close(window: tauri::WebviewWindow) -> Result<(), String> {
    window.close().map_err(|error| error.to_string())
}

pub fn run() -> Result<()> {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .setup(|app| {
            let session_path = app
                .path()
                .app_data_dir()
                .context("could not resolve the Orchard app-data directory")?
                .join("session-state.json");
            app.manage(SessionState::open(session_path));
            app.manage(NativeAudio::new()?);
            app.manage(SystemMedia::new(app.handle().clone()));
            app.manage(DiscordPresence::new()?);

            // WebKitGTK's wheel interpolation can queue high-resolution wheel
            // deltas and release them in uneven bursts. Chromium handles that
            // input itself, so keep this override confined to the Linux webview.
            let main_window = app
                .get_webview_window("main")
                .context("main webview was not created")?;
            main_window
                .with_webview(|webview| {
                    use webkit2gtk::{SettingsExt, WebViewExt};

                    if let Some(settings) = webview.inner().settings() {
                        settings.set_enable_smooth_scrolling(false);
                    }
                })
                .context("failed to configure main webview scrolling")?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            renderer_error,
            discord_set_presence,
            discord_clear_presence,
            auth_status,
            auth_login,
            auth_switch_account,
            auth_logout,
            audio_load,
            audio_play,
            audio_pause,
            audio_clear,
            audio_seek,
            audio_set_volume,
            audio_set_rate,
            audio_state,
            audio_set_engine_config,
            audio_set_auto_eq_gains,
            audio_set_track_gain,
            session_state_all,
            session_state_set,
            system_media_set,
            transition_render,
            window_minimize,
            window_toggle_maximize,
            window_set_fullscreen,
            window_close
        ])
        .run(tauri::generate_context!())
        .context("Tauri event loop failed")?;
    Ok(())
}
