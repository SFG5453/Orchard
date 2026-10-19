/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Local adaptive mix preparation. Never runs on the audio callback.
//! Everything outside the `desktop` feature is shared with Android, which links it
//! through `orchard-transition-mobile`, and with the web build (`web`).
pub mod analysis;
pub mod best_mix;
pub mod best_mix_cost;
pub mod best_mix_order;
pub mod beats;
#[cfg(feature = "desktop")]
pub mod decode;
pub mod downmix;
pub mod evidence;
pub mod lock;
#[cfg(not(feature = "web"))]
pub mod models;
#[cfg(feature = "web")]
#[path = "models_web.rs"]
pub mod models;
pub mod planner;
pub mod prepare;
pub mod repeat;
pub mod song;
#[cfg(feature = "desktop")]
pub mod text_embed;
