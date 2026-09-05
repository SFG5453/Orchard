## Orchard 5.0.0-beta.7 "Tiles Dogcart"

### New & improved
- **Fullscreen Player**: Redesigned the fullscreen experience with artwork-led visuals, responsive desktop and mobile layouts, unified lyrics, richer queue controls, and virtualized long queues.
- **Album Details**: Added artwork-derived accent palettes, ambient album and video presentation, and more expressive responsive visuals.
- **Customizable Layouts**: Home sections and sidebar items can now be reordered, hidden, persisted, and reset from Settings.
- **Orchard Packages Installers**: Added Debian/RPM and Windows NSIS installers, with managed package upgrades that replace direct Orchard installations.
- **Package Lifecycle**: Added shared Electron runtime reuse, package size reporting, managed release opening, and per-version uninstallation.

### Changed
- **Native Audio**: Unified desktop audio analysis and transition rendering in the Earmark native runtime.
- **Native Packaging**: Expanded platform-native asset collection for cross-platform Sharp binaries, Windows runtime DLLs, icons, and packaged release entry points.
- **Lyrics**: The desktop sidebar and fullscreen player now share the same synchronized lyrics presentation, seeking behavior, and word-level progress visuals.

### Fixed
- **Playlist Shuffle**: Starting playback from a shuffled playlist no longer discards tracks that remain after the selected track.
- **Fullscreen Queue**: Fixed queue performance for large lists and kept artwork synchronized while seeking.
- **Windows Packages**: Fixed launcher paths by safely terminating app directories, required `welcome.html` during validation, and added compatibility for legacy beta 6 layouts.

### Maintenance
- **Dependencies**: Updated Electron to 43.6.0, Quasar to 2.30.0, HLS.js to 1.7.2, Zod to 4.5.4, and the Undici override to 6.28.0; added Sharp and OGL for artwork visuals and removed the obsolete node-gyp and node-addon-api toolchain.
- **Regression Coverage**: Added tests for package installation, uninstallation, launchers, Electron runtime handling, legacy layouts, and cross-platform native assets.
