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

import { isArtworkPath, listArtwork, purgeArtwork, serveArtwork, uploadArtwork } from "./artwork.js";
import { openHub } from "./connect.js";
import { hashSecret, pkceChallenge, randomToken } from "./crypto.js";
import { finishGithubLink, githubIdentity, publicGithub, startGithubLink, unlinkGithub } from "./github.js";
import { exchangeGoogleCode, googleAuthorizeUrl } from "./google.js";
import { HttpError, htmlPage, issuer, json, nowSeconds, readJson, requireSession } from "./http.js";
import { ACCESS_TOKEN_AUDIENCE, publicJwk, signJwt } from "./jwt.js";
import { createReport, getReport, isScreenshotPath, listReports, markReportRead, serveScreenshot } from "./support.js";
import { githubWebhook } from "./support_sync.js";

export const ACCESS_TOKEN_SECONDS = 15 * 60;
export const AUTH_REQUEST_SECONDS = 10 * 60;
export const AUTH_CODE_SECONDS = 2 * 60;
// Covers a client that lost the rotated token to a dropped response.
export const REFRESH_REUSE_GRACE_SECONDS = 60;
export const REFRESH_IDLE_SECONDS = 90 * 24 * 60 * 60;
export const MAX_DEVICES_PER_USER = 50;

const PLATFORMS = new Set(["linux", "windows", "macos", "android", "ios"]);
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "[::1]", "localhost"]);
const ANDROID_SCHEMES = new Set([
  "dev.sfg.orchard.mobile:", "dev.sfg.orchard.mobile.debug:",
  "dev.sfg.orchard.mobile.canary:", "dev.sfg.orchard.mobile.canaryx86:",
]);
const PKCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const STATE_PATTERN = /^[A-Za-z0-9._~-]{16,256}$/;

function redirect(location) {
  return new Response(null, { status: 302, headers: { location, "cache-control": "no-store" } });
}

function isAllowedRedirect(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  // Android returns through its app link; desktop still picks a loopback port.
  const android = ANDROID_SCHEMES.has(url.protocol) && url.host === "account" && url.pathname === "/callback";
  const desktop = url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname) && url.port !== "" &&
    url.pathname === "/callback";
  return (android || desktop) && !url.search && !url.hash && !url.username && !url.password;
}

async function rateLimit(env, request, bucket) {
  if (!env.AUTH_LIMITER) return;
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const { success } = await env.AUTH_LIMITER.limit({ key: `${bucket}:${ip}` });
  if (!success) throw new HttpError(429, "slow_down", "Too many sign-in attempts");
}

function userResponse(row) {
  return { id: row.id, email: row.email, name: row.name, picture: row.picture };
}

function cleanDeviceName(value) {
  const name = String(value || "").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 64);
  return name || "Orchard device";
}

async function issueTokens(env, user, deviceId, refreshSecret) {
  const now = nowSeconds();
  const accessToken = await signJwt({
    iss: issuer(env),
    aud: ACCESS_TOKEN_AUDIENCE,
    sub: user.id,
    did: deviceId,
    iat: now,
    exp: now + ACCESS_TOKEN_SECONDS,
  }, env.SIGNING_KEY);
  return json({
    token_type: "Bearer",
    access_token: accessToken,
    expires_in: ACCESS_TOKEN_SECONDS,
    refresh_token: `${deviceId}.${refreshSecret}`,
    device_id: deviceId,
    user: userResponse(user),
  });
}

