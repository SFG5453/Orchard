# Orchard Song Links Worker

Cloudflare Worker + D1 replacement foundation for song.link-style lookups.

The Worker stores a canonical Orchard song row and returns direct destination pages when it can. YouTube and YouTube Music are direct when Orchard sends a video ID. Apple Music and Deezer are resolved through public lookup APIs. Apple Music, TIDAL, and Spotify stay visible as branded search links when a direct match is unavailable; TIDAL resolves through the official API when credentials are configured, while Spotify stays search-only because Spotify's catalog search API is blocked for this app.

When Orchard does not provide artwork, the Worker can ask a compatible Apple
Music artwork service for a cover and store the result in D1. Set
`ARTWORK_API_ORIGIN` in `wrangler.jsonc` to that service's origin.

## Setup

```bash
cd workers/song-links
npm install
npx wrangler login
npm run d1:create
```

Copy the `database_id` printed by `d1:create` into `wrangler.jsonc`, replacing the all-zero placeholder.

Then initialize D1:

```bash
npm run d1:migrate:local
npm run d1:migrate:remote
```

## Local Development

```bash
npm run dev
```

Try a resolver request:

```bash
curl -s http://127.0.0.1:8787/resolve \
  -H 'content-type: application/json' \
  -d '{
    "title": "BIRDS OF A FEATHER",
    "artist": "Billie Eilish",
    "album": "HIT ME HARD AND SOFT",
    "youtubeVideoId": "V9PVRfjEBTI"
  }'
```

The JSON response includes `shareUrl`, which can be opened under the Worker host, for example `http://127.0.0.1:8787/s/<id>`.

## Deploy

```bash
npm run deploy
```

## Optional TIDAL Resolver

TIDAL direct links use the official client-credentials API. Create an app in the TIDAL Developer Portal, then add the credentials as Worker secrets:

```bash
npx wrangler secret put TIDAL_CLIENT_ID
npx wrangler secret put TIDAL_CLIENT_SECRET
```

Optionally set a catalog country in `wrangler.jsonc`:

```jsonc
"vars": {
  "TIDAL_COUNTRY": "US"
}
```

Without those secrets, TIDAL stays visible as a branded search link.

## API

### `POST /resolve`

Accepts:

```json
{
  "title": "Song title",
  "artist": "Artist name",
  "album": "Album name",
  "isrc": "USUM00000000",
  "youtubeVideoId": "YouTubeId11",
  "durationSeconds": 180,
  "thumbnailUrl": "https://..."
}
```

Requires `title` and `artist`.

### `GET /resolve`

Same fields as query parameters.

### `GET /s/:id`

Returns a simple share page.

### `GET /api/songs/:id`

Returns the cached JSON record.
