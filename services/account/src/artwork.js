/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Permanent WebP artwork hosting for Discord Rich Presence. Content-addressed
// and validated here before storage in B2.

import { HttpError, json, nowSeconds, requireSession } from "./http.js";
import { getArtworkObject, putArtworkObject } from "./b2.js";
import { WebpError, parseWebp } from "./webp.js";

// A full Apple Music motion loop at 512px q90 lands around 7-9 MiB.
export const ARTWORK_MAX_BYTES = 10 * 1024 * 1024;
export const ARTWORK_MAX_DIMENSION = 1024;
// About 40 s at 30 fps; motion artwork loops run 20-35 s.
export const ARTWORK_MAX_FRAMES = 1200;
export const ARTWORK_MAX_FPS = 60;

// [window seconds, max uploads] per user, plus the per-IP and in-flight caps.
export const USER_UPLOAD_LIMITS = [[30, 3], [60 * 60, 20], [24 * 60 * 60, 100]];
export const IP_UPLOAD_LIMIT = [60 * 60, 50];
export const MAX_CONCURRENT_UPLOADS = 2;
// Uploads that never finish (worker killed mid-request) stop counting after this.
const UPLOAD_LEASE_SECONDS = 120;

const HASH_PATTERN = /^\/artwork\/([0-9a-f]{64})\.webp$/;
const SHA_PATTERN = /^[0-9a-f]{64}$/;
// Older clients require a numeric expiry to accept an upload response. The
// value is only a compatibility marker; B2 files have no expiry policy here.
const LEGACY_COMPAT_EXPIRES_AT = 4102444800;

function objectKey(hash) {
  return `${hash}.webp`;
}

function publicUrl(env, hash) {
  return new URL(`/${objectKey(hash)}`, env.ARTWORK_PUBLIC_URL || env.PUBLIC_URL).toString();
}

function legacyUrl(env, hash) {
  return new URL(`/artwork/${objectKey(hash)}`, env.PUBLIC_URL).toString();
}

function hex(bytes) {
  return [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function describe(row, env, reused, legacyClient) {
  return {
    url: legacyClient ? legacyUrl(env, row.hash) : publicUrl(env, row.hash),
    sha256: row.hash,
    expires_at: legacyClient ? LEGACY_COMPAT_EXPIRES_AT : null,
    width: row.width,
    height: row.height,
    frames: row.frames,
    bytes: row.bytes,
    reused,
  };
}

function rateLimited(retryAfter, description) {
  const seconds = Math.max(1, Math.ceil(retryAfter));
  const error = new HttpError(429, "rate_limited", description);
  error.headers = { "retry-after": String(seconds) };
  return error;
}

// Atomic check-and-insert: D1 serializes writes, so no two requests can both
// take the last slot.
async function acquireUploadSlot(env, userId, ip) {
  const now = nowSeconds();
  const id = crypto.randomUUID();
  const userChecks = USER_UPLOAD_LIMITS.map(([window, limit]) =>
    `(SELECT COUNT(*) FROM artwork_uploads WHERE user_id = ?2 AND started_at > ?4 - ${window}) < ${limit}`);
  const result = await env.DB.prepare(
    `INSERT INTO artwork_uploads (id, user_id, ip, started_at)
     SELECT ?1, ?2, ?3, ?4
     WHERE (SELECT COUNT(*) FROM artwork_uploads
            WHERE user_id = ?2 AND finished_at IS NULL AND started_at > ?4 - ${UPLOAD_LEASE_SECONDS}) < ${MAX_CONCURRENT_UPLOADS}
       AND ${userChecks.join(" AND ")}
       AND (SELECT COUNT(*) FROM artwork_uploads WHERE ip = ?3 AND started_at > ?4 - ${IP_UPLOAD_LIMIT[0]}) < ${IP_UPLOAD_LIMIT[1]}`,
  ).bind(id, userId, ip, now).run();
  if (result.meta?.changes) return id;
  throw await explainRejection(env, userId, ip, now);
}

async function explainRejection(env, userId, ip, now) {
  const inFlight = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM artwork_uploads WHERE user_id = ? AND finished_at IS NULL AND started_at > ?",
  ).bind(userId, now - UPLOAD_LEASE_SECONDS).first();
  if (inFlight.n >= MAX_CONCURRENT_UPLOADS) return rateLimited(5, "Too many uploads in progress");

  const windows = [
    ...USER_UPLOAD_LIMITS.map(([window, limit]) => ["user_id", userId, window, limit]),
    ["ip", ip, ...IP_UPLOAD_LIMIT],
  ];
  for (const [column, value, window, limit] of windows) {
    // The slot frees when the oldest attempt that still counts ages out.
    const { results } = await env.DB.prepare(
      `SELECT started_at FROM artwork_uploads WHERE ${column} = ? AND started_at > ? ORDER BY started_at DESC LIMIT ?`,
    ).bind(value, now - window, limit).all();
    if (results.length >= limit) {
      return rateLimited(results[limit - 1].started_at + window - now, "Artwork upload limit reached");
    }
  }
  return rateLimited(5, "Artwork upload limit reached");
}

async function readLimitedBody(request) {
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > ARTWORK_MAX_BYTES) {
    throw new HttpError(413, "too_large", `Artwork must be at most ${ARTWORK_MAX_BYTES} bytes`);
  }
  if (!request.body) throw new HttpError(400, "invalid_request", "Missing artwork body");
  const reader = request.body.getReader();
  const parts = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > ARTWORK_MAX_BYTES) {
      await reader.cancel();
      throw new HttpError(413, "too_large", `Artwork must be at most ${ARTWORK_MAX_BYTES} bytes`);
    }
    parts.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.byteLength;
  }
  return bytes;
}

