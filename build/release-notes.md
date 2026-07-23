## Orchard 3.2.0 "Astrology Tenn"

### New & improved
- Smart Crossfade analysis now prioritizes current and upcoming tracks, runs work concurrently without duplicate jobs, retries temporary network failures, and caches validated native or worker results.
- Best Mix now favors local audio analysis, uses confidence-aware BPM and key metadata, and automatically re-sorts when tracks are added to the queue.
- Beat-matched transitions now align incoming drops more accurately with finer tempo adjustments and safer Web Audio automation.

### Fixed
- Song cache writes no longer interrupt playback when storage is slow or unavailable. Orchard also limits cache write lag, removes abandoned partial files, and prevents duplicate writes.
- Desktop media widgets now refresh track metadata after automatic track changes without sending redundant updates.
- Playback proxy retries now preserve the requested stream format instead of silently substituting a different format.
- The Support view's Current issues link now opens the [public Orchard issue tracker](https://github.com/SFG5453/Orchard/issues).

### Maintenance
- Updated the maintainer email used by Linux packages.
