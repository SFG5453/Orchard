## Orchard Mobile 1.4.3 "Sheep Hydrants"

### Fixes
- **Full-Quality Explicit Tracks**: Explicit lyrics no longer force songs onto the roughly 69 kbps authenticated stream. Songs now use the normal high-quality route unless YouTube returns a real age restriction.
- **Age-Restricted Playback Recovery**: When a genuine age gate is detected, Orchard Mobile retries through authenticated direct playback and falls back to HLS only if needed.
