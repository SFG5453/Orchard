# Orchard Qobuz provider

This is the Qobuz integration migrated from `orchardv2`. Its runtime code is
standard ECMAScript and has no Node, Electron, DOM, filesystem, or HTTP-server
dependency.

The host supplies:

- a Fetch-compatible `fetchImpl`;
- credentials from platform-secure storage: the user's own Qobuz `appId` and
  `appSecret`, plus a user ID and auth token issued to that app;
- `randomUuid()`.

Orchard ships no Qobuz app credentials and does not use the Qobuz web player's.
Without the user's own app ID and secret the provider stays disconnected.

Catalog lookup, matching, album quality and reporting remain in this package.
Playback signs `track/getFileUrl` with the app secret and `src/direct.js` reads
the returned FLAC file through HTTP Range requests, renewing an expired URL.
`readChunk()` returns at most 512 KiB so the loopback servers can hand ready
audio to the player while later bytes load. Android schedules playback
reporting independently of audio reads.

## Desktop runtime

`src/runtime.js` is the QuickJS entry point. CMake bundles it with the YouTube
provider's esbuild and compiles it to `qobuz-provider.qjc`, which runs on its own
provider thread (`QobuzService`), so audio reads never wait behind YouTube work.
The shared provider host supplies `fetch` with native `ArrayBuffer` bodies and
passes a top-level `Uint8Array` result back as raw bytes. The playback proxy
serves the FLAC to Qt Multimedia on a loopback URL, reading it through
`playback.readChunk` with a 512 KiB maximum read size.

QuickJS has no `URL` or `URLSearchParams`; `src/query.js` covers the query
strings this package builds and reads. MD5 request signing is implemented
locally in ECMAScript because Web Crypto does not provide MD5.

This is an unofficial integration and is not affiliated with or endorsed by
Qobuz. It requires a valid account and appropriate access to the requested
content. Private APIs and media formats can change without notice.

## Tests

Node is used only as a development test runner:

```sh
npm test
```

