## Orchard Mobile 1.6.0 "Pewee Lemonades"

### New & improved
- **Faster, Resumable Downloads**: Orchard can download more tracks at once, resume interrupted transfers, and use bounded parallel ranges for Max-quality audio when the source supports them.
- **Release Notes and Update Controls**: A redesigned update experience shows structured release notes, exposes the installed version, and adds manual update checks and install actions to Settings.

### Fixed
- **More Reliable Playback and Downloads**: Orchard now rotates through coordinated YouTube client profiles and carries each profile's required request identity into media fetches when a stream is rejected.
- **Safer Download Recovery**: Downloads validate byte ranges and content lengths, preserve compatible partial files after interruptions, and fall back safely when a server rejects parallel transfers.
- **Screen Timeout with Animated Artwork**: Animated artwork no longer keeps the display awake when the normal Android screen timeout should turn it off.
