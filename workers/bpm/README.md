# Orchard BPM Worker

This Cloudflare Worker looks up song tempo and musical-key data through
[GetSongBPM](https://getsongbpm.com/). It keeps the upstream API key out of
Orchard clients, ranks search results, normalizes the response, and caches
successful lookups to protect the upstream hourly quota.

## API

```http
GET /bpm?title=Master%20of%20Puppets&artist=Metallica
```

`title` is required and `artist` is optional. The response includes `bpm`,
`key`, `openKey`, `timeSignature`, and the matched song metadata. `GET /health`
provides a liveness check. The landing page at `/` includes the backlink
required by GetSongBPM's API terms.

## Set up

Register the deployed Worker URL and its root-page backlink at
[GetSongBPM's API page](https://getsongbpm.com/api), then install dependencies
and add the issued key as a Worker secret:

```bash
npm install
npx wrangler secret put GETSONG_API_KEY
```

For local development, create an uncommitted `.dev.vars` file containing
`GETSONG_API_KEY=...`, then run:

```bash
npm run dev
```

Validate locally with:

```bash
npm run check
```

Before deploying, optionally build the exact upload bundle without publishing
it, then deploy:

```bash
npm run check:bundle
npm run deploy
```

Never put the API key in `wrangler.jsonc`, source code, a committed
`.dev.vars` file, or a client application.
