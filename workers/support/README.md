# Orchard Support Worker

Two-way support for Orchard. The Worker stores anonymous report identities and conversations in D1, keeps screenshots in a private R2 bucket, creates one private Discord forum thread per report, and mirrors each report description into a public-safe GitHub issue.

## Provisioning

Do not commit IDs, bot tokens, or local `.dev.vars` files.

```bash
npm install
npx wrangler d1 create orchard-support
npx wrangler r2 bucket create orchard-support-screenshots
```

Replace the all-zero D1 `database_id` in `wrangler.jsonc` with the ID returned
by `d1 create`. Set `SUPPORT_URL` to the assigned `workers.dev` URL or your own
custom domain, and set `GITHUB_REPOSITORY` to the destination `owner/repo`.

Create a GitHub App for user-attributed issues, grant it read/write access to
Issues, enable Device Flow, and install it on the destination repository. Put
the app's public Client ID in `GITHUB_CLIENT_ID` in `wrangler.jsonc`. Do not add
the GitHub App client secret or private key to Orchard; the desktop flow does
not use either one.

Set secrets interactively:

```bash
npx wrangler secret put DISCORD_APPLICATION_ID
npx wrangler secret put DISCORD_BOT_TOKEN
npx wrangler secret put DISCORD_FORUM_CHANNEL_ID
npx wrangler secret put DISCORD_GUILD_ID
npx wrangler secret put DISCORD_PUBLIC_KEY
npx wrangler secret put DISCORD_STAFF_ROLE_IDS
npx wrangler secret put GITHUB_TOKEN
```

`DISCORD_STAFF_ROLE_IDS` is a comma-separated allowlist. The Discord app needs permission to view the private forum, send messages, create public threads in that forum, manage threads, and delete its support threads. Set its Interactions Endpoint URL to:

```text
https://<your-worker-url>/discord/interactions
```

Keep `GITHUB_REPOSITORY` in `wrangler.jsonc` as `owner/repo`. `GITHUB_TOKEN` is
the service-account fallback and needs Issues read/write permission for that
repository. A reporter's GitHub App user token is used for one issue-creation
request and is never written to D1 or application logs.

Apply the database migration and register guild commands:

```bash
npm run migrate:remote
DISCORD_APPLICATION_ID=... DISCORD_GUILD_ID=... DISCORD_BOT_TOKEN=... npm run discord:register
```

The registration command reads secrets only from the process environment or an untracked `.dev.vars`; it never prints them. Slash commands only appear after registration, and the app must be installed in the server with both the `bot` and `applications.commands` OAuth scopes. If commands still do not appear, check Discord server settings under Integrations for the Orchard app and make sure the staff role can use application commands in the support forum and its threads.

## Local verification

Create an untracked `.dev.vars` containing the Discord values above when exercising Discord locally. Local D1 and R2 bindings do not touch production by default.

```bash
npm run migrate:local
npm test
npm run types
npm run check
```

Deployment is intentionally separate:

```bash
npm run deploy
```
