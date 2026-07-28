## Orchard 4.0.0-beta.1 "Popularly Mpeg"

### New & improved
- Smart Crossfade can now render compatible beat-matched transitions with a native, pitch-preserving WSOLA engine. Mixes align on a shared beat grid, keep bass handoffs steady, and animate artwork changes in fullscreen.
- The optional Continuous queue combines playback history, the current track, and upcoming songs in one layout. It supports jumping to or removing entries in the queue page, right panel, and fullscreen player, and keeps shared history synchronized in listening parties.
- Graphics settings now offer Automatic and Integrated GPU modes, with low-power GPU selection and guided app restarts after a mode change.
- You can opt into beta updates from Settings. Beta builds are delivered through GitHub Releases while the stable channel remains unchanged.

### Fixed
- YouTube Brand Account switching now handles expected Google redirects, detects account and delegated-session changes, refreshes the active profile immediately, and remembers the selected browser identity across restarts.
- Search now supplements broad results with dedicated song and artist matches, then ranks exact matches by relevance and catalog popularity.
- Smart Crossfade now prevents competing transition engines and queue edits from corrupting an active mix, avoids comb filtering at handoffs, preserves supported audio processing, and bounds long pre-rolls and overlaps.
- Immersive backgrounds stop unnecessary GPU rendering when animation is disabled, playback is paused, or Orchard is hidden, and Linux restores the original GPU environment when returning to Automatic mode.
- Continuous queues keep skipped tracks out of playback history and preserve host-controlled navigation and shared history during listening parties.

### Maintenance
- Updated Quasar to 2.23.2.
