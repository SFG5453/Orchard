## Orchard 4.3.3 "Rundowns Tonic"

### New & improved
- **More Linux Packages**: Added x86_64 and ARM64 Flatpak bundles, plus a native Windows ARM64 installer and Linux ARM64 AppImage with architecture-aware update metadata.

### Fixed
- **Full-Quality Explicit Tracks**: Explicit lyrics no longer force songs through the lower-bitrate authenticated stream. Orchard now uses the normal high-quality route unless YouTube returns a real age restriction.
- **Playback Survives Stale Local Configuration**: YouTube session identities are refreshed on launch while player and sign-in caches are preserved, fixing "no playable audio format" failures that previously required deleting Orchard's local configuration directory.
- **Age-Restricted Playback Recovery**: Authenticated direct and HLS playback are now reserved for confirmed age gates, with recovery state kept out of the persisted queue.
