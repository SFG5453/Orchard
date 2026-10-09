import { createRemoteJWKSet, jwtVerify } from "jose";
import { BadBucket, Conflict, inventory, isObjectKey, isOldEnough, liveHashes, objectAgeDays } from "./releases";
import { html, script, style } from "./ui";

const MAX_BODY = 32 * 1024;
const MAX_DELETE = 100;

function json(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}

async function authenticated(request: Request, env: Env): Promise<boolean> {
  const domain = env.ACCESS_TEAM_DOMAIN;
  const audience = env.ACCESS_AUD;
  if (!domain || domain.includes("REPLACE_WITH") || !/^[a-z0-9.-]+\.cloudflareaccess\.com$/i.test(domain) ||
      !audience || audience.includes("REPLACE_WITH"))
    throw new BadBucket("Cloudflare Access settings are missing");
  const token = request.headers.get("Cf-Access-Jwt-Assertion");
  if (!token) return false;
  try {
    const issuer = `https://${domain}`;
    const keySet = createRemoteJWKSet(new URL(`${issuer}/cdn-cgi/access/certs`));
    await jwtVerify(token, keySet, { issuer, audience, algorithms: ["RS256"] });
    return true;
  } catch { return false; }
}

async function body(request: Request): Promise<Record<string, unknown>> {
  if (request.headers.get("Content-Type")?.split(";", 1)[0] !== "application/json")
    throw new BadBucket("Expected application/json");
  if (!request.body) throw new BadBucket("Missing request body");
  const reader = request.body.getReader();
  const decoder = new TextDecoder();
  let length = 0;
  let text = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > MAX_BODY) {
      await reader.cancel();
      throw new BadBucket("Request body is too large");
    }
    text += decoder.decode(value, { stream: true });
  }
  text += decoder.decode();
  let data: unknown;
  try { data = JSON.parse(text); }
  catch { throw new BadBucket("Invalid JSON body"); }
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new BadBucket("Expected JSON object");
  return data as Record<string, unknown>;
}

function mutationAllowed(request: Request): boolean {
  const origin = request.headers.get("Origin");
  return origin === new URL(request.url).origin && request.headers.get("X-Orchard-Admin") === "1";
}

