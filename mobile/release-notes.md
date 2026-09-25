## Orchard Mobile 2.0.0-beta.7 "Gliders Dungarees"

### Added
- **Desktop Transition Parity**: Mobile now runs the shared desktop transition planner with the same cue selection, tempo ratios, choreography, and fallback policy, backed by generated parity fixtures.
- **Cache Controls**: Added cache size reporting and a confirmation flow to clear temporary artwork, audio, network, and stream caches without touching downloads or library data.
- **Artist Actions**: Added artist credits in playback and follow/unfollow controls from artist and track surfaces.

### Changed
- **Listening Experience**: Refined Home, Library, detail, queue, now-playing, lyrics, device, integration, and settings screens with consistent responsive surfaces and bundled Inter typography.
- **Beat Analysis**: Shipped the official `final0` Beat This dynamic INT8 model with fixed 1500-frame windows, CPU execution, versioned extraction, and bounded audio work.
- **Crossfade Engine**: Shared transition planning now preserves selected cues, tempo ratios, choreography, and fallback behavior while rendered playback keeps its full-song source clock.

### Fixed
- **Rendered Crossfades**: Stabilized transition handoffs, preserved source position and duration, and prevented competing analysis and render jobs from disturbing playback timing.

### Maintenance
- **Android Runtime**: Updated to stock ONNX Runtime Android 1.29.0, kept production APKs ARM64-only, and removed the experimental QNN/HTP packaging path after benchmark validation.
- **Testing & Tooling**: Added transition parity, timeline, work-limiter, cache, and UI tests plus Beat This quantization and accuracy benchmarks.
