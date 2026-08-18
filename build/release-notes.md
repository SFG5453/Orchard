## Orchard 4.6.0 "Suicide Mutilators"

### Added
- **SponsorBlock**: SponsorBlock skipping is back with off, show-button, and automatic modes. Intro, outro, and music-offtopic segments are supported, with lyric timing adjusted when a skipped segment displaces the song.
- **Unified System Media Controls**: Linux MPRIS, Windows SMTC, and macOS Now Playing controls now share one native media service, including playback commands, metadata, seeking, repeat, shuffle, and clean shutdown behavior.

### Changed
- **Smaller Release Packages**: Desktop releases now ship only the target platform and architecture's ONNX Runtime CPU payload, omit unused execution-provider libraries, use the Intel macOS WebAssembly fallback, and use stronger RPM compression. Native media binaries are built and packaged for Linux, Windows, and macOS through the release pipeline.
