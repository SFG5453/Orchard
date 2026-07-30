## Orchard 4.0.0-beta.2 "Resells Citibank"

### New & improved
- The new Canopy layout preset introduces a docked player and a compact header to improve vertical screen space, alongside a redesigned onboarding flow for interface selection.
- Smart Crossfade replaces WSOLA and heuristic metering with ONNX neural models for beat tracking and vocal separation, using Essentia to calibrate transition confidence.
- Smart transitions now evaluate vocal activity per frame to keep incoming vocals out of the overlap, halving transition lengths and counting them in beats.

### Fixed
- Last.fm API calls correctly use POST for authenticated requests and forward the user-agent.
- Arch Linux packages now generate valid version strings, and Windows prerelease builds use GitHub-safe update manifests.
- The interface prioritizes channel handles in account summaries, resolves premature ellipsis on paused long lyric lines, and adds fluid transition animations for navigation and queue changes.
- Album metadata parsing has been patched to handle recent upstream YouTube changes.

### Maintenance
- Orchard has been relicensed to AGPL-3.0-or-later.