// Throws unless the bytes are a WebP inside the Discord artwork limits.
export function validateArtwork(bytes) {
  let info;
  try {
    info = parseWebp(bytes);
  } catch (error) {
    if (error instanceof WebpError) throw new HttpError(415, "invalid_webp", error.message);
    throw error;
  }
  if (info.width > ARTWORK_MAX_DIMENSION || info.height > ARTWORK_MAX_DIMENSION) {
    throw new HttpError(422, "too_large", `Artwork must be at most ${ARTWORK_MAX_DIMENSION}x${ARTWORK_MAX_DIMENSION}`);
  }
  if (info.frames > ARTWORK_MAX_FRAMES) {
    throw new HttpError(422, "too_many_frames", `Artwork must have at most ${ARTWORK_MAX_FRAMES} frames`);
  }
  if (info.animated && (info.durationMs <= 0 || (info.frames * 1000) / info.durationMs > ARTWORK_MAX_FPS)) {
    throw new HttpError(422, "frame_rate", `Artwork must play at most ${ARTWORK_MAX_FPS} frames per second`);
  }
  return info;
}

async function linkSource(env, userId, sourceSha, hash, now) {
  if (!sourceSha) return;
  await env.DB.prepare(
    `INSERT INTO artwork_sources (user_id, source_sha256, hash, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (user_id, source_sha256) DO UPDATE SET hash = excluded.hash, created_at = excluded.created_at`,
  ).bind(userId, sourceSha, hash, now).run();
}

async function storeArtwork(env, bytes, userId, sourceSha) {
  const info = validateArtwork(bytes);
  const hash = hex(await crypto.subtle.digest("SHA-256", bytes));
  const now = nowSeconds();

  const existing = await env.DB.prepare("SELECT * FROM artwork WHERE hash = ?").bind(hash).first();
  if (existing?.storage === "b2") {
    await linkSource(env, userId, sourceSha, hash, now);
    return json(describe(existing, env, true, !sourceSha));
  }

  await putArtworkObject(env, objectKey(hash), bytes);
  const row = await env.DB.prepare(
    `INSERT INTO artwork (hash, bytes, width, height, frames, uploaded_by, created_at, expires_at, storage)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'b2')
     ON CONFLICT (hash) DO UPDATE SET created_at = excluded.created_at, expires_at = 0, storage = 'b2'
     RETURNING *`,
  ).bind(hash, bytes.byteLength, info.width, info.height, info.frames, userId, now, 0).first();
  await linkSource(env, userId, sourceSha, hash, now);
  return json(describe(row, env, false, !sourceSha), 201);
}

export async function uploadArtwork(request, env) {
  const claims = await requireSession(request, env);
  const sourceSha = request.headers.get("x-orchard-source-sha256");
  if (sourceSha && !SHA_PATTERN.test(sourceSha)) {
    throw new HttpError(400, "invalid_request", "Invalid source SHA-256");
  }
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const slot = await acquireUploadSlot(env, claims.sub, ip);
  try {
    return await storeArtwork(env, await readLimitedBody(request), claims.sub, sourceSha);
  } finally {
    await env.DB.prepare("UPDATE artwork_uploads SET finished_at = ? WHERE id = ?").bind(nowSeconds(), slot).run();
  }
}

export async function listArtwork(request, env) {
  const claims = await requireSession(request, env);
  const params = new URL(request.url).searchParams;
  const sourceSha = params.get("source_sha256");
  const cursor = params.get("cursor");
  if ((sourceSha && !SHA_PATTERN.test(sourceSha)) || (cursor && !SHA_PATTERN.test(cursor))) {
    throw new HttpError(400, "invalid_request", "Invalid artwork index digest");
  }
  const { results } = await env.DB.prepare(
    `SELECT s.source_sha256, a.hash FROM artwork_sources s
     JOIN artwork a ON a.hash = s.hash
     WHERE s.user_id = ? AND a.storage = 'b2' AND s.source_sha256 > ?
       AND (? IS NULL OR s.source_sha256 = ?)
     ORDER BY s.source_sha256 LIMIT 501`,
  ).bind(claims.sub, cursor || "", sourceSha, sourceSha).all();
  const page = results.slice(0, 500);
  return json({ files: page.map((row) => ({
    source_sha256: row.source_sha256,
    url: publicUrl(env, row.hash),
    expires_at: null,
  })), next_cursor: results.length > 500 ? page.at(-1).source_sha256 : null },
  200, { "cache-control": "private, no-store" });
}

export async function serveArtwork(request, env) {
  const match = new URL(request.url).pathname.match(HASH_PATTERN);
  if (!match) throw new HttpError(404, "not_found", "No such artwork");
  const row = await env.DB.prepare("SELECT * FROM artwork WHERE hash = ? AND storage = 'b2'").bind(match[1]).first();
  if (!row) throw new HttpError(404, "not_found", "No such artwork");

  const headers = {
    "content-type": "image/webp",
    "cache-control": "public, max-age=31536000, immutable",
    etag: `"${row.hash}"`,
    "x-orchard-expires": String(LEGACY_COMPAT_EXPIRES_AT),
  };
  const object = await getArtworkObject(env, objectKey(row.hash), request.method);
  if (!object) throw new HttpError(404, "not_found", "No such artwork");
  if (request.method === "HEAD") {
    return new Response(null, { headers: { ...headers, "content-length": String(row.bytes) } });
  }
  return new Response(object.body, { headers });
}

export function isArtworkPath(pathname) {
  return HASH_PATTERN.test(pathname);
}

export async function purgeArtwork(env, now = nowSeconds()) {
  // Only the quota ledger ages out. Artwork and its source index stay put.
  await env.DB.prepare("DELETE FROM artwork_uploads WHERE started_at <= ?").bind(now - 24 * 60 * 60).run();
}
