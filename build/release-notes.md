## Orchard 4.4.0 "Jocosity Jordanians"

### New & improved
- **Analysis-Aware Bass Handoffs**: Smart Crossfade now measures each track's low-frequency structure and moves the bass handoff to a suitable beat when it detects an incoming bass entrance or outgoing bass exit.
- **Interface Scaling**: Adjust text and interface size from 85% to 150%; Orchard remembers the setting between launches.
- **Artist Subscriptions**: Subscribe to or unsubscribe from artists directly on their artist pages.
- **Queue on Narrow Layouts**: Open the Queue from the sidebar even when Orchard is using a narrow desktop layout.

### Fixed
- **Niri and Wayland Stability**: Orchard now respects Niri-managed window geometry, supports narrower tiled windows, and avoids fighting the compositor over restored bounds.
- **Immersive Background Resizing**: Artwork backgrounds recover correctly after compositor and viewport resizes.
- **Smoother Interface Scaling**: Scaling is applied after the slider is released, avoiding unnecessary resizing while it is being dragged.

### Changed
- **Simpler Settings**: Removed the redundant setup guide now that onboarding handles initial configuration.
