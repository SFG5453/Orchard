## Orchard 5.0.0-beta.6 "Promos Imbecile"

### New & improved
- **Offline Music Downloads**: Download individual songs or full albums, artists, and playlists for playback without a network connection. Downloads have dedicated management, remain separate from the replay cache, and can be browsed while Orchard is offline.
- **Accessible Collection Actions**: Browse detail actions now have visible labels, descriptive accessible names, and clearer responsive styling across artist and collection views.

### Changed
- **Welcome Renderer**: Moved the welcome and setup experience into a standalone renderer that loads only the onboarding state it needs while preserving preferences, sign-in, Orchard Connect, and diagnostics.

### Fixed
- **Windows Packages**: Corrected launcher path resolution across installed Orchard Packages layouts and added coverage for package asset and opener behavior.

### Maintenance
- **Native Audio**: Removed the retired Rubber Band native renderer and vendored sources now that Earmark handles desktop and mobile transitions, reducing native build and package overhead.
- **Packaging and Dependencies**: Slimmed production package payloads, improved platform-native asset selection, updated Quasar to 2.28.0 and Zod to 4.5.2, and restored complete AGPL headers in the welcome renderer.
- **Documentation**: Refreshed the main and mobile documentation, feature presentation, and mobile screenshots.
