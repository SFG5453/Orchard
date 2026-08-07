## Orchard Mobile 1.1.0 "Fiancee Batches"

### Features
- **Explicit Content & Badging**: Added parsing for explicit badges (`MUSIC_EXPLICIT_BADGE`), explicit track metadata support across models/UI, and JavaScript challenge solver integration for age-gated streams.
- **Playlist Management**: Added mobile playlist add/create actions, `PlaylistPickerSheet`, and catalog mutation endpoints.
- **Song Share & Links**: Enhanced song sharing sheet with direct Songlink integration and platform links.
- **Orchard Connect & Cloud Sync**: Added remote track analysis request/response protocol (v3) over paired desktop connections, along with Supabase sync integration.
- **Player UI Enhancements**: Rebuilt player components with tablet layout support (`TabletPlayer`), transition glow overlays, refined scrubber controls, and dedicated panel views.
- **Offline Downloads & Catalog Sheets**: Full offline media caching system and paginated catalog section sheets.

### Improvements & Fixes
- **Volume Normalization Engine**: Wired volume normalization toggle into audio settings and added DSP level adjustments in `TransitionFilter`.
- **Update Metadata & Codename**: Supported codename metadata field in update manager (`MobileUpdateMetadata`) and displayed codename in app settings.
- **Stream Cache Reliability**: Improved stream cache handling and corrected YouTube quality warning logic during fallback playback.
- **Smart Crossfade Tuning**: Integrated native WSOLA planner, best-mix track sorting, and refined transition planning/policy logic.
- **Debug Build Flavor**: Added coexisting `.debug` build variant with distinct application ID and app name.
