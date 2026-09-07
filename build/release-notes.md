## Orchard 5.0.0-beta.8 "Attained Silts"

### New & improved
- **Qobuz Playback**: Connect a Qobuz account and use confidently matched recordings as an optional CD-quality lossless or Hi-Res source, with selectable quality and YouTube fallback.
- **Accelerated Beat Detection**: Beat This now runs the full FP32 model through ONNX Runtime WebGPU on supported systems, with CPU/WASM fallbacks when WebGPU is unavailable.

### Changed
- **Analysis Cache**: Reworked persisted audio analysis into a compact SQLite cache with a small hot in-memory LRU, version cleanup, and migration from the legacy JSON cache to reduce memory use.
- **Audio Features**: Smart Crossfade and EQ are now mutually exclusive so equalization cannot interfere with transition analysis; playback quality labels identify Qobuz lossless and Hi-Res streams.

### Fixed
- **Playback Resilience**: Qobuz matching only replaces a stream when it identifies the same recording confidently, while unsupported WebGPU targets and missing beat models continue through the existing fallback paths.

### Maintenance
- **Dependencies and Packaging**: Updated Quasar to 2.30.1, added the shared Qobuz package to production packaging, and switched the shipped desktop beat model to the self-contained FP32 artifact.
- **Regression Coverage**: Added coverage for Qobuz authentication and streaming, audio-analysis cache persistence, WebGPU compatibility, playback quality labels, and crossfade/EQ compatibility.
