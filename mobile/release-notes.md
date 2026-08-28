## Orchard Mobile 2.0.0-beta.3 "Kimberly Illegal"

### Added
- **Connect Reverse Playback**: Paired devices can now send playback commands back to one another with negotiated protocol capabilities.
- **Collection Search**: Added collection search across mobile library and detail surfaces.
- **Album Best Mix**: Best Mix is now available from album views while preserving native gapless album playback when requested.

### Changed
- **Media Controls**: External accessory, smartwatch, and notification media controls now prioritize skip actions and respond more quickly.
- **Playback Persistence**: Playback and widget persistence work moves off the main thread, with redundant layout broadcasts avoided.
- **Mini Player**: A vertical swipe now dismisses the mini player and clears the playback queue.

### Fixed
- **Connect Compatibility**: Playback state no longer silently renegotiates or downgrades an established Connect session.

### Maintenance
- Added Rust and `cargo-ndk` setup to the Android canary and release build workflows.
