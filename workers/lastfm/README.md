# Orchard Last.fm Worker

This Worker owns Orchard's Last.fm API credentials, signs desktop
authentication and scrobbling requests, and forwards them to Last.fm over
HTTPS. The desktop keeps each user's Last.fm session key encrypted locally;
neither the API key nor shared secret is committed to or bundled with Orchard.

## Provisioning

Install the Worker dependencies and add both Last.fm credentials as secrets:

```bash
npm install
npx wrangler versions secret put LASTFM_API_KEY
npx wrangler versions secret put LASTFM_SHARED_SECRET
```

Then validate and deploy:

```bash
npm run check
npm run deploy
```

Configure `https://lastfm.sfg545.dev` as a custom domain, or set
`ORCHARD_LASTFM_WORKER_URL` before launching or packaging Orchard to use a
different deployed Worker URL. Do not put either credential in
`wrangler.jsonc`, source code, `.env`, or a committed `.dev.vars` file.

The Worker exposes `GET /health` plus four POST endpoints used by the desktop:
`/auth/token`, `/auth/session`, `/now-playing`, and `/scrobble`.
