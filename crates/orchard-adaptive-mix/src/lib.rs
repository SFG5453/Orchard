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

//! Local adaptive mix preparation. Never runs on the audio callback.
//! Everything outside the `desktop` feature is shared with Android, which links it
//! through `orchard-transition-mobile`.
pub mod analysis;
pub mod best_mix;
pub mod beats;
#[cfg(feature = "desktop")]
pub mod decode;
pub mod downmix;
pub mod evidence;
pub mod lock;
pub mod models;
pub mod planner;
pub mod prepare;
pub mod repeat;
pub mod song;
#[cfg(feature = "desktop")]
pub mod text_embed;
