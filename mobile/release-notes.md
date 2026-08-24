## Orchard Mobile 2.0.0-beta.2 "Disallow Thermoses"

### Added
- **Best Mix**: Added mobile Best Mix with offline audio analysis, cloud synchronization, and transition-aware queue sorting.
- **Queue Controls**: Added Best Mix, Autoplay, and Sleep Timer controls to the redesigned queue header.
- **Catalog Browsing**: Expanded search and added multi-section catalog browse pages.
- **Adaptive Navigation**: The frosted bottom navigation bar now samples colors from the current album artwork.

### Changed
- Mobile now executes the same exact staged transition choreography as desktop.
- Home prioritizes saved playlists and albums while catalog songs and related shelves play directly.
- Audio decoding now resamples during decode and reuses vocal-model inference buffers for better performance.

### Fixed
- Improved transition timing, beat-grid agreement, cue boundaries, vocal-collision handling, and audible track-tail protection.
- Fixed explicit-safe audio version resolution and clipped animated artwork to its container.
- Removed the duplicate cloud audio-analysis setting.

### Maintenance
- Updated cloud analysis to schema version 12 and bumped `@xmldom/xmldom` to 0.9.12.
