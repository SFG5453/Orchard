## Orchard 3.2.1 "Hounds Vicar"

### New & improved
- Support is now a full-width, two-pane workspace with more room to compose reports and review existing conversations.
- Sanitized diagnostics can now be collected, reviewed, and refreshed before submission, so the private attachment contains exactly the snapshot shown in Orchard.

### Fixed
- Linux media controls now publish their complete MPRIS interface and initial playback state before announcing the service, preventing Plasma and other clients from discovering an empty player.
- Pausing, seeking, skipping, or refreshing playback now cleanly cancels an active crossfade. In-flight transitions can no longer restart after cancellation, and volume changes remain synchronized across both decks.
- Resuming a persisted shuffled queue now preserves its saved order, while playing a track from history no longer enqueues the rest of listening history.

### Maintenance
- Resolved all dependency audit findings and removed the unused Node/Jimp image-processing chain from Orchard's browser palette extraction.
