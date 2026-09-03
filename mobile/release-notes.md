## Orchard Mobile 1.9.1 "Journeyman Anthology"

### Fixed
- **Public Stream Playback**: Fixed public YouTube tracks failing with CDN errors by routing every quality tier through NewPipe while preserving the selected bitrate.
- **Public Stream Downloads**: Downloads now use the same quality-aware public resolver as playback, while account-only uploads retain the Innertube fallback.

