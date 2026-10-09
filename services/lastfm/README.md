# Orchard Last.fm Worker

This Worker owns Orchard's Last.fm API credentials, signs desktop
authentication and scrobbling requests, and forwards them to Last.fm over
HTTPS. The desktop keeps each user's Last.fm session key in the OS keyring;
neither the API key nor shared secret is committed to or bundled with Orchard.

## Provisioning

The tracked `wrangler.jsonc` uses a placeholder Worker name. The ignored
`wrangler.production.jsonc` preserves the production Worker settings, and
`npm run deploy` uses it. Install the Worker dependencies and add both Last.fm
credentials to that Worker as secrets:

```bash
npm install
npx wrangler versions secret put LASTFM_API_KEY --config wrangler.production.jsonc
npx wrangler versions secret put LASTFM_SHARED_SECRET --config wrangler.production.jsonc
```

Then validate and deploy:

```bash
npm run check
npx wrangler deploy --config wrangler.production.jsonc --dry-run
npm run deploy
```

Configure `https://lastfm.sfg545.dev` as a custom domain, or start the desktop
app with `ORCHARD_LASTFM_URL` set to use a different Worker (for example
`ORCHARD_LASTFM_URL=http://127.0.0.1:8787` with `npm run dev`). Do not put
either credential in a Wrangler config, source code, `.env`, or a committed
`.dev.vars` file.

The Worker exposes `GET /health` plus four POST endpoints used by the desktop:
`/auth/token`, `/auth/session`, `/now-playing`, and `/scrobble`.
