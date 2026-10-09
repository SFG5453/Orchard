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

import { base64url, decodeJsonSegment, encodeJsonSegment, encoder, fromBase64url } from "./crypto.js";

export const ACCESS_TOKEN_AUDIENCE = "orchard";

const keyCache = new Map();

function parseJwk(json) {
  const jwk = typeof json === "string" ? JSON.parse(json) : json;
  if (jwk.kty !== "OKP" || jwk.crv !== "Ed25519" || !jwk.kid) {
    throw new Error("Signing key must be an Ed25519 JWK with a kid");
  }
  return jwk;
}

export function publicJwk(privateJwkJson) {
  const { kty, crv, x, kid } = parseJwk(privateJwkJson);
  return { kty, crv, x, kid, alg: "EdDSA", use: "sig" };
}

async function importKey(jwk, usage) {
  const cacheKey = `${usage}:${jwk.kid}:${jwk.x}`;
  let key = keyCache.get(cacheKey);
  if (!key) {
    // Drop "alg": Node writes "Ed25519", workerd only accepts "EdDSA".
    const { kty, crv, x, d } = jwk;
    const material = usage === "sign" ? { kty, crv, x, d } : { kty, crv, x };
    key = await crypto.subtle.importKey("jwk", material, { name: "Ed25519" }, false, [usage]);
    keyCache.set(cacheKey, key);
  }
  return key;
}

export async function signJwt(claims, privateJwkJson) {
  const jwk = parseJwk(privateJwkJson);
  const header = encodeJsonSegment({ alg: "EdDSA", typ: "JWT", kid: jwk.kid });
  const body = encodeJsonSegment(claims);
  const input = `${header}.${body}`;
  const signature = await crypto.subtle.sign({ name: "Ed25519" }, await importKey(jwk, "sign"), encoder.encode(input));
  return `${input}.${base64url(new Uint8Array(signature))}`;
}

// Returns the claims, or null for anything malformed, forged, or expired.
export async function verifyJwt(token, privateJwkJson, { issuer, now = Math.floor(Date.now() / 1000) } = {}) {
  const parts = String(token || "").split(".");
  if (parts.length !== 3) return null;
  let header;
  let claims;
  try {
    header = decodeJsonSegment(parts[0]);
    claims = decodeJsonSegment(parts[1]);
  } catch {
    return null;
  }
  const jwk = parseJwk(privateJwkJson);
  if (header.alg !== "EdDSA" || header.kid !== jwk.kid) return null;
  const valid = await crypto.subtle.verify(
    { name: "Ed25519" },
    await importKey(jwk, "verify"),
    fromBase64url(parts[2]),
    encoder.encode(`${parts[0]}.${parts[1]}`),
  );
  if (!valid) return null;
  if (typeof claims.exp !== "number" || claims.exp <= now) return null;
  if (claims.aud !== ACCESS_TOKEN_AUDIENCE) return null;
  if (issuer && claims.iss !== issuer) return null;
  return claims;
}
