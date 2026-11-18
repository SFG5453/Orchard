/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

//! Orchard's Android native library. Adaptive mixes and Best Mix run the desktop worker's own
//! Rust (`mix`).
//! A rule that lives here rather than in a shared crate is a rule only Android obeys.

mod mix;
