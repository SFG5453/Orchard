# Orchard Artist Metadata

Cloudflare Worker that resolves an artist's broad iTunes genre and caches the
confirmed mapping in D1. Orchard sends both an artist name and one known album;
the Worker only returns a genre when the album belongs to the same iTunes
artist ID, avoiding same-name artist collisions.

After deployment, use the assigned `workers.dev` URL or configure your own
custom domain. The artist endpoint is:

```text
https://<your-worker-url>/artist
```

Example:

```bash
curl -sG 'https://<your-worker-url>/artist' \
  --data-urlencode 'artist=SZA' \
  --data-urlencode 'album=SOS' \
  --data-urlencode 'youtubeBrowseId=UC...'
```

## Setup

```bash
npm install
npm run d1:create
```

Copy the returned D1 UUID into `wrangler.jsonc`, then apply the migration:

```bash
npm run d1:migrate:local
npm run d1:migrate:remote
```

Validate and deploy:

```bash
npm run check
npm run deploy
```

Successful matches are cached for 90 days. Failed album confirmations are
cached for one day so duplicate or incomplete iTunes entries do not cause a
request storm.
