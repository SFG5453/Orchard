## Orchard 4.5.1 "Hellishly Code"

### New & improved
- **Network Controls**: Choose whether album art and update checks follow the system proxy or connect directly when an unreachable proxy prevents them from loading.
- **Orchard Connect Links**: Pairing now prefers a reachable LAN address and offers alternate network links when the computer has multiple adapters.
- **Full Queue Access**: Open the full queue directly from the compact queue panel.
- **Immersive Artwork Contrast**: The artwork veil now adapts to the cover's colors so light covers remain readable without unnecessarily obscuring darker artwork.

### Fixed
- **Cleaner Ordinary Playback**: Tracks no longer pass through the DJ crossover filters during normal playback, preventing phase-related transient clipping while preserving crossover automation for DJ transition styles.
- **Short Display Fullscreen Player**: The fullscreen transport and lyrics now fit on short displays instead of being clipped below the viewport.
- **Reliable Clipboard Actions**: Copy actions use the main-process bridge and browser fallbacks, fixing silent failures inside the desktop app.
- **International Catalog Entries**: Catalog matching now preserves titles and artist names written in non-Latin scripts.
