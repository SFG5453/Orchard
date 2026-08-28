## Orchard 5.0.0-beta.5 "Teacup Obeisant"

### New & improved
- **Data Saving**: Added settings for controlling playback, artwork, artist previews, and other network-heavy features together.
- **Queue to Playlist**: Added actions for saving the current queue to a playlist from the queue and fullscreen player.
- **Connect**: Added reverse playback commands between paired devices and collection search from the remote experience.
- **Fullscreen Player**: Redesigned the fullscreen player and queue with richer artwork presentation, clearer controls, improved responsive behavior, and reduced-motion support.

### Changed
- Softened the desktop visual hierarchy across the shell, home, search, cards, player bars, overlays, and sidebars.
- Reduced immersive background rendering cost while preserving the artwork-driven presentation.

### Fixed
- **Welcome Window**: Repaired welcome-window controls, sizing, and settings synchronization.
- **Package Installation**: Preserved safe Electron archive symlinks during package installation.

### Maintenance
- Streamlined desktop packaging and audio runtime handling, removed the obsolete Windows launcher, and updated Vue to 3.5.42.
