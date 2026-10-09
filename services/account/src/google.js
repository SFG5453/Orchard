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

import { decodeJsonSegment } from "./crypto.js";

const AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);

export function googleCallbackUrl(env) {
  return new URL("/auth/google/callback", env.PUBLIC_URL).toString();
}

export function googleAuthorizeUrl(env, { state, codeChallenge }) {
  const url = new URL(AUTHORIZE_URL);
  url.search = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID,
    redirect_uri: googleCallbackUrl(env),
    response_type: "code",
    // Basic scopes only: no YouTube access, no Google security review.
    scope: "openid email profile",
    state,
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
    prompt: "select_account",
  }).toString();
  return url.toString();
}

// Exchanges the code and returns the ID token's claims.
export async function exchangeGoogleCode(env, { code, codeVerifier, now = Math.floor(Date.now() / 1000) }) {
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: googleCallbackUrl(env),
      grant_type: "authorization_code",
      code_verifier: codeVerifier,
    }),
  });
  if (!response.ok) {
    throw new Error(`Google token exchange failed with HTTP ${response.status}`);
  }
  const body = await response.json();
  const parts = String(body.id_token || "").split(".");
  if (parts.length !== 3) throw new Error("Google returned no ID token");

  // The token came straight from Google over TLS, so OIDC Core 3.1.3.7 lets
  // us skip signature checks. The claims still have to be for us.
  const claims = decodeJsonSegment(parts[1]);
  if (!ISSUERS.has(claims.iss)) throw new Error("Unexpected ID token issuer");
  if (claims.aud !== env.GOOGLE_CLIENT_ID) throw new Error("ID token audience mismatch");
  if (typeof claims.exp !== "number" || claims.exp <= now) throw new Error("ID token expired");
  if (!claims.sub) throw new Error("ID token has no subject");
  return claims;
}
