## Orchard Mobile 2.0.0-beta.4 "Boogieman Elliot"

### Added
- **Square Now Playing Artwork**: Non-animated artwork now appears as a centered square card with rounded corners, a soft shadow, and smooth track transitions.
- **Shared Rust Audio Analysis**: Mobile audio analysis now runs through the shared Rust/Earmark analyzer while keeping the trained beat-model input contract intact.
- **Quality-Aware Public Playback**: Public YouTube streams now use NewPipe across all quality tiers while preserving Innertube fallbacks for private and account-only tracks.

### Changed
- **Best Mix Preparation**: Download validation, progress reporting, and local analysis now agree on which tracks have usable files before sorting.

### Fixed
- **Playlist Picker**: Long playlist lists can now be scrolled inside the add-to-playlist sheet.
- **Stale Downloads**: Missing or empty files no longer remain marked as completed and are automatically eligible for re-download.
- **Best Mix Resilience**: A decoder or native-analysis failure no longer prevents a collection from playing; Orchard falls back to the original order with a warning.

### Maintenance
- Replaced the retired mobile C++ analysis JNI bridge with the shared Rust/Earmark library and refreshed mobile documentation and screenshots.
