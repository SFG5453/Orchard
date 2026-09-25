# @orchardmusic/qobuz

A host-neutral Node.js API for Qobuz catalog matching, playback sessions, reporting, and conversion of segmented Qobuz CMAF streams into seekable FLAC responses.

This is an unofficial integration and is not affiliated with or endorsed by Qobuz. It requires a valid Qobuz account and access appropriate to the content being played. Qobuz's private web APIs and media format can change without notice.

## Install

```sh
npm install @orchardmusic/qobuz
```

The package is ESM-only, requires Node.js 18 or newer, and has no runtime npm dependencies. The default entry point does not import Electron or any Orchard code.

## Quick start

The host supplies credentials and, optionally, its own `fetch` implementation. The returned service owns session renewal, catalog matching, reporting, and segmented playback state.

```js
import { createQobuz } from '@orchardmusic/qobuz';

const qobuz = createQobuz({
  credentials: async () => ({
    token: process.env.QOBUZ_USER_TOKEN,
    userId: Number(process.env.QOBUZ_USER_ID)
  }),
  logger: console,
  softwareVersion: 'ExamplePlayer/1.0.0'
});

const results = await qobuz.search('artist track');

const resolved = await qobuz.resolveTrack({
  title: 'Track title',
  artists: ['Artist name'],
  album: 'Album title',
  durationMs: 180_000,
  isrc: 'USABC1200001',
  explicit: false
}, 'hires');

if (resolved) {
  console.log(resolved.match);
  console.log(resolved.source);
}

await qobuz.close();
```

The package expects credentials obtained through an authorized Qobuz login flow. It intentionally does not ship copied application credentials.

### Building a login flow

Web and desktop hosts can construct their own redirect flow with the exported OAuth helpers:

```js
import {
  createQobuzAuthorizationUrl,
  exchangeQobuzAuthorizationCode,
  fetchQobuzBootstrap
} from '@orchardmusic/qobuz';

const web = await fetchQobuzBootstrap();
const loginUrl = createQobuzAuthorizationUrl({
  appId: web.appId,
  redirectUrl: 'https://player.example/qobuz/callback'
});

// Redirect the user to loginUrl. In the callback handler:
const credentials = await exchangeQobuzAuthorizationCode({
  code: authorizationCode,
  web
});
```

Credential storage, callback routing, CSRF protection, and browser navigation belong to the host. The optional Electron adapter provides one complete desktop implementation.

## Serving playback

`resolveTrack()` and `resolveStream()` return an opaque playback ID and FLAC metadata. Expose that ID through a host-owned HTTP route and delegate requests to `proxyStream()`:

```js
import { createServer } from 'node:http';

const server = createServer(async (request, response) => {
  const playbackId = new URL(request.url, 'http://localhost').pathname.slice(1);
  await qobuz.proxyStream(playbackId, request, response);
});

server.listen(3000);
```

The proxy supports `GET`, `HEAD`, `OPTIONS`, and single HTTP byte ranges. Call `playbackStarted(playbackId, positionSeconds)` and `playbackEnded(playbackId, positionSeconds)` when the player changes state so required playback reports reflect actual listening time.

## Core API

### `createQobuz(options)`

Creates the high-level service. Options:

- `credentials`: Required async function returning `{ token, userId }` or `null`.
- `fetchImpl`: Fetch-compatible network function; defaults to global `fetch`.
- `logger`: Object with optional `info` and `warn` methods; defaults to `console`.
- `softwareVersion`: Host identifier included in playback-end reports.
- `bootstrap`: Optional custom bootstrap loader with a `get()` method.
- `bootstrapMaxAgeMs`: Cache duration for the built-in bootstrap loader.
- `matcherCacheTtlMs`: Cache duration for canonical track matches.

The service exposes:

- `search(query)` and `streamingInfo(trackId, quality)` for direct API use.
- `albumQuality(albumId)`, `trackQuality(trackId)`, and `trackQualities(trackIds)` for catalog quality metadata. The `getAlbumQuality`, `getTrackQuality`, and `getTrackQualities` names are equivalent aliases.
- `matchTrack(track)` to match canonical metadata without resolving media.
- `resolveStream(match, quality)` to resolve an existing match.
- `resolveTrack(track, quality)` to match and resolve in one call.
- `proxyStream(playbackId, request, response)` to serve seekable FLAC.
- `playbackStarted(...)`, `playbackEnded(...)`, and `close()` for reporting and cleanup.
- `client` and `bootstrap` for advanced session control.

Supported quality values are `auto`, `lossless`, and `hires`.

### Catalog quality metadata

The catalog helpers call Qobuz's album and track metadata endpoints with the authenticated client headers. Album IDs are opaque strings and are preserved exactly; track IDs remain numeric. `trackQualities()` sends batches of at most 50 IDs to the track list endpoint. Returned `bitDepth`, `sampleRate`, `channels`, `hiresStreamable`, and `streamable` fields are optional because Qobuz may omit them; an explicit `false` streamability flag is retained. Catalog `sampleRate` is normalized from Qobuz's kHz value to Hz, matching the playback source unit.

Catalog values describe the highest quality Qobuz advertises for the album or track. They do not guarantee the quality of a particular playback session; use `streamingInfo()` or the resolved playback source for the media actually selected.

### Lower-level exports

The package root also exports the individual building blocks:

- `createQobuzClient`, `createQobuzBootstrapLoader`, `fetchQobuzBootstrap`, and `extractQobuzBootstrap`.
- `createQobuzAuthorizationUrl` and `exchangeQobuzAuthorizationCode` for host-owned login flows.
- `createQobuzMatcher`, `selectQobuzMatch`, `canonicalTrack`, and `normalizedQobuzText`.
- `normalizeQobuzAlbumQuality` and `normalizeQobuzTrackQuality` for hosts that already have catalog response objects.
- `createQobuzPlayback` and `createQobuzReporter`.
- `parseQobuzInitSegment`, `parseQobuzAudioSegment`, key derivation helpers, and segment decryption helpers.
- API URLs, format IDs, CMAF UUIDs, quality values, and normalization helpers.

## Electron adapter

Electron login and encrypted session persistence are available separately, so non-Electron consumers never load that code:

```js
import { setupQobuzElectron } from '@orchardmusic/qobuz/electron';

const provider = setupQobuzElectron({
  app,
  applicationName: 'Example Player',
  BrowserWindow,
  ipcChannels: {
    CONNECT: 'qobuz:connect',
    DISCONNECT: 'qobuz:disconnect',
    STATUS: 'qobuz:status',
    UPDATE: 'qobuz:update'
  },
  ipcMain,
  net,
  safeStorage,
  session,
  softwareVersion: 'ExamplePlayer/1.0.0'
});
```

The adapter also accepts custom `partition` and `recordPath` values. `createQobuzAuth` is exported from the `/electron` entry point for hosts that want authentication without the composed provider.

The authentication helper persists a user token only when Electron `safeStorage` reports a secure backend. Otherwise the login remains memory-only and public status reports that it is not persistent.

## Security

Hosts should avoid logging account tokens, signed media URLs, bootstrap secrets, session information, or content keys. Keep credentials server-side or in an OS-backed secret store, validate playback IDs at any public HTTP boundary, and call `close()` during shutdown.

## License

AGPL-3.0-or-later. See the [Orchard repository](https://github.com/sfg5453/orchard) for source and license terms.
