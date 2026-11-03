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

import { base64url, encodeJsonSegment, encoder } from "./crypto.js";
import { HttpError, json, nowSeconds, requireSession } from "./http.js";

// 60 days, under Apple's six month cap; clients cache the token until it nears expiry.
export const APPLE_DEVELOPER_TOKEN_SECONDS = 60 * 24 * 60 * 60;
// Bounds abuse from one account; cached tokens need only a few a day.
export const APPLE_DAILY_TOKENS = 150;
// Apple only honors the token from these page origins in browsers.
export const APPLE_DEFAULT_ORIGINS = ["https://appassets.androidplatform.net"];

const APPLE_ID_PATTERN = /^[A-Z0-9]{10}$/;

function unavailable() {
  return new HttpError(503, "apple_music_unavailable", "Apple Music is unavailable");
}

async function importPrivateKey(pem) {
  const match = pem.replace(/\\n/g, "\n").trim().match(
    /^-----BEGIN PRIVATE KEY-----\s+([A-Za-z0-9+/=\s]+)\s+-----END PRIVATE KEY-----$/,
  );
  if (!match) throw unavailable();
  const bytes = Uint8Array.from(atob(match[1].replace(/\s/g, "")), (char) => char.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", bytes, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

function origins(env) {
  const configured = String(env.APPLE_MUSIC_ORIGINS || "").split(",").map((origin) => origin.trim()).filter(Boolean);
  return configured.length ? configured : APPLE_DEFAULT_ORIGINS;
}

function limited(code, message, seconds) {
  const error = new HttpError(429, code, message);
  error.headers = { "retry-after": String(seconds) };
  return error;
}

// Atomic increment that stops at the cap; no row comes back once the account is over it.
async function chargeDailyToken(env, userId, now) {
  const day = Math.floor(now / 86400);
  const row = await env.DB.prepare(
    `INSERT INTO apple_token_daily_usage (user_id, day, used) VALUES (?, ?, 1)
     ON CONFLICT (user_id, day) DO UPDATE SET used = used + 1 WHERE used < ? RETURNING used`,
  ).bind(userId, day, APPLE_DAILY_TOKENS).first();
  if (!row) throw limited("apple_daily_limit", "Apple Music token limit reached for today", (day + 1) * 86400 - now);
}

export async function purgeAppleTokenUsage(env, now) {
  await env.DB.prepare("DELETE FROM apple_token_daily_usage WHERE day < ?").bind(Math.floor(now / 86400) - 1).run();
}

async function issueToken(request, env, withOrigin) {
  const session = await requireSession(request, env);
  const keyId = String(env.APPLE_MUSIC_KEY_ID || "").trim();
  const teamId = String(env.APPLE_MUSIC_TEAM_ID || "").trim();
  const pem = env.APPLE_MUSIC_PRIVATE_KEY;
  if (!APPLE_ID_PATTERN.test(keyId) || !APPLE_ID_PATTERN.test(teamId) || typeof pem !== "string" || !pem.trim()) {
    throw unavailable();
  }
  if (env.APPLE_LIMITER) {
    const { success } = await env.APPLE_LIMITER.limit({ key: session.sub });
    if (!success) throw limited("slow_down", "Too many Apple Music token requests", 60);
  }
  const now = nowSeconds();
  await chargeDailyToken(env, session.sub, now);
  const expiresAt = now + APPLE_DEVELOPER_TOKEN_SECONDS;
  const header = encodeJsonSegment({ alg: "ES256", kid: keyId });
  const claims = { iss: teamId, iat: now, exp: expiresAt };
  if (withOrigin) claims.origin = origins(env);
  const input = `${header}.${encodeJsonSegment(claims)}`;
  let signature;
  try {
    const key = await importPrivateKey(pem);
    // Web Crypto returns the fixed-width r || s signature required by ES256.
    signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, encoder.encode(input));
  } catch {
    console.error(JSON.stringify({ event: "apple_music_signing_failed" }));
    throw unavailable();
  }
  return json({ developer_token: `${input}.${base64url(new Uint8Array(signature))}`, expires_at: expiresAt });
}

export function appleDeveloperToken(request, env) {
  return issueToken(request, env, true);
}

// ShazamKit's HTTP client sends no Origin header, so Apple rejects origin-bound tokens with 401.
export function appleShazamToken(request, env) {
  return issueToken(request, env, false);
}
