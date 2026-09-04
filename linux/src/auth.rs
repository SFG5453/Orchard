use std::time::Duration;

use serde_json::{Value, json};
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

const MUSIC_URL: &str = "https://music.youtube.com/";
const ACCOUNT_URL: &str =
    "https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Fmusic.youtube.com%2F";

pub async fn status(app: &AppHandle) -> Result<Value, String> {
    let cookie = collect_cookie(app, false, MUSIC_URL)
        .await?
        .unwrap_or_default();
    Ok(json!({ "cookie": cookie }))
}

pub async fn login(app: &AppHandle, switch: bool) -> Result<Value, String> {
    let url = if switch { ACCOUNT_URL } else { MUSIC_URL };
    let cookie = collect_cookie(app, true, url)
        .await?
        .ok_or_else(|| "YouTube Music sign-in was cancelled.".to_string())?;
    Ok(json!({ "cookie": cookie }))
}

pub async fn logout(app: &AppHandle) -> Result<Value, String> {
    let window = auth_window(app, false, MUSIC_URL)?;
    tokio::time::sleep(Duration::from_millis(400)).await;
    window
        .clear_all_browsing_data()
        .map_err(|error| error.to_string())?;
    let _ = window.close();
    Ok(json!({ "cookie": "" }))
}

async fn collect_cookie(
    app: &AppHandle,
    visible: bool,
    start_url: &str,
) -> Result<Option<String>, String> {
    let window = auth_window(app, visible, start_url)?;
    let attempts = if visible { 300 } else { 4 };
    for _ in 0..attempts {
        tokio::time::sleep(Duration::from_secs(1)).await;
        if app.get_webview_window("youtube-auth").is_none() {
            return Ok(None);
        }
        let cookies = window
            .cookies_for_url(MUSIC_URL.parse().map_err(|error| format!("{error}"))?)
            .map_err(|error| error.to_string())?;
        let header = cookies
            .iter()
            .map(|cookie| format!("{}={}", cookie.name(), cookie.value()))
            .collect::<Vec<_>>()
            .join("; ");
        if has_login_cookie(&header) {
            let _ = window.close();
            return Ok(Some(header));
        }
    }
    let _ = window.close();
    Ok(None)
}

fn auth_window(app: &AppHandle, visible: bool, url: &str) -> Result<WebviewWindow, String> {
    if let Some(existing) = app.get_webview_window("youtube-auth") {
        let _ = existing.close();
    }
    WebviewWindowBuilder::new(
        app,
        "youtube-auth",
        WebviewUrl::External(url.parse().map_err(|error| format!("{error}"))?),
    )
    .title("Sign in to YouTube Music")
    .inner_size(520.0, 720.0)
    .resizable(true)
    .visible(visible)
    .on_navigation(|url| {
        url.host_str().is_some_and(|host| {
            host == "youtube.com"
                || host.ends_with(".youtube.com")
                || host == "google.com"
                || host.ends_with(".google.com")
                || host.ends_with(".googleusercontent.com")
                || host.ends_with(".gstatic.com")
        })
    })
    .build()
    .map_err(|error| error.to_string())
}

fn has_login_cookie(cookie: &str) -> bool {
    cookie
        .split(';')
        .map(|part| part.trim().split('=').next().unwrap_or_default())
        .any(|name| name == "SAPISID" || name == "__Secure-3PAPISID")
}
