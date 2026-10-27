# Orchard Qobuz provider

This is the Qobuz integration migrated from `orchardv2`. Its runtime code is
standard ECMAScript and has no Node, Electron, DOM, filesystem, or HTTP-server
dependency.

The host supplies:

- a Fetch-compatible `fetchImpl`;
- credentials from platform-secure storage;
- `randomUuid()`;
- synchronous `hkdfSha256`, `decryptAes128Cbc`, and `decryptAes128Ctr`
  capabilities when encrypted playback is used.

Catalog lookup, matching, album quality, playback-session renewal, reporting,
CMAF parsing, and segmented FLAC assembly remain in this package. `readRange()`
returns a requested range of the logical FLAC stream as `Uint8Array`.
Range reads locate the seek span in the segment table and fetch only its segments
in batches of up to four. Overlapping reads share downloads and session renewal;
the decrypted segment cache holds four segments.
Credentials may carry the user's own `appId` and `appSecret`. Then the client
skips the web-player bootstrap, signs `track/getFileUrl` with that secret, and
`src/direct.js` serves the returned FLAC file through HTTP Range requests.
`readChunk()` yields the FLAC metadata or one audio segment as soon as it is ready. The
desktop and Android loopback servers use it to avoid holding ready audio behind
later downloads, keeping up to four requested segments in flight. Android
schedules playback reporting independently of audio reads.

## Desktop runtime

`src/runtime.js` is the QuickJS entry point. CMake bundles it with the YouTube
provider's esbuild and compiles it to `qobuz-provider.qjc`, which runs on its own
provider thread (`QobuzService`), so audio reads never wait behind YouTube work.
The shared provider host supplies `fetch` with native `ArrayBuffer` bodies,
passes a top-level `Uint8Array` result back as raw bytes, and implements the
crypto capabilities in Rust (`orchard_hkdf_sha256`, `orchard_aes128_*`). The
playback proxy serves the FLAC to Qt Multimedia on a loopback URL, reading it
through `playback.readChunk` with a 512 KiB maximum read size.

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

The synthetic startup/seek benchmark uses 512 KiB reads and a fixed 40 ms
download delay per 128 KiB segment. The first-audio benchmark makes later
segments take 200 ms to check that they do not hold up ready audio. It also
measures clear frame assembly; it does not measure codec decoding or real
Qobuz network latency.

```sh
node bench/playback.mjs
```

Pass another checkout's `src` directory as an argument to compare implementations.
