## Orchard Mobile 2.0.0-beta.6 "Caryatid Winfred"

### Added
- **Qobuz Lossless Playback**: Connect a Qobuz account from Settings to match catalog recordings and stream CD-quality lossless or 24-bit Hi-Res audio with selectable quality and player quality badges.
- **Qobuz Account Controls**: Enable or disable Qobuz matching, disconnect the account, and choose the preferred streaming tier without changing Orchard's catalog or fallback behavior.

### Changed
- **Quality-Aware Playback**: At MAX quality, matched recordings can resolve through Qobuz while uploads, unmatched recordings, and authenticated YouTube tracks keep their existing playback paths.

### Maintenance
- **Regression Coverage**: Added Qobuz matching, authentication, CMAF streaming, and segment-cache tests.
- **Beat Model Benchmarking**: Extended Android instrumentation to benchmark the FP32 Beat This model through the mobile WebGPU provider while retaining the existing mobile model path.