async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/")
    return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store",
      "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer" } });
  if (request.method === "GET" && url.pathname === "/ui.js")
    return new Response(script, { headers: { "Content-Type": "text/javascript; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
  if (request.method === "GET" && url.pathname === "/ui.css")
    return new Response(style, { headers: { "Content-Type": "text/css; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
  if (request.method === "GET" && url.pathname === "/api/manifests") {
    const state = await inventory(env.RELEASE_BUCKET);
    const days = objectAgeDays(env.MIN_OBJECT_AGE_DAYS);
    return json({ channels: state.channelDetails.map((entry) => ({
      key: entry.key, name: entry.name, version: entry.version, platforms: entry.platforms,
      uploaded: entry.uploaded.toISOString(),
    })), manifests: state.manifests.map((entry) => ({
      key: entry.key, version: entry.version, size: entry.size, uploaded: entry.uploaded.toISOString(),
      active: state.active.has(entry.key), activeChannels: state.activeBy.get(entry.key) ?? [],
      eligible: !state.active.has(entry.key) && isOldEnough(entry.uploaded, days),
    })), minAgeDays: days });
  }
  if (request.method === "GET" && url.pathname === "/api/orphans") {
    const state = await inventory(env.RELEASE_BUCKET);
    const days = objectAgeDays(env.MIN_OBJECT_AGE_DAYS);
    const hashes = await liveHashes(env.RELEASE_BUCKET, state);
    const cursor = url.searchParams.get("cursor") || undefined;
    const page = await env.RELEASE_BUCKET.list({ prefix: "objects/", cursor, limit: 1000 });
    const candidates = page.objects.filter((entry) => isObjectKey(entry.key) &&
      !hashes.has(entry.key.slice(8)) && isOldEnough(entry.uploaded, days));
    return json({ scanned: page.objects.length, candidates: candidates.map((entry) => ({
      key: entry.key, version: entry.version, size: entry.size, uploaded: entry.uploaded.toISOString(),
    })), cursor: page.truncated ? page.cursor : null, minAgeDays: days });
  }
  if (request.method === "POST" && url.pathname === "/api/retire") {
    if (!mutationAllowed(request)) return json({ error: "Origin or admin header missing" }, 403);
    const data = await body(request);
    if (data.confirm !== "DELETE" || !Array.isArray(data.manifests) ||
        data.manifests.length < 1 || data.manifests.length > MAX_DELETE)
      throw new BadBucket("Select 1 to 100 manifests and confirm DELETE");
    const state = await inventory(env.RELEASE_BUCKET);
    const days = objectAgeDays(env.MIN_OBJECT_AGE_DAYS);
    const known = new Map(state.manifests.map((entry) => [entry.key, entry]));
    const keys: string[] = [];
    for (const item of data.manifests) {
      if (!item || typeof item !== "object" || Array.isArray(item)) throw new BadBucket("Invalid manifest selection");
      const { key, version } = item as { key?: unknown; version?: unknown };
      if (typeof key !== "string" || typeof version !== "string" || !known.has(key))
        throw new Conflict("Manifest list changed; refresh before deleting");
      if (state.active.has(key)) throw new Conflict(`Live channel still uses ${key}`);
      if (known.get(key)?.version !== version) throw new Conflict(`${key} changed; refresh before deleting`);
      if (!isOldEnough(known.get(key)!.uploaded, days)) throw new Conflict(`${key} is too new to retire`);
      keys.push(key);
    }
    if (new Set(keys).size !== keys.length) throw new BadBucket("Duplicate manifest selection");
    // Re-read channels just before the destructive operation.
    const fresh = await inventory(env.RELEASE_BUCKET);
    if (keys.some((key) => fresh.active.has(key))) throw new Conflict("A selected manifest is now live");
    for (const key of keys) {
      const before = known.get(key);
      const current = await env.RELEASE_BUCKET.head(key);
      if (!current || current.version !== before?.version || !isOldEnough(current.uploaded, days))
        throw new Conflict(`${key} changed; refresh before deleting`);
    }
    await env.RELEASE_BUCKET.delete(keys);
    console.log(JSON.stringify({ action: "retire_manifests", count: keys.length, keys }));
    return json({ deleted: keys });
  }
  if (request.method === "POST" && url.pathname === "/api/delete-objects") {
    if (!mutationAllowed(request)) return json({ error: "Origin or admin header missing" }, 403);
    const data = await body(request);
    if (data.confirm !== "DELETE" || !Array.isArray(data.objects) ||
        data.objects.length < 1 || data.objects.length > MAX_DELETE)
      throw new BadBucket("Select 1 to 100 objects and confirm DELETE");
    const state = await inventory(env.RELEASE_BUCKET);
    const hashes = await liveHashes(env.RELEASE_BUCKET, state);
    const days = objectAgeDays(env.MIN_OBJECT_AGE_DAYS);
    const keys: string[] = [];
    for (const item of data.objects) {
      if (!item || typeof item !== "object" || Array.isArray(item)) throw new BadBucket("Invalid object selection");
      const { key, version } = item as { key?: unknown; version?: unknown };
      if (typeof key !== "string" || !isObjectKey(key) || typeof version !== "string")
        throw new BadBucket("Invalid object key or version");
      if (hashes.has(key.slice(8))) throw new Conflict(`${key} is still referenced`);
      const current = await env.RELEASE_BUCKET.head(key);
      if (!current || current.version !== version || !isOldEnough(current.uploaded, days))
        throw new Conflict(`${key} changed or is too new; rescan before deleting`);
      keys.push(key);
    }
    if (new Set(keys).size !== keys.length) throw new BadBucket("Duplicate object selection");
    const fresh = await inventory(env.RELEASE_BUCKET);
    if (fresh.channelDetails.map((entry) => `${entry.key}:${entry.objectVersion}`).join("\n") !==
        state.channelDetails.map((entry) => `${entry.key}:${entry.objectVersion}`).join("\n") ||
        fresh.manifests.map((entry) => `${entry.key}:${entry.version}`).join("\n") !==
        state.manifests.map((entry) => `${entry.key}:${entry.version}`).join("\n"))
      throw new Conflict("Release metadata changed during scan; rescan before deleting");
    await env.RELEASE_BUCKET.delete(keys);
    console.log(JSON.stringify({ action: "delete_orphan_objects", count: keys.length }));
    return json({ deleted: keys });
  }
  return json({ error: "Not found" }, 404);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      if (!await authenticated(request, env)) return json({ error: "Cloudflare Access authentication required" }, 401);
      return await handle(request, env);
    } catch (error) {
      if (error instanceof BadBucket) return json({ error: error.message }, 400);
      if (error instanceof Conflict) return json({ error: error.message }, 409);
      console.error(JSON.stringify({ action: "request_failed", error: error instanceof Error ? error.message : String(error) }));
      return json({ error: "Internal error; check Worker logs" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
