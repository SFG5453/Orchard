# Orchard account service

Cloudflare Worker that signs users in with Google and issues Orchard sessions.
Other Orchard services verify its access tokens against `/.well-known/jwks.json`.

## Flow

1. The app opens `/auth/google/start` with a callback `redirect_uri`, `state`
   and an S256 PKCE challenge. Desktop uses a loopback port; Android uses
   an app-specific `<applicationId>://account/callback` URI so the browser
   returns to the correct installed build.
2. The worker sends the browser to Google, handles `/auth/google/callback`, and
   redirects to the app's callback with a one-time code.
3. The app redeems the code at `POST /auth/token` and gets a 15 minute access
   token (EdDSA JWT) plus a refresh token bound to a new device.
4. Refresh tokens rotate on every use. Replaying a rotated token after a 60
   second grace window revokes the device.

## Access tokens

| Claim | Meaning |
| --- | --- |
| `iss` | `PUBLIC_URL` origin |
| `aud` | `orchard` |
| `sub` | Orchard user id |
| `did` | Device id |
| `exp` | 15 minutes after issue |

Services that only check the JWT accept a revoked device until its token
expires. The account routes (`/me`, `/devices`) also check the device row.

## Routes

| Route | Auth |
| --- | --- |
| `GET /auth/google/start` | none |
| `GET /auth/google/callback` | none |
| `POST /auth/token` | `authorization_code` or `refresh_token` grant |
| `POST /auth/logout` | refresh token in body |
| `GET /apple/developer-token` | Bearer, live device; per-IP, per-account and daily limits |
| `GET /apple/shazam-token` | Same as `/apple/developer-token` |
| `GET /me` | Bearer |
| `POST /lyrics/translate` | Bearer, live device, Cloudflare IP |
| `GET /devices` | Bearer |
| `DELETE /devices/:id` | Bearer |
| `GET /.well-known/jwks.json` | none |
| `POST /artwork` | Bearer, WebP body |
| `GET /artwork/index` | Bearer |
| `GET`/`HEAD /artwork/<sha256>.webp` | none |
| `GET /connect/hub` (WebSocket) | `bearer.<access token>` subprotocol |
| `POST /github/link` | Bearer |
| `GET /auth/github/callback` | none |
| `DELETE /github` | Bearer |
| `GET /support/reports` | Bearer |
| `POST /support/reports` | Bearer, multipart |
| `GET /support/reports/:id` | Bearer |
| `POST /support/reports/:id/read` | Bearer |
| `GET /support/screenshots/<key>` | none |
| `POST /github/webhook` | `X-Hub-Signature-256` |

## Apple Music developer tokens

`GET /apple/developer-token` returns `{developer_token, expires_at}` for
MusicKit JS. `expires_at` is a Unix timestamp in seconds. Tokens last 60
days, so clients cache one and fetch again near expiry. Responses use
`Cache-Control: no-store`.

The endpoint requires an Orchard session from a live device; missing or revoked
sessions return 401. Requests pass three limits, each returning 429 with
`Retry-After`:

- `AUTH_LIMITER`: 30 per minute per Cloudflare IP in the `apple` bucket (`slow_down`).
- `APPLE_LIMITER`: 5 per minute per account (`slow_down`).
- 150 tokens per account per UTC day, counted in D1 (`apple_daily_limit`).

Apply migration `0006_apple_music.sql` before deploying. The hourly cron purges
usage rows older than yesterday.

Each token carries an `origin` claim, so browsers only accept it from Orchard's
pages. The default is `https://appassets.androidplatform.net`, the Android
sign-in page. Set `APPLE_MUSIC_ORIGINS` to a comma-separated list to replace it.
Clients outside a browser can forge `Origin`; the account requirement is the
real control.

`GET /apple/shazam-token` returns the same body and passes the same session
check and limits, sharing the daily count, but its token has no `origin` claim.
ShazamKit for Android sends no `Origin` header, so Apple answers origin-bound
tokens with 401. The Media Services key must have ShazamKit enabled.

Apple handles subscriber authorization in MusicKit JS and returns a separate
Music User Token to the app. The Worker only signs developer tokens and does not
receive Apple passwords or Music User Tokens.

