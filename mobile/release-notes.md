## Orchard Mobile 1.2.1 "Presume Recumbent"

### Features
- **Orchard Canary**: Orchard Canary builds are now available.

### Fixes
- **Discord Presence**: Presence dropped artwork, went stale, or stopped entirely. Artwork was sent as a raw URL where Discord only accepts an asset key, so every state change replaced good artwork with a broken-image placeholder; asset keys are now cached and reused. Rapid play/pause blew past Discord's rate limit and stranded the profile on a stale state, so updates now coalesce to one every 4 seconds with the newest state winning. And once the access token aged out, presence stopped for good, and that case now refreshes the token and reconnects instead of giving up.
- **Playback Errors**: When a track failed to play, the app said nothing useful. It now shows the real reason.
- **Slow Track Loading**: A track that was slow rather than broken could sit in silence for up to a minute while stream resolution worked through its fallbacks. Each attempt is now capped at 10 seconds and the whole chain at 25.
- **Synced Lyrics Spacing**: Karaoke word timing could run words together, like "Iwalkedalone", for providers that put the separating space at the start of a syllable instead of the end. Full-line lyrics were never affected.
- **Volume Slider**: The slider thumb never lined up with the edge of the fill, because Material insets thumb travel by half the thumb width while the fill spans the whole track. The thumb is gone and the fill edge is now the position indicator; the full-height touch target and dragging are unchanged.
