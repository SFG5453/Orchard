# Orchard release cleaner

An Access-protected admin Worker for the Orchard release R2 bucket. It uses the bucket's `releases/`, `manifests/`, and `objects/` layout. The page lets you retire old manifest files, then inspect and delete content objects that no remaining manifest or live channel bootstrapper references. Nothing is deleted on a scan.

The bucket cannot tell whether an old Orchard installation still needs a release. Decide which versions you no longer support before retiring their manifests. A retired manifest may prevent an old installation from repairing itself. The Worker always blocks deletion of manifests named by any current channel, including stable and canary, and of files younger than the configured minimum age.

## Configure

`wrangler.jsonc` is a tracked placeholder for local checks. `wrangler.production.jsonc` is ignored by Git and is the only config used by `npm run deploy`. Fill its remaining placeholders before deployment. The documented release URL does not reveal the R2 bucket name or the Access application details.

1. In `wrangler.production.jsonc`, bind the existing `orchard-dev` bucket with `"jurisdiction": "us"`. The jurisdiction is required: the same name also exists outside the US jurisdiction and does not contain the canary release.
2. Create a dedicated hostname, such as `admin.sfg545.dev`, as a [Worker Custom Domain](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/). The tracked config uses `admin.example.com` as a placeholder; set the real hostname in `wrangler.production.jsonc`:

   ```jsonc
   "routes": [{ "pattern": "admin.sfg545.dev", "custom_domain": true }]
   ```

3. Create a [Cloudflare Access self-hosted application](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/) for that **entire hostname**. Add an Allow policy for only your admin identity or group. Set `ACCESS_TEAM_DOMAIN` in the production JSONC to your team hostname (for example, `myteam.cloudflareaccess.com`) and `ACCESS_AUD` to that application's Audience tag. The Worker also validates the signed Access JWT; it rejects missing or invalid tokens even if another route reaches it. `workers_dev` is disabled in both configs.
4. Set `MIN_OBJECT_AGE_DAYS` in the production JSONC to the minimum time between upload and deletion eligibility. It defaults to seven days. This protects files uploaded before a release is published.
5. From this directory run `npm ci`, `npm run types`, `npm run check`, `npm test`, and `npx wrangler deploy --config wrangler.production.jsonc --dry-run`. Then run `npm run deploy` when the bucket, hostname, and Access application are configured.

The `RELEASE_BUCKET` binding grants the Worker read and delete access to that bucket. Keep the Access policy narrow. [Cloudflare's R2 binding documentation](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/) describes the list, get, head, and delete operations used here. [Cloudflare's Access guidance](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/) requires origin JWT verification as well as the edge Access policy.

## Use

1. Pause release publishing while cleaning. R2 does not provide a transaction across channel, manifest, and object updates.
2. Open the admin hostname. Review the list of manifests; current channel manifests cannot be selected. Select old platform manifests you no longer support and type `DELETE` when prompted.
3. Scan objects. The scan checks all remaining manifests and current channel bootstrappers, then shows eligible objects in pages of up to 1,000 scanned keys. Select the candidates you want to delete and type `DELETE`. Continue through the pages until finished. To delete every eligible unused object, click **Clean all unused** and type `DELETE`. Cleanup scans every page and deletes in batches of up to 100, reporting progress and stopping on the first error. It keeps all retained manifests and follows the configured minimum object age.

Each delete request rechecks the live references and object versions. Object deletion is limited to 100 keys per request. The Worker leaves unfamiliar key names untouched and refuses to clean if release metadata is missing, malformed, or exceeds its scan bounds (32 channels, 400 manifests, or 100,000 referenced object hashes). The Worker does not delete channel files.

If you keep a version for rollback or repair, keep its manifest. Shared content objects remain protected as long as any retained manifest references them. Retiring a manifest does not immediately delete its objects; those objects become candidates in the next scan only if nothing else uses them and they meet the age limit.