Create a MusicKit identifier and enabled private key in the Apple developer
account, then configure these independently of Orchard's `SIGNING_KEY`:

```sh
npx wrangler secret put APPLE_MUSIC_TEAM_ID --config wrangler.production.jsonc
npx wrangler secret put APPLE_MUSIC_KEY_ID --config wrangler.production.jsonc
npx wrangler secret put APPLE_MUSIC_PRIVATE_KEY --config wrangler.production.jsonc < /path/to/AuthKey_KEYID.p8
```

The private key secret contains the full PKCS#8 PEM from the `.p8` file. For
local development, put it in ignored `.dev.vars` as a quoted value with `\n`
escapes. Never commit the private key or include it in an Android build. The
Worker signs ES256 JWTs with Apple's Key ID in `kid` and Team ID in `iss`,
following [Apple's developer token format](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens).

Missing or invalid Apple credentials return 503 `apple_music_unavailable`.
Other account routes work without these optional credentials. This endpoint
does not implement Sign in with Apple or issue Orchard sessions.

## Lyric translation

`POST /lyrics/translate` accepts `{song_id, lines}` for a whole song and returns
`{song_id, lines, cached, quota}` with exactly one English output for each input line.
`GET /lyrics/quota` returns `{quota}` without consuming an allowance or running inference.
Both routes require an active session and Cloudflare client IP. The quota contains
`daily_limit`, `account_remaining`, `ip_remaining` (null for elevated accounts),
`remaining` (the effective allowance after any shared IP limit), and `resets_at`
(Unix seconds at the next UTC midnight). Translation errors after authentication
and IP validation also include the current quota. Clients refresh it when opening
translation settings, every minute while those settings are visible, and from
translation responses; they show an unavailable allowance if quota data is missing.
The model is fixed server-side to `@cf/google/gemma-4-26b-a4b-it` using the `AI`
binding. Client model, identity, IP, quota, and reset fields are ignored.

D1 caches results for 90 days in `lyric_translations`, keyed by song ID, prompt/model
revision, and a server-computed lyrics digest. The digest protects line alignment
when lyric sources differ. Cache reads require the same session/device checks as
misses, and cache hits are free across accounts.

Each cache miss reserves one slot in the authenticated account's quota, which
defaults to 10 starts per UTC day. Accounts with a limit of 10 or less also share
a 10-start quota on Cloudflare's `CF-Connecting-IP`. Accounts with an elevated
allowance only use their account quota, so a shared network cannot block their
allowance and their translations do not consume other accounts' IP quota.
Quotas reset at 00:00 UTC from the server clock, independently of cron. D1 triggers
check counters and charge admission atomically, including concurrent requests.
Changing song IDs or lyric content requires another admission. Failed generation
counts too; failed or partial output is never cached. Missing Cloudflare IP fails
closed. IPs are hashed with the signing key before storage, and completed cache
rows discard the account and IP association.

The request body is limited to 48 KiB, 200 nonempty lines, 1024 UTF-8 bytes per
line, and 24 KiB of lyric JSON. The Worker splits inference into 24-line batches
with a 4096-token output cap. Inference has a 120-second deadline across all batches
and rejects requests when model capacity is unavailable. A per-cache-entry lease prevents duplicate inference;
it expires after 180 seconds. Errors: 429 `translation_daily_limit` with
`Retry-After` until midnight, 409 `translation_pending` with `Retry-After: 5`,
502 `translation_failed` or `translation_invalid_result`, 504 `translation_timeout`,
503 `translation_busy`, and 503 `translation_unavailable` for a missing binding.
Structured logs record start, completion, elapsed time, and stable failure codes;
lyric text and credentials are excluded.
The hourly purge removes expired entries and old daily counters.

Apply all account migrations, including `0007_translation_allowances.sql`, and
include `ai: {binding: "AI"}` in the production Wrangler config before deploying.
The ignored production config on
this checkout includes that binding; the tracked TOML includes its equivalent.
The model's binding and completion format are documented in the
[Cloudflare model reference](https://developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/).

### Per-account daily allowances

To give a donor 50 translations per UTC day, look up their Orchard account by
sign-in email in `users`, verify the ID, then run this SQL in the production D1
console (replace the placeholder with that ID):

```sql
UPDATE users SET translation_daily_limit = 50 WHERE id = 'ACCOUNT_ID';
SELECT id, email, translation_daily_limit FROM users WHERE id = 'ACCOUNT_ID';
```

The allowance is recurring and takes effect immediately without signing in
again. Starts already used today count toward the 50. Set the value back to 10
to restore the default. Account deletion also removes the allowance.

## Artwork

Hosting for Discord Rich Presence artwork in Backblaze B2. The worker
receives the bytes so it can parse the WebP container before storage. Public
files are served from `https://artwork.sfg545.dev`, which maps to the bucket
root; the B2 application key stays with the worker.

- Key: `<sha256>.webp` at the bucket root, chosen by the worker. Re-uploading
  stored bytes returns the existing URL (`reused: true`).
- Limits: 10 MiB, 1024x1024, 1200 frames, 60 fps average.
- Rate: 3 per 30 s, 20 per hour, 100 per day per user; 50 per hour per IP;
  2 in flight per user. Rejections are 429 with `Retry-After`.
- Index: uploads may include `x-orchard-source-sha256`, the lowercase SHA-256
  of the source URL's UTF-8 bytes. `GET /artwork/index` returns the caller's
  `{source_sha256, url, expires_at}` files. It accepts an optional
  `source_sha256` query to find one source and a `cursor` query to page through
  the full index (500 files per page; `next_cursor` is null on the last page).
  The desktop client checks the index before downloading or converting motion
  artwork. URL changes, including rotating query strings, cause a miss.
- Expiry: files expire 24 hours after their last use. An upload of the same
  bytes or an index lookup by `source_sha256` pushes `expires_at` out again; a
  full index listing does not. The hourly cron deletes up to 40 expired files
  per run from B2 and D1. A row is marked `purging` before its B2 delete, so a
  failed delete is retried and the file is never advertised meanwhile.
  Re-uploading bytes whose row survived but whose file is missing stores them
  again.
- Existing R2-backed D1 rows are treated as legacy and are not served from B2;
  re-uploading the bytes migrates them. Clients that omit the source digest
  receive an account-hosted proxy URL for compatibility with older desktop
  builds. Both URLs serve the same B2 object.

## Bug reports

Reports are GitHub issues on `GITHUB_REPOSITORY`, filed by `GITHUB_TOKEN` and
attributed to the reporter with an `@login` mention. Filing needs a linked
GitHub account (403 `github_required` otherwise).

- Linking: `POST /github/link` returns a GitHub authorize URL for the
  `GITHUB_CLIENT_ID` OAuth app (no scopes). The callback reads `/user`, stores
  the GitHub id and login as a `github` identity, and revokes the token. One
  GitHub account per Orchard account; a GitHub account already linked to
  someone else gets a 409 page. Apps poll `/support/reports` until `github` is
  set.
- Filing: multipart `kind` (`bug`, `feature`, `feedback`), `title` (140),
  `body` (12000), optional `diagnostics` (JSON object, shown in a collapsed
  block), optional `screenshot` (still PNG, JPEG or WebP, 5 MiB, magic bytes
  checked). Screenshots go to R2 under `support/<random>.<ext>` and are
  embedded in the issue, so they are public. Limit: 5 per hour, 20 per day.
- Activity: the issue timeline is mirrored into `support_events`: comments
  (with a maintainer flag from `author_association`), referencing commits,
  cross-references from issues and PRs, closes (with reason or fixing commit),
  reopens, assignments and labels. Labels and the reporter's own activity
  never count as unread. Unread compares `added_at` with the report's
  `read_at`.
- Sync: the `*/10 * * * *` cron syncs the 20 stalest followed reports (open,
  or touched in the last 30 days). Listing reports syncs up to 5 of the
  caller's that are older than a minute. Timeline requests send the stored
  ETag, so an unchanged issue costs one 304.
- Webhook (optional): with `GITHUB_WEBHOOK_SECRET` set, point a repository
  webhook at `/github/webhook` for `issues`, `issue_comment` and `push`
  events. Pushes record referencing commits immediately; everything else
  triggers a sync of the affected report.

## Orchard Connect hub

`/connect/hub` upgrades to a WebSocket served by the `ConnectHub` Durable
Object, one per account. Clients offer the subprotocols `orchard-connect.2` and
`bearer.<access token>`; WebSocket clients cannot set headers, and a token in
the URL would reach logs. The hub only does presence, session grants and
WebRTC signaling. Playback and audio go device to device.

Client to hub:

- `hello` / `update`: `connect_protocol_major` (must be 2), `device`, `lan`
  (literal IPv4 endpoints). Anything else gets `error` and close code 4002, so
  old clients never see presence.
- `session.request {request_id, to}`: becomes the controller of `to`.
- `signal {to, session_id, data}`: relayed only between the two ends of a
  granted session.
- `session.decline {session_id, code}`, `session.end {session_id}`.
- `refresh {token}`: extends the socket past the access token's expiry. An
  expired socket is closed with 4401.

Hub to client: `welcome`, `presence {devices}`, `session.grant` (same random
32-byte `key` to both ends, `role`, `peer`, `peer_lan`, `ice_servers`,
`expires_in`), `signal {from, ...}`, `session.declined`, `error`.

The device id always comes from the token (`did`), never from the message.
Session requests are limited to 12 per device per minute. With `TURN_KEY_ID`
and `TURN_KEY_API_TOKEN` set, grants carry Cloudflare Realtime TURN
credentials; without them, only STUN.

## Setup

The tracked `wrangler.toml` contains placeholders for local development.
`wrangler.production.jsonc` preserves the production resource identifiers and
is ignored by Git. `npm run deploy` and `npm run migrate:remote` use the
production JSONC; check it before either command.

1. In Google Cloud, create an OAuth client of type **Web application** with
   the redirect URI `https://account.sfg545.dev/auth/google/callback`. Only the
   `openid`, `email` and `profile` scopes are used.
2. Create the database and put its id in `wrangler.production.jsonc`:
   `npx wrangler d1 create orchard-accounts`
3. Use the `Orchard-Art` B2 bucket already served at `artwork.sfg545.dev`.
   The Worker uses its lowercase S3 name `orchard-art` and endpoint region
   `us-west-004`. The Worker deletes expired files itself. Set the lifecycle
   rule to keep only the last version so hidden (deleted) files are removed.
   Create a bucket-scoped application key with read and write file permissions,
   which include deleting files.
   The bucket root maps to the artwork domain root.
4. Set `GOOGLE_CLIENT_ID` in `wrangler.production.jsonc`, then add the secrets:
   ```sh
   npx wrangler secret put GOOGLE_CLIENT_SECRET --config wrangler.production.jsonc
   npm run --silent generate-key | npx wrangler secret put SIGNING_KEY --config wrangler.production.jsonc
   npx wrangler secret put B2_KEY_ID --config wrangler.production.jsonc
   npx wrangler secret put B2_APPLICATION_KEY --config wrangler.production.jsonc
   ```
5. Optional, for Connect over strict NATs: create a TURN key in Cloudflare
   Realtime and put `TURN_KEY_ID` and `TURN_KEY_API_TOKEN` using
   `--config wrangler.production.jsonc`.
6. Bug reports: create the screenshot bucket with
   `npx wrangler r2 bucket create orchard-support`. Create a GitHub OAuth app
   with the callback `https://account.sfg545.dev/auth/github/callback`, put
   its client id in `GITHUB_CLIENT_ID`, and check `GITHUB_REPOSITORY`. Then
   `npx wrangler secret put GITHUB_CLIENT_SECRET --config wrangler.production.jsonc`
   and `GITHUB_TOKEN` with the same config (a
   fine-grained token with Issues read/write and Contents read on that
   repository). Optionally `GITHUB_WEBHOOK_SECRET` plus a repository webhook
   (see Bug reports).
7. `npm run migrate:remote && npm run deploy`

## Local development

Copy `.dev.vars.example` to `.dev.vars`, fill it in, then run
`npm run migrate:local && npm run dev`. Start the desktop app with
`ORCHARD_ACCOUNT_URL=http://127.0.0.1:8787`.

`npm test` runs the suite against an in-memory D1 stand-in on `node:sqlite`.
