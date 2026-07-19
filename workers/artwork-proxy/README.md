# Orchard Artwork Proxy

This Worker turns Orchard's Apple Music animated artwork MP4 URL into a stable,
cacheable animated GIF URL for Discord Rich Presence. Conversion runs on the FreeBSD
server; the Worker validates input, authenticates to that service, streams its
response, and caches successful artwork at the edge.

## Deploy

Set `CONVERTER_URL` in `wrangler.jsonc` to your converter endpoint. The Worker
and converter must share the same random token. Store it as a Worker secret
rather than in this repository:

```bash
npm install
npx wrangler secret put CONVERTER_TOKEN
npm run deploy
```

After deployment, use the assigned `workers.dev` URL or configure your own
custom domain. The conversion endpoint is:

```text
https://<your-worker-url>/convert.gif?url=<encoded Apple artwork MP4 URL>
```

Only HTTPS MP4 URLs on `mvod.itunes.apple.com` are accepted.
