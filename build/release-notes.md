## Orchard 5.0.0-beta.1 "Aerie Hymned"

### New & improved
- **Native Transition Engine**: Integrated the `earmark` transition engine via a native N-API Rust module (`native-audio-rust`), bringing high-performance beat grid alignment, energy and loudness analysis, and constraint-based DJ transition planning to desktop crossfades.

### Fixed
- **Beta Channel Updates**: Update checks on the beta channel now gracefully handle missing release manifests, reporting when Orchard is up to date or reminding you when the corresponding stable version has released so you can switch to the release channel.

### Maintenance
- Updated Vite to 8.2.2 and `sass-embedded` to 1.103.1.

