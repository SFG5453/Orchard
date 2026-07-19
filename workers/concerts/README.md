# Orchard Concerts

Ticketmaster-backed music-event discovery for Orchard.

After deployment, use the assigned `workers.dev` URL or configure your own
custom domain. The events endpoint is:

```text
https://<your-worker-url>/events
```

Supported queries:

```text
/events?location=Example%20City,%20CA
/events?location=00000
/events?lat=0&lng=0
```

## Before deployment

Install dependencies and add the Ticketmaster key as a Worker secret:

```bash
npm install
npx wrangler versions secret put TICKETMASTER_API_KEY
```

`versions secret put` creates a version containing the secret without deploying
it. Once the secret is ready, validate and deploy the application code:

```bash
npm run check
npm run deploy
```

Do not put the Ticketmaster key in `wrangler.jsonc`, Orchard, or a committed
`.dev.vars` file.