async function startGoogleSignIn(request, env) {
  await rateLimit(env, request, "start");
  const params = new URL(request.url).searchParams;
  const redirectUri = params.get("redirect_uri") || "";
  const clientState = params.get("state") || "";
  const codeChallenge = params.get("code_challenge") || "";
  if (!isAllowedRedirect(redirectUri)) throw new HttpError(400, "invalid_request", "redirect_uri must be a supported Orchard callback URL");
  if (!STATE_PATTERN.test(clientState)) throw new HttpError(400, "invalid_request", "state is missing or malformed");
  if (params.get("code_challenge_method") !== "S256" || !PKCE_PATTERN.test(codeChallenge)) {
    throw new HttpError(400, "invalid_request", "S256 PKCE is required");
  }

  const id = randomToken();
  const googleVerifier = randomToken();
  await env.DB.prepare(
    "INSERT INTO auth_requests (id, google_verifier, redirect_uri, client_state, code_challenge, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
  ).bind(id, googleVerifier, redirectUri, clientState, codeChallenge, nowSeconds() + AUTH_REQUEST_SECONDS).run();

  return redirect(googleAuthorizeUrl(env, { state: id, codeChallenge: await pkceChallenge(googleVerifier) }));
}

async function upsertGoogleUser(env, claims) {
  const now = nowSeconds();
  const email = claims.email_verified === false ? "" : String(claims.email || "");
  const name = String(claims.name || "");
  const picture = String(claims.picture || "");
  const identity = await env.DB.prepare("SELECT user_id FROM identities WHERE provider = 'google' AND subject = ?")
    .bind(claims.sub).first();

  if (identity) {
    return env.DB.prepare(
      "UPDATE users SET email = ?, name = ?, picture = ?, updated_at = ? WHERE id = ? RETURNING *",
    ).bind(email, name, picture, now, identity.user_id).first();
  }

  const userId = crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO users (id, email, name, picture, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(userId, email, name, picture, now, now),
    env.DB.prepare("INSERT INTO identities (provider, subject, user_id, email, created_at) VALUES ('google', ?, ?, ?, ?)")
      .bind(claims.sub, userId, email, now),
  ]);
  return { id: userId, email, name, picture };
}

async function finishGoogleSignIn(request, env) {
  const params = new URL(request.url).searchParams;
  // Single use: the row is gone whether or not the rest succeeds.
  const pending = await env.DB.prepare("DELETE FROM auth_requests WHERE id = ? RETURNING *")
    .bind(params.get("state") || "").first();
  if (!pending || pending.expires_at <= nowSeconds()) {
    return htmlPage("Sign-in expired", "Start signing in again from Orchard.", 400);
  }

  const back = new URL(pending.redirect_uri);
  back.searchParams.set("state", pending.client_state);
  const code = params.get("code");
  if (!code) {
    back.searchParams.set("error", params.get("error") === "access_denied" ? "access_denied" : "server_error");
    return redirect(back.toString());
  }

  let claims;
  try {
    claims = await exchangeGoogleCode(env, { code, codeVerifier: pending.google_verifier });
  } catch (error) {
    console.error("Google sign-in failed:", error.message);
    back.searchParams.set("error", "server_error");
    return redirect(back.toString());
  }

  const user = await upsertGoogleUser(env, claims);
  const orchardCode = randomToken();
  await env.DB.prepare(
    "INSERT INTO auth_codes (code_hash, user_id, redirect_uri, code_challenge, expires_at) VALUES (?, ?, ?, ?, ?)",
  ).bind(await hashSecret(orchardCode), user.id, pending.redirect_uri, pending.code_challenge, nowSeconds() + AUTH_CODE_SECONDS).run();

  back.searchParams.set("code", orchardCode);
  return redirect(back.toString());
}

