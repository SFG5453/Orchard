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

//! Orchard's Android native library. Adaptive mixes and Best Mix run the desktop worker's own
//! Rust (`mix`); the QuickJS provider host resolves YouTube player code through the extractor.
//! A rule that lives here rather than in a shared crate is a rule only Android obeys.

// The QuickJS provider host resolves these by name from this library.
pub use orchard_youtube_extractor::ffi::{orchard_youtube_extract, orchard_youtube_extract_free};

mod mix;
