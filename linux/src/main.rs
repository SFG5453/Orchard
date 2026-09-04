#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod auth;
mod commands;
mod discord;
mod media;
mod session_state;
mod system_media;
mod transitions;

fn main() {
    if let Err(error) = commands::run() {
        eprintln!("Orchard failed to start: {error:#}");
        std::process::exit(1);
    }
}
