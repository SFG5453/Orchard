## Orchard 4.7.0 "Epiphanies Hesitated"

### Added
- **Volume Wheel**: The mouse wheel now adjusts volume while the pointer is over the volume controls.
- **Canopy Readout**: The Canopy titlebar readout shows the playing track's bitrate and carries its own Liked Songs toggle beside the song actions button.

### Fixed
- **Blank Home After The Queue**: The queue and podcast views render from a single root again, so AppFrame's view transition can run its leave hook instead of wedging and leaving Home blank until another view forced it through.
- **Canopy Queue Spacing**: Windowed queue rows are positioned absolutely again rather than being pushed a row height apart by the layout's `position: relative` rule.

### Changed
- **Release Pipeline**: The arm64 and macOS jobs now verify the architecture of the native media addon alongside the audio analyzer, so a mismatched system-media binary fails the build instead of shipping.
- Electron is updated to 43.4.1.
