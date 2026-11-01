# Orchard Apple Music provider

This package supplies Apple Music catalog, library, recommendations, lyrics,
stations, artwork, audio analysis, and playback requests to Orchard. It returns
Orchard item and page shapes shared with the other providers. Its runtime is
standard ECMAScript without Node, DOM, or URL dependencies. Node is used for
tests and bundling.

The host owns sign-in, token storage, networking, media fetching, and Widevine
processing. Personal features and playback require a Music User Token; playback
also requires access to the requested content. Library-only uploads without a
catalog song ID cannot be streamed by this provider.

## Integration and session

`createAppleMusic({ fetchImpl, session })` in `src/index.js` creates the
host-neutral API. `session` is a function returning `{ developerToken,
userToken, storefront, origin }`. `fetchImpl` is Fetch-compatible. The
QuickJS entry point `src/runtime.js` exposes
`OrchardAppleMusicProvider.invoke(method, payload)` and retains the session set
through `session.set` until it is replaced or cleared.

The developer token is required for public API requests. The user token is
optional for catalog requests and required for `/v1/me/` requests and playback.
The storefront defaults to `us`; `session.set` lowercases it. When supplied,
`origin` is sent as the `Origin` header for the developer token. The desktop
Meson build and Android Gradle build bundle the runtime and compile it to
`apple-music-provider.qjc` for the native provider host.

Apple resources use Orchard browse IDs of the form `am.<type>.<id>`, for
example `am.artists.605800394` and `am.playlists.pl.u-abc`. Apple IDs can
contain dots. Tracks use `source: "apple_music"` and carry the catalog ID in
`appleId` when available.

## Runtime methods

All methods are asynchronous. The table describes the QuickJS `invoke` API;
`src/index.js` also exposes the underlying functions directly.

| Method | Payload | Result |
| --- | --- | --- |
| `session.set` | `developerToken`, optional `userToken`, `storefront`, `origin` | `{connected}`; an empty developer token clears the session |
| `storefront.resolve` | optional `fallback` | `{storefront}`; defaults to `us` when a user token is unavailable or rejected |
| `catalog.home` | none | Recommendation sections, falling back to charts when recommendations contain no supported items |
| `catalog.home.section` | `browseId` | `{items}` from a recommendation shelf |
| `catalog.artist` | `browseId` | Artist page with top songs and available shelves |
| `catalog.artist.view` | `browseId`, `params` (`view:<name>`) | `{items}` from an artist shelf |
| `catalog.artist.match` | `name` | `{browseId, title}` for an exact name match, or `null` |
| `catalog.album` | `browseId` | Album page with tracks and available related shelves |
| `catalog.playlist` | `browseId` | Catalog or library playlist page; station IDs return a station page |
| `catalog.musicVideo` | `appleId` or `id` | `{videoId}` for a catalog song's music video, or `{}` |
| `catalog.upNext` | `seed` | `{items}` from a song or station browse ID |
| `catalog.artwork` | `albumId`, `appleId`, `isrc`, or `title`, `artist`, `album`; `kind: "album"` for albums | Album artwork metadata, or `{}` on a miss |
| `catalog.audioAnalysis` | `ids` | `{items}` keyed by catalog song ID, with available analysis attributes |
| `catalog.search` | `query`, optional `filter` | Sections keyed `songs`, `albums`, `artists`, `playlists`; filter defaults to `all` |
| `library` | none | Library sections keyed `artists`, `albums`, `songs`, `playlists` |
| `library.playlists` | none | `{items}` of library playlists |
| `library.recent` | none | `{items}` of recently played tracks |
| `library.like.status` | `track` | `{liked, songId}` |
| `library.like.set` | `track`, `liked` | `{liked, songId}` after setting or removing a love |
| `library.playlist.targets` | none | `{playlists}` of editable playlists |
| `library.playlist.add` | `track`, `playlistId`, optional `title` | `{playlistId, title}` |
| `library.playlist.create` | `title`, optional `track` | `{playlistId, title}` |
| `lyrics` | `appleId`, `isrc`, or `title`, `artist`, `album` | `{songId, ttml}`, or `{}` on a miss |
| `playback.stream` | `track` with numeric `appleId` or `am.songs.<id>` in `id` | Audio stream metadata and byte ranges |
| `playback.video` | `id` (numeric or `am.music-videos.<id>`) | `{appleId, licenseUrl, master}` for a video HLS master playlist |
| `playback.license` | `licenseUrl`, `keyUri`, `appleId`, base64 `challenge` | `{license}` containing a base64 Widevine license |
| `close` | none | Clears the session and returns `null` |

