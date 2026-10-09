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
| `GET /me` | Bearer |
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

## Artwork

Permanent hosting for Discord Rich Presence artwork in Backblaze B2. The worker
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
  `{source_sha256, url, expires_at: null}` files. It accepts an optional
  `source_sha256` query to find one source and a `cursor` query to page through
  the full index (500 files per page; `next_cursor` is null on the last page).
  The desktop client checks the index before downloading or converting motion
  artwork. URL changes, including rotating query strings, cause a miss.
- Files do not expire. The hourly cron only clears old upload quota entries.
  Existing R2-backed D1 rows are treated as legacy and are not served from B2;
  re-uploading the bytes migrates them. Clients that omit the source digest
  receive an account-hosted proxy URL and a far-future expiry marker for
  compatibility with older desktop builds. Both URLs serve the same permanent
  B2 object.

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
   `us-west-004`. Keep artwork files without an expiration lifecycle rule.
   Create a bucket-scoped application key with read and write file permissions.
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
