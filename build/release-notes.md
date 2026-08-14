## Orchard 4.4.1 "Hole Riddance"

### Fixed
- **Reliable Desktop Playback**: Restored direct YouTube Music playback after upstream protection changes caused tracks to fail with “no supported source” errors, including on macOS.
- **Full-Quality Direct Streams**: Direct audio now uses video-bound proof-of-origin tokens and concrete byte ranges, while HLS remains a fallback for authenticated age-gated playback.
- **Playlist Additions**: Songs can be added to existing YouTube Music playlists again.
