# Orchard YouTube provider

This package contains the platform-neutral YouTube catalog, authentication
signing, playback-format selection, stream-resolution helpers, history/likes,
and SponsorBlock logic migrated from orchardv2/electron.

Its runtime source has no Electron or Node built-in imports. YouTube.js remains
the protocol/parser implementation and will be bundled for Orchard's provider
JavaScript runtime. The host supplies Fetch and owns login UI, cookies,
credential persistence, range transport, decoding, and platform media control.

The desktop host lazily loads a separate QuickJS bytecode bundle for PO tokens.
BgUtils runs against a bundled JSDOM compatibility layer, with Qt providing
networking and timers. No browser process or Node runtime is launched. The
minter is reused until expiry, and tokens are cached by the resolved video ID.
Stream retries renew the attestation. The token is sent in the player request
and attached to the deciphered CDN URL, since a one-byte probe can succeed even
when subsequent audio chunks require a token.

`scripts/buildPoMinter.mjs` supplies JavaScript compatibility modules at build
time and excludes filesystem, subprocess, VM, canvas, and WebSocket hosts.
Page scripts and resource loading remain disabled. Dependency notices are
embedded alongside the minter bytecode as `youtube-po-minter-licenses.txt`.
