## Orchard 4.0.0-beta.3 "Mushroom Novitiates"

### New & improved
- Orchard can now connect to Spotify and play the animated Canvas loop for a track in place of static album art, falling back to the usual artwork whenever a Canvas is unavailable.

### Fixed
- Packages built against a system Electron runtime now locate the audio analyzer and both neural models, which had silently fallen back to heuristic analysis and disabled beat-matched transitions.
- Custom artist sound effects play again after the content security policy was widened to allow their audio data.
