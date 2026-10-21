# Orchard Apple Music provider

Catalog access to the public Apple Music API (`api.music.apple.com`), returning
the same item, track and detail shapes as the YouTube provider so desktop QML
and Android parse both alike. The runtime code is standard ECMAScript with no
Node, DOM or URL dependency.

The host supplies Fetch and a session: the developer token from the Orchard
account service, the Music User Token from MusicKit sign-in, the storefront,
and the page origin named in the developer token's `origin` claim. Native hosts
send that origin as the `Origin` header.

## Ids

Apple resources travel as Orchard browse ids `am.<type>.<id>`, such as
`am.artists.605800394` or `am.playlists.pl.u-abc`. Tracks carry
`source: "apple_music"` and the bare catalog id in `appleId`.

## Runtime methods

`src/runtime.js` is the QuickJS entry point, exposed as
`OrchardAppleMusicProvider.invoke(method, payload)`:

| Method | Payload | Result |
| --- | --- | --- |
| `session.set` | `developerToken`, `userToken`, `storefront`, `origin` | `{connected}` |
| `storefront.resolve` | `fallback` | the listener's storefront, or `fallback` for guests |
| `catalog.home` | none | personal recommendation shelves, or charts when empty |
| `catalog.home.section` | `browseId` | every supported item in a recommendation shelf |
| `catalog.artist` | `browseId` | artist page: top songs, release shelves, similar artists |
| `catalog.artist.view` | `browseId`, `params` (`view:<name>`) | every item on one artist shelf |
| `catalog.artist.match` | `name` | `{browseId, title}` for an exact name match, or null |
| `catalog.album` | `browseId` | album page with every track |
| `catalog.playlist` | `browseId` | playlist page with every track |
| `catalog.artwork` | `albumId`, `appleId`, `isrc`, or `title`, `artist`, `album` (`kind: "album"` for albums) | `{albumId, name, artist, static, animated, animatedVertical}`, or `{}` when nothing matches |
| `catalog.search` | `query`, `filter` | sections keyed `songs`, `albums`, `artists`, `playlists` |
| `library` | none | the listener's library as sections keyed `artists`, `albums`, `songs`, `playlists` |
| `library.playlists` | none | `{items}`: every playlist in the listener's library |
| `library.like.status` | `track` | `{liked, songId}` from the song's rating |
| `library.like.set` | `track`, `liked` | loves (and saves) or unloves the song; `{liked, songId}` |
| `library.playlist.targets` | none | `{playlists}`: the listener's editable playlists |
| `library.playlist.add` | `track`, `playlistId`, `title` | `{playlistId, title}` |
| `library.playlist.create` | `title`, optional `track` | `{playlistId, title}` |
| `lyrics` | `appleId`, `isrc`, or `title`, `artist`, `album` | `{songId, ttml}`, or `{}` when Apple has no lyrics |
| `playback.stream` | `track` (`id` or `appleId` of a catalog song) | `{appleId, flavor, licenseUrl, keyUri, media, ranges, durations, duration}`: one encrypted fragmented MP4 as `[start, size]` byte ranges, the init segment first |
| `playback.license` | `licenseUrl`, `keyUri`, `appleId`, `challenge` (base64) | `{license}` (base64 Widevine license) |

Apple API failures return `{error: {code, status, message}}`. Codes include
`not_connected`, `not_signed_in`, `developer_token` (401), `user_token` (403),
`not_found` and `rate_limited`; playback adds `not_playable` (library-only songs),
`not_offered` or `playback_<failureType>` with Apple's own message, `no_widevine`,
`bad_stream` and `license_refused`. The host fetches and decrypts the media itself.

Artist pages request the undocumented `artistBio` and `editorialVideo`
attributes and retry without them if Apple rejects the request. Third-party
developer tokens never receive editorial fields (`editorialVideo` is dropped,
`editorialArtwork` is a 400). While the listener is signed in to Apple Music,
editorial requests go to `amp-api.music.apple.com` with music.apple.com's own
developer token, read from its main bundle (`src/webToken.js`) and cached until
Apple rejects it. Signed out, or when that fails, the public API answers. Music videos
and stations are left out until Apple Music playback exists.

Run `npm test` for the unit tests.

## Home

`catalog.home` reads `/v1/me/recommendations` with the signed-in listener's
Music User Token. Recommendation titles use `attributes.title.stringForDisplay`
and cards use `relationships.contents`, matching the response shapes in
[MusadoraKit](https://github.com/rryam/MusadoraKit/tree/main/Sources/MusadoraKit/Recommendations).
Empty supported recommendations fall back to storefront charts. Hosts fall
back to YouTube Music if Apple Music is disconnected or Home fails.

Apple Music tracks are catalog metadata; the hosts match them to YouTube Music
for playback. Apple Music subscription audio is not streamed.

## Library

`library` reads `/v1/me/library/*` with the Music User Token. Cards use the
catalog id from `playParams.catalogId` when Apple has one, so library songs
match catalog songs for playback, artwork and lyrics. Library-only resources
keep their library ids (`p.`, `l.`, `i.`), and `catalog.playlist` and
`catalog.album` open `am.playlists.p.…` and `am.albums.l.…` from the library.
Each section fails on its own and carries an `error`.

The `library.like.*` and `library.playlist.*` writes take a track from any
service and match it to a catalog song (library-only songs keep
their `i.` id). Loving a song also adds it to the library, so it appears under
Songs. Apple's API cannot remove or reorder playlist tracks.

## Lyrics

`lyrics` finds the catalog song like `catalog.artwork` does and reads
`syllable-lyrics`, then `lyrics`, from `amp-api.music.apple.com` with
music.apple.com's web token and the listener's token as `Media-User-Token`.
It returns the raw TTML. The YouTube provider parses it through `lyrics.resolve`
as the `apple` source, so Apple lyrics sit in the listener's provider order.

## Animated artwork

`catalog.artwork` finds the Apple album behind a song (by Apple id, ISRC or a
title/artist/album search) and reads its `editorialVideo` with `extend` through
the editorial path above, so motion artwork needs an Apple Music sign-in.
`motionDetailSquare` and `motionDetailTall` are the keys music.apple.com uses
for album motion artwork; both are HLS URLs. Hosts use it in place of the
unofficial artwork providers while the listener is signed in.
