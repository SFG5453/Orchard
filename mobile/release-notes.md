## Orchard Mobile 1.6.1 "Palmiest Addressees"

### Fixed
- **Uploaded Library Playback**: Uploaded and private library tracks now skip guest clients that cannot access them and reach signed-in playback with the account's visitor and channel delegation identity. The same path now retries only when refreshing the stream can help.
- **Progressive Collection Loading**: Collections show their first page immediately and fill in as more pages arrive. Endless mixes use a bounded paging budget, and opening another collection cancels the previous load.
- **Private Search History**: Internal song lookups used to resolve playback are now anonymous, so they no longer add searches to the listener's YouTube history.
