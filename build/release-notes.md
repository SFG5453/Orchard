## Orchard 3.0.3 "Boulevard Heiresses"

### New & improved
- Welcomed Julian Ramierez as an Orchard tester and the new mobile app developer. (coming soon)
- Best Mix now uses catalog BPM and key metadata from GetSongBPM to supplement local analysis, enabling Best Mix for tracks that can't be analyzed locally.
- Parallel range-based audio fetching for faster analysis performance.

### Fixed
- Fixed YouTube Music API requests failing with 401 errors by adding automatic re-authentication and expanding proxy support to all /youtubei/ endpoints.
- Replaced the fake Youtube Shuffle All button with a real shuffle button in the Library songs menu.
- Fixed BPM and confidence handling across multiple Best Mix modules.