async function redeemAuthorizationCode(env, body) {
  const code = await env.DB.prepare("DELETE FROM auth_codes WHERE code_hash = ? RETURNING *")
    .bind(await hashSecret(String(body.code || ""))).first();
  if (!code || code.expires_at <= nowSeconds() || code.redirect_uri !== body.redirect_uri ||
      (await pkceChallenge(String(body.code_verifier || ""))) !== code.code_challenge) {
    throw new HttpError(400, "invalid_grant", "Authorization code is invalid or expired");
  }

  const user = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(code.user_id).first();
  if (!user) throw new HttpError(400, "invalid_grant", "Account no longer exists");

  const device = body.device && typeof body.device === "object" ? body.device : {};
  const platform = PLATFORMS.has(device.platform) ? device.platform : "unknown";
  const deviceId = crypto.randomUUID();
  const refreshSecret = randomToken();
  const now = nowSeconds();
  // Sign before writing so a signing failure leaves no orphan device.
  const response = await issueTokens(env, user, deviceId, refreshSecret);
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO devices (id, user_id, name, platform, refresh_hash, rotated_at, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(deviceId, user.id, cleanDeviceName(device.name), platform, await hashSecret(refreshSecret), now, now, now),
    // Oldest devices fall off so one account cannot grow the table forever.
    env.DB.prepare(
      "DELETE FROM devices WHERE user_id = ? AND id NOT IN (SELECT id FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC LIMIT ?)",
    ).bind(user.id, user.id, MAX_DEVICES_PER_USER),
  ]);
  return response;
}

function splitRefreshToken(value) {
  const token = String(value || "");
  const dot = token.indexOf(".");
  if (dot <= 0) return null;
  return { deviceId: token.slice(0, dot), secret: token.slice(dot + 1) };
}

async function rotateRefreshToken(env, body) {
  const parts = splitRefreshToken(body.refresh_token);
  const invalid = new HttpError(400, "invalid_grant", "Refresh token is invalid or revoked");
  if (!parts) throw invalid;

  const device = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(parts.deviceId).first();
  if (!device) throw invalid;
  const now = nowSeconds();
  if (device.last_seen_at + REFRESH_IDLE_SECONDS <= now) {
    await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(device.id).run();
    throw invalid;
  }

  const presented = await hashSecret(parts.secret);
  let expected;
  if (presented === device.refresh_hash) {
    expected = ["refresh_hash", device.refresh_hash];
  } else if (presented === device.prev_refresh_hash && now - device.rotated_at < REFRESH_REUSE_GRACE_SECONDS) {
    expected = ["prev_refresh_hash", device.prev_refresh_hash];
  } else {
    if (presented === device.prev_refresh_hash) {
      // Stale token replayed long after rotation: assume theft, kill the device.
      await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(device.id).run();
    }
    throw invalid;
  }

  const refreshSecret = randomToken();
  // Conditional update so two racing refreshes cannot both win.
  const result = await env.DB.prepare(
    `UPDATE devices SET prev_refresh_hash = refresh_hash, refresh_hash = ?, rotated_at = ?, last_seen_at = ? WHERE id = ? AND ${expected[0]} = ?`,
  ).bind(await hashSecret(refreshSecret), now, now, device.id, expected[1]).run();
  if (!result.meta?.changes) throw invalid;

  const user = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(device.user_id).first();
  if (!user) throw invalid;
  return issueTokens(env, user, device.id, refreshSecret);
}

async function token(request, env) {
  await rateLimit(env, request, "token");
  const body = await readJson(request);
  if (body.grant_type === "authorization_code") return redeemAuthorizationCode(env, body);
  if (body.grant_type === "refresh_token") return rotateRefreshToken(env, body);
  throw new HttpError(400, "unsupported_grant_type", "Use authorization_code or refresh_token");
}

async function logout(request, env) {
  const body = await readJson(request);
  const parts = splitRefreshToken(body.refresh_token);
  if (parts) {
    const hash = await hashSecret(parts.secret);
    await env.DB.prepare("DELETE FROM devices WHERE id = ? AND (refresh_hash = ? OR prev_refresh_hash = ?)")
      .bind(parts.deviceId, hash, hash).run();
  }
  return new Response(null, { status: 204 });
}

async function me(request, env) {
  const claims = await requireSession(request, env);
  const user = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(claims.sub).first();
  if (!user) throw new HttpError(401, "invalid_token", "Account no longer exists");
  return json({ ...userResponse(user), github: publicGithub(await githubIdentity(env, user.id)) });
}