`AppleMusicError` failures are returned by `invoke` as
`{error: {code, status, message}}`. Common codes include `not_connected`,
`not_signed_in`, `developer_token` (401), `user_token` (403), `not_found`,
`rate_limited`, and `http`. Playback can also return `not_playable`,
`not_offered`, `playback_<failureType>`, `no_widevine`, `bad_stream`,
`bad_request`, or `license_refused`. Unknown methods and errors that are not
`AppleMusicError` throw rather than returning this envelope.

## Catalog and personal features

Home reads `/v1/me/recommendations`, including nested recommendations and
personal mix shelves. If the response has no supported cards, it tries
storefront charts; if those are also empty it returns `empty_home`. A failed
personal request is not itself converted into a chart request.

Library sections read `/v1/me/library/*`. Catalog IDs from library resources
are preferred when available; library-only IDs remain usable for library album
and playlist pages. A failure in one section is reported on that section, while
connection and sign-in errors fail the entire request. Pagination is bounded:
the default client limit is eight pages, with separate limits for library and
recently played items in `src/library.js`.

Library writes match tracks from other services to Apple songs. Loving a catalog
song also adds it to the library. Playlist operations support creating a
playlist and adding tracks. Stations use Apple's personal next-tracks endpoint
in batches of ten. A song seed maps to its `ra.<songId>` station; live radio is
not playable through the station page.

## Editorial data, lyrics, and artwork

Signed-in editorial requests use `amp-api.music.apple.com` and a web developer
token discovered from music.apple.com's bundle. The token is cached and reset
when a non-personal editorial request is rejected with 401 or 403. Catalog
callers can fall back to the public API when editorial data is unavailable;
artist requests also retry without extended attributes after a public 400.

Lyrics try `syllable-lyrics` and then `lyrics` on the editorial API, sending the
listener's token as `Media-User-Token`, and return raw TTML for the host to
parse. `lyrics.translation` takes the same track query plus `language` (a BCP 47
tag) and returns `{songId, language, translations}` keyed by TTML line key, read from
the `ttmlLocalizations` extension with `l[lyrics]`; it returns `{}` when Apple has no
translation for that language. Artwork lookup resolves an album from an ID, ISRC, or metadata and can
return static artwork, colors, and signed-in editorial motion artwork. Audio
analysis uses the editorial API in batches of 50 and omits songs without
available analysis. These editorial features depend on Apple responses and may
return no data.

## Playback

`playback.stream` requests a Widevine audio asset from Apple's playback
endpoint and parses its HLS media playlist. Its result includes `appleId`,
`flavor`, `licenseUrl`, `keyUri`, `media`, `ranges`, `durations`, and
`duration`. Each range is `[start, size]`; the first is the initialization
segment. The parser requires contiguous ranges in one media file and a
Widevine key. The host fetches the media and handles decryption and decoding.

`playback.video` returns the HLS master URL and license endpoint. The host
handles the video playlists and media. Both audio and video use
`playback.license` to exchange a Widevine challenge for a license.

## Source map

| Area | Source | Key symbols | Role |
| --- | --- | --- | --- |
| Entry points | `src/index.js`, `src/runtime.js` | `createAppleMusic`, `invoke` | Compose the API and expose QuickJS methods |
| Requests | `src/client.js`, `src/webToken.js` | `createAppleMusicClient`, `createWebToken` | Authentication headers, pagination, public and editorial requests |
| Catalog | `src/catalog.js`, `src/artist.js`, `src/home.js`, `src/normalize.js`, `src/ids.js` | `createCatalog`, `createArtistCatalog`, `createHomeCatalog` | Pages, recommendations, normalization, and browse IDs |
| Personal library | `src/library.js`, `src/libraryWrite.js` | `createLibraryCatalog`, `createLibraryWrites` | Library reads, ratings, and playlist writes |
| Stations and video | `src/stations.js`, `src/musicVideos.js` | `createStationCatalog`, `createMusicVideoCatalog` | Station queues and song-to-video lookup |
| Enrichment | `src/artwork.js`, `src/lyrics.js`, `src/analysis.js` | `createArtworkCatalog`, `createLyricsCatalog`, `createAnalysisCatalog` | Artwork, TTML lyrics, and audio analysis |
| Playback | `src/playback.js` | `createPlayback`, `parsePlaylist` | Audio and video playback requests and licenses |
| Hosts | `../../core/native/provider_host/provider_host_media.cpp`, `../../meson.build`, `../../mobile/android/app/build.gradle` | `OrchardAppleMusicProvider`, `bundleAppleMusicProvider` | Provider host registration and runtime bundling |

## Tests

Run the provider's mocked unit tests from this directory:

```sh
npm test
```

Apple's public and web endpoints and media formats may change. The tests do not
verify access to a live account or real playback.
