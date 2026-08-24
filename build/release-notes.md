## Orchard 5.0.0-beta.3 "Curates Admixture"

### New & improved
- **Unified Best Mix**: Best Mix now ranks tracks by executable DJ choreography, with exact staged transition automation shared across the desktop and mobile playback engines.
- **New Updater**: Introduced the Orchard package manager and a GitHub-backed updater with managed Linux package downloads and automatic beta-channel selection.
- **Responsive Desktop**: Improved the player, title bar, sidebar, fullscreen player, and windowed layouts across screen sizes.

### Fixed
- **Transition Reliability**: Strengthened transition timing, beat-grid alignment, vocal-collision handling, cue boundaries, audible track-tail protection, and WSOLA source timeline mapping.
- **Analysis Performance**: Reduced repeated transition planning and audio-analysis overhead while rejecting stale cloud analysis results.

### Maintenance
- Added Arch Linux packaging, repaired Flatpak and Linux x64 builds, and bundled the C++ runtime required by the transition engine.
- Updated Quasar and `@xmldom/xmldom` dependencies.
