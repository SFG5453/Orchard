import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";

import worker from "../src/index.js";
import { ARTWORK_MAX_BYTES, validateArtwork } from "../src/artwork.js";
import { signJwt } from "../src/jwt.js";
import { purgeExpired } from "../src/service.js";
import { parseWebp } from "../src/webp.js";
import { createD1 } from "./d1.js";

const now = () => Math.floor(Date.now() / 1000);

// WebP container builders. Payloads carry valid headers but no real pixels,
// which is all the container parser looks at.
function chunk(id, payload) {
  const size = payload.length;
  const out = new Uint8Array(8 + size + (size & 1));
  out.set([...id].map((c) => c.charCodeAt(0)), 0);
  new DataView(out.buffer).setUint32(4, size, true);
  out.set(payload, 8);
  return out;
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function u24(value) {
  return [value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff];
}

function vp8l(width, height) {
  const bits = (width - 1) | ((height - 1) << 14);
  return chunk("VP8L", new Uint8Array([0x2f, bits & 0xff, (bits >> 8) & 0xff, (bits >> 16) & 0xff, (bits >>> 24) & 0xff]));
}

function riff(...chunks) {
  const body = concat(new Uint8Array([...("WEBP")].map((c) => c.charCodeAt(0))), ...chunks);
  return concat(chunk("RIFF", body).subarray(0, 8), body);
}

function animatedWebp({ width = 512, height = 512, frames = 3, durationMs = 40, frameWidth = width, seed = 0 } = {}) {
  const vp8x = new Uint8Array([0x02 | 0x10, 0, 0, 0, ...u24(width - 1), ...u24(height - 1)]);
  const anim = new Uint8Array([seed, 0, 0, 0, 0, 0]);
  const anmf = Array.from({ length: frames }, () =>
    chunk("ANMF", concat(new Uint8Array([...u24(0), ...u24(0), ...u24(frameWidth - 1), ...u24(height - 1), ...u24(durationMs), 0]), vp8l(frameWidth, height))));
  return riff(chunk("VP8X", vp8x), chunk("ANIM", anim), ...anmf);
}

function fakeB2() {
  const objects = new Map();
  return { objects, async fetch(request) {
    assert.match(request.headers.get("authorization"), /^AWS4-HMAC-SHA256 /);
    const key = new URL(request.url).pathname.replace(/^\/test-bucket\//, "");
    if (request.method === "PUT") {
      objects.set(key, new Uint8Array(await request.arrayBuffer()));
      return new Response(null, { status: 200 });
    }
    if (request.method === "DELETE") {
      objects.delete(key);
      return new Response(null, { status: 204 });
    }
    const bytes = objects.get(key);
    return bytes ? new Response(request.method === "HEAD" ? null : bytes) : new Response(null, { status: 404 });
  }};
}

function index(auth = token, query = "") {
  return worker.fetch(new Request(`https://account.test/artwork/index${query}`, {
    headers: { authorization: `Bearer ${auth}` },
  }), env);
}

function sourceSha(source) {
  return crypto.subtle.digest("SHA-256", new TextEncoder().encode(source)).then((digest) =>
    [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join(""));
}

async function uploadFrom(source, bytes) {
  return worker.fetch(new Request("https://account.test/artwork", {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "cf-connecting-ip": "203.0.113.7",
      "x-orchard-source-sha256": await sourceSha(source) },
    body: bytes,
  }), env);
}

let env;
let token;

async function session(userId = "user-1", deviceId = "device-1") {
  env.DB.raw.prepare("INSERT OR IGNORE INTO users (id, created_at, updated_at) VALUES (?, 0, 0)").run(userId);
  env.DB.raw.prepare(
    "INSERT OR IGNORE INTO devices (id, user_id, name, platform, refresh_hash, rotated_at, created_at, last_seen_at) VALUES (?, ?, 'Desk', 'linux', ?, 0, 0, ?)",
  ).run(deviceId, userId, `hash-${deviceId}`, now());
  return signJwt({ iss: "https://account.test", aud: "orchard", sub: userId, did: deviceId, iat: now(), exp: now() + 900 }, env.SIGNING_KEY);
}

function upload(bytes, { auth = token, ip = "203.0.113.7" } = {}) {
  return worker.fetch(new Request("https://account.test/artwork", {
    method: "POST",
    headers: { authorization: `Bearer ${auth}`, "content-type": "image/webp", "cf-connecting-ip": ip },
    body: bytes,
  }), env);
}

function fetchArtwork(url, method = "GET") {
  const filename = new URL(url).pathname.split("/").at(-1);
  return worker.fetch(new Request(`https://account.test/artwork/${filename}`, { method }), env);
}

beforeEach(async () => {
  const { privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  env = {
    DB: createD1(),
    B2_REGION: "us-east-005",
    B2_BUCKET: "test-bucket",
    B2_KEY_ID: "test-key",
    B2_APPLICATION_KEY: "test-secret",
    PUBLIC_URL: "https://account.test",
    ARTWORK_PUBLIC_URL: "https://artwork.test",
    SIGNING_KEY: JSON.stringify({ ...(await crypto.subtle.exportKey("jwk", privateKey)), kid: "k" }),
  };
  const b2 = fakeB2();
  env.B2_FETCH = b2.fetch;
  env.B2_STORE = b2.objects;
  token = await session();
});

describe("webp parser", () => {
  it("reads animated geometry and timing", () => {
    assert.deepEqual(parseWebp(animatedWebp({ frames: 4, durationMs: 33 })),
      { width: 512, height: 512, animated: true, frames: 4, durationMs: 132, alpha: true });
  });

  it("reads simple lossless images", () => {
    assert.deepEqual(parseWebp(riff(vp8l(300, 200))),
      { width: 300, height: 200, animated: false, frames: 1, durationMs: 0, alpha: true });
  });

  it("rejects malformed containers", () => {
    const good = animatedWebp();
    assert.throws(() => parseWebp(good.subarray(0, good.length - 5)), /Truncated/);
    assert.throws(() => parseWebp(concat(good, new Uint8Array(16))), /Trailing/);
    assert.throws(() => parseWebp(new TextEncoder().encode("GIF89a not a webp at all")), /Not a WebP/);
    assert.throws(() => parseWebp(animatedWebp({ width: 100, frameWidth: 200 })), /exceeds canvas/);
  });
});

describe("artwork upload", () => {
  it("stores, serves, and deduplicates", async () => {
    const bytes = animatedWebp();
    const first = await uploadFrom("https://cdn.example/one.mp4", bytes);
    assert.equal(first.status, 201);
    const body = await first.json();
    assert.match(body.url, /^https:\/\/artwork\.test\/[0-9a-f]{64}\.webp$/);
    assert.equal(body.reused, false);
    assert.equal(body.frames, 3);
    assert.equal(body.expires_at, null);
    assert.equal(env.B2_STORE.has(`${body.sha256}.webp`), true);

    const served = await fetchArtwork(body.url);
    assert.equal(served.headers.get("content-type"), "image/webp");
    assert.deepEqual(new Uint8Array(await served.arrayBuffer()), bytes);
    const head = await fetchArtwork(body.url, "HEAD");
    assert.equal(head.status, 200);
    assert.ok(Number(head.headers.get("x-orchard-expires")) > now() + 50 * 365 * 86400);

    const again = await upload(bytes);
    assert.equal(again.status, 200);
    assert.equal((await again.json()).reused, true);
    assert.equal(env.B2_STORE.size, 1);
  });

  it("keeps older desktop builds working through the account URL", async () => {
    const result = await (await upload(animatedWebp())).json();
    assert.match(result.url, /^https:\/\/account\.test\/artwork\/[0-9a-f]{64}\.webp$/);
    assert.ok(result.expires_at > now() + 50 * 365 * 86400);
    assert.equal((await fetchArtwork(result.url)).status, 200);
    assert.equal((await (await index()).json()).files.length, 0);
  });

  it("reuses permanent artwork even when a legacy expiry value remains", async () => {
    const bytes = animatedWebp();
    const source = "https://cdn.example/reuse.mp4";
    const { sha256 } = await (await uploadFrom(source, bytes)).json();
    env.DB.raw.prepare("UPDATE artwork SET expires_at = ?").run(now() - 60);
    const refreshed = await (await uploadFrom(source, bytes)).json();
    assert.equal(refreshed.sha256, sha256);
    assert.equal(refreshed.reused, true);
    assert.equal(refreshed.expires_at, null);
  });

  it("indexes hosted files by source for the same account", async () => {
    const source = "https://cdn.example/motion.mp4";
    const sha = await sourceSha(source);
    const uploaded = await (await uploadFrom(source, animatedWebp())).json();
    const listed = await index();
    assert.equal(listed.headers.get("cache-control"), "private, no-store");
    assert.deepEqual((await listed.json()), { files: [{
      source_sha256: sha, url: uploaded.url, expires_at: uploaded.expires_at,
    }], next_cursor: null });
    assert.equal((await (await index(token, `?source_sha256=${sha}`)).json()).files.length, 1);
    assert.deepEqual((await (await index("nope")).json()).error, "invalid_token");

    const other = await session("user-2", "device-2");
    assert.deepEqual((await worker.fetch(new Request("https://account.test/artwork/index", {
      headers: { authorization: `Bearer ${other}` },
    }), env).then((response) => response.json())).files, []);
  });

  it("does not advertise legacy R2 rows and keeps B2 rows permanent", async () => {
    const source = "https://cdn.example/old.mp4";
    const uploaded = await (await uploadFrom(source, animatedWebp())).json();
    env.DB.raw.prepare("UPDATE artwork SET storage = 'r2'").run();
    assert.deepEqual((await (await index()).json()).files, []);
    assert.equal((await fetchArtwork(uploaded.url, "HEAD")).status, 404);
    env.DB.raw.prepare("UPDATE artwork SET storage = 'b2', expires_at = ?").run(now() - 1);
    assert.equal((await (await index()).json()).files.length, 1);
  });

  it("pages a growing permanent index", async () => {
    const { sha256 } = await (await upload(animatedWebp())).json();
    const insert = env.DB.raw.prepare(
      "INSERT INTO artwork_sources (user_id, source_sha256, hash, created_at) VALUES ('user-1', ?, ?, 0)");
    for (let i = 0; i < 501; i++) insert.run(i.toString(16).padStart(64, "0"), sha256);
    const first = await (await index()).json();
    assert.equal(first.files.length, 500);
    assert.ok(first.next_cursor);
    const second = await (await index(token, `?cursor=${first.next_cursor}`)).json();
    assert.equal(second.files.length, 1);
    assert.equal(second.next_cursor, null);
  });

  it("requires a session", async () => {
    assert.equal((await upload(animatedWebp(), { auth: "nope" })).status, 401);
  });

  it("rejects bad artwork", async () => {
    assert.equal((await upload(new TextEncoder().encode("definitely a gif"))).status, 415);
    assert.equal((await upload(animatedWebp({ width: 2048, height: 2048 }))).status, 422);
    assert.throws(() => validateArtwork(animatedWebp({ frames: 1201, width: 16, height: 16 })), /at most 1200 frames/);
    assert.throws(() => validateArtwork(animatedWebp({ durationMs: 10 })), /frames per second/);
    assert.equal((await upload(new Uint8Array(ARTWORK_MAX_BYTES + 1))).status, 413);
    assert.equal(env.B2_STORE.size, 0);
  });
});

describe("upload limits", () => {
  it("allows a burst of three, then asks the client to wait", async () => {
    for (let i = 0; i < 3; i++) assert.equal((await upload(animatedWebp({ seed: i }))).status, 201);
    const limited = await upload(animatedWebp({ seed: 9 }));
    assert.equal(limited.status, 429);
    const retryAfter = Number(limited.headers.get("retry-after"));
    assert.ok(retryAfter >= 1 && retryAfter <= 30, `retry-after ${retryAfter}`);
  });

  it("counts failed attempts too", async () => {
    for (let i = 0; i < 3; i++) await upload(new Uint8Array([1, 2, 3]));
    assert.equal((await upload(animatedWebp())).status, 429);
  });

  it("caps concurrent uploads per user", async () => {
    for (const id of ["a", "b"]) {
      env.DB.raw.prepare("INSERT INTO artwork_uploads (id, user_id, ip, started_at) VALUES (?, 'user-1', 'x', ?)").run(id, now() - 200 + 100);
    }
    const limited = await upload(animatedWebp());
    assert.equal(limited.status, 429);
    assert.match((await limited.json()).error_description, /in progress/);
  });

  it("forgets uploads that never finished", async () => {
    for (const id of ["a", "b"]) {
      env.DB.raw.prepare("INSERT INTO artwork_uploads (id, user_id, ip, started_at) VALUES (?, 'user-1', 'x', ?)").run(id, now() - 3000);
    }
    assert.equal((await upload(animatedWebp())).status, 201);
  });

  it("enforces the hourly and daily user limits", async () => {
    const insert = env.DB.raw.prepare("INSERT INTO artwork_uploads (id, user_id, ip, started_at, finished_at) VALUES (?, 'user-1', 'x', ?, ?)");
    for (let i = 0; i < 20; i++) insert.run(`h${i}`, now() - 600, now() - 600);
    const limited = await upload(animatedWebp());
    assert.equal(limited.status, 429);
    assert.ok(Number(limited.headers.get("retry-after")) > 2900);
  });

  it("limits uploads per IP across accounts", async () => {
    const insert = env.DB.raw.prepare("INSERT INTO artwork_uploads (id, user_id, ip, started_at, finished_at) VALUES (?, ?, '198.51.100.1', ?, ?)");
    for (let i = 0; i < 50; i++) insert.run(`ip${i}`, `farm-${i}`, now() - 100, now() - 100);
    assert.equal((await upload(animatedWebp(), { ip: "198.51.100.1" })).status, 429);
    assert.equal((await upload(animatedWebp(), { ip: "198.51.100.2" })).status, 201);
  });
});

describe("permanent artwork", () => {
  it("keeps objects and rows during the hourly cleanup", async () => {
    const { url } = await (await upload(animatedWebp())).json();
    await purgeExpired(env, now() + 365 * 86400);
    assert.equal(env.B2_STORE.size, 1);
    assert.equal((await fetchArtwork(url)).status, 200);
  });

  it("serves B2 artwork regardless of legacy expiry values", async () => {
    const { url } = await (await upload(animatedWebp())).json();
    env.DB.raw.prepare("UPDATE artwork SET expires_at = ?").run(now() - 1);
    assert.equal((await fetchArtwork(url)).status, 200);
  });
});
