## Orchard 5.0.0-beta.9 "Mopping Blimpish"

### New & improved
- **Qobuz Catalog Quality**: Added normalized album and track quality metadata, including bit depth, sample rate, channel count, and streamability, with batched track lookups.
- **Shared Mobile Planning**: Mobile builds now bundle the desktop transition planner and generated parity fixtures so beat-matched choreography and fallback behavior stay aligned across platforms.

### Changed
- **Crossfade Fallbacks**: Standard non-beatmatched fades now honor the configured 1–12 second duration and keep outgoing and incoming entry cues stable, including on short tracks.

### Fixed
- **YouTube Sessions**: Persistent browser sessions now renew automatically for the selected Google account when cookies expire, with throttled background recovery and safe cleanup.
- **Playback Scheduling**: Rendered and fallback crossfades no longer lose incoming cue timing or overrun the usable audio window.

### Maintenance
- **Dependencies**: Updated @kawarp to 1.2.1, Electron to 43.7.0, Quasar to 2.32.0, Vite to 8.3.0, and Zod to 4.6.1; added Babel tooling for the shared planner build.
- **Regression Coverage**: Added coverage for browser session renewal, Qobuz catalog metadata and batching, and configured crossfade fallbacks.