async function listDevices(request, env) {
  const claims = await requireSession(request, env);
  const { results } = await env.DB.prepare(
    "SELECT id, name, platform, created_at, last_seen_at FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC",
  ).bind(claims.sub).all();
  return json({ devices: results.map((device) => ({ ...device, current: device.id === claims.did })) });
}

async function removeDevice(request, env, deviceId) {
  const claims = await requireSession(request, env);
  const result = await env.DB.prepare("DELETE FROM devices WHERE id = ? AND user_id = ?").bind(deviceId, claims.sub).run();
  if (!result.meta?.changes) throw new HttpError(404, "not_found", "No such device");
  return new Response(null, { status: 204 });
}

function jwks(env) {
  return json({ keys: [publicJwk(env.SIGNING_KEY)] }, 200, { "cache-control": "public, max-age=3600" });
}

async function route(request, env, ctx) {
  const { pathname } = new URL(request.url);
  const method = request.method;
  if (method === "GET" && pathname === "/auth/google/start") return startGoogleSignIn(request, env);
  if (method === "GET" && pathname === "/auth/google/callback") return finishGoogleSignIn(request, env);
  if (method === "POST" && pathname === "/auth/token") return token(request, env);
  if (method === "POST" && pathname === "/auth/logout") return logout(request, env);
  if (method === "GET" && pathname === "/me") return me(request, env);
  if (method === "GET" && pathname === "/devices") return listDevices(request, env);
  const deviceMatch = pathname.match(/^\/devices\/([0-9a-f-]{36})$/);
  if (method === "DELETE" && deviceMatch) return removeDevice(request, env, deviceMatch[1]);
  if (method === "GET" && pathname === "/.well-known/jwks.json") return jwks(env);
  if (method === "GET" && pathname === "/artwork/index") return listArtwork(request, env);
  if (method === "POST" && pathname === "/artwork") return uploadArtwork(request, env);
  if ((method === "GET" || method === "HEAD") && isArtworkPath(pathname)) return serveArtwork(request, env);
  if (method === "GET" && pathname === "/connect/hub") return openHub(request, env);
  if (method === "POST" && pathname === "/github/link") return startGithubLink(request, env);
  if (method === "GET" && pathname === "/auth/github/callback") return finishGithubLink(request, env);
  if (method === "DELETE" && pathname === "/github") return unlinkGithub(request, env);
  if (method === "POST" && pathname === "/github/webhook") return githubWebhook(request, env, ctx);
  if (method === "GET" && pathname === "/support/reports") return listReports(request, env);
  if (method === "POST" && pathname === "/support/reports") return createReport(request, env);
  const reportMatch = pathname.match(/^\/support\/reports\/([0-9a-f-]{36})(\/read)?$/);
  if (reportMatch && method === "GET" && !reportMatch[2]) return getReport(request, env, reportMatch[1]);
  if (reportMatch && method === "POST" && reportMatch[2]) return markReportRead(request, env, reportMatch[1]);
  if (method === "GET" && isScreenshotPath(pathname)) return serveScreenshot(request, env);
  throw new HttpError(404, "not_found", "No such route");
}

export async function purgeExpired(env, now = nowSeconds()) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM auth_requests WHERE expires_at <= ?").bind(now),
    env.DB.prepare("DELETE FROM auth_codes WHERE expires_at <= ?").bind(now),
    env.DB.prepare("DELETE FROM github_links WHERE expires_at <= ?").bind(now),
    env.DB.prepare("DELETE FROM devices WHERE last_seen_at <= ?").bind(now - REFRESH_IDLE_SECONDS),
  ]);
  await purgeArtwork(env, now);
}

export async function handleRequest(request, env, ctx) {
  try {
    return await route(request, env, ctx);
  } catch (error) {
    if (error instanceof HttpError) {
      return json({ error: error.code, error_description: error.message }, error.status, error.headers);
    }
    console.error(error);
    return json({ error: "server_error", error_description: "Something went wrong" }, 500);
  }
}
