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

// GitHub account linking and the REST helpers support reports share.

import { randomToken } from "./crypto.js";
import { HttpError, htmlPage, json, nowSeconds, requireSession } from "./http.js";

export const GITHUB_API = "https://api.github.com";
const AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
const TOKEN_URL = "https://github.com/login/oauth/access_token";
export const GITHUB_LINK_SECONDS = 10 * 60;
const REQUEST_TIMEOUT_MS = 8000;

export function githubCallbackUrl(env) {
  return new URL("/auth/github/callback", env.PUBLIC_URL).toString();
}

// "owner/repo" only; anything else would let the var aim requests elsewhere.
export function repoPath(repository) {
  const match = /^([A-Za-z0-9-]+)\/([A-Za-z0-9._-]+)$/.exec(String(repository || ""));
  if (!match) throw new Error("GITHUB_REPOSITORY must look like owner/repo");
  return `/repos/${match[1]}/${match[2]}`;
}

export function githubFetch(env, pathOrUrl, { method = "GET", body, etag, token = env.GITHUB_TOKEN } = {}) {
  const headers = {
    accept: "application/vnd.github+json",
    "user-agent": "OrchardAccount/1.0",
    "x-github-api-version": "2022-11-28",
  };
  if (token) headers.authorization = `Bearer ${token}`;
  if (etag) headers["if-none-match"] = etag;
  if (body !== undefined) headers["content-type"] = "application/json";
  const url = pathOrUrl.startsWith(`${GITHUB_API}/`) ? pathOrUrl : `${GITHUB_API}${pathOrUrl}`;
  return fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
}

export async function githubIdentity(env, userId) {
  return env.DB.prepare("SELECT subject, login, picture FROM identities WHERE provider = 'github' AND user_id = ?")
    .bind(userId).first();
}

export function publicGithub(identity) {
  return identity ? { id: identity.subject, login: identity.login, avatar: identity.picture } : null;
}

export async function startGithubLink(request, env) {
  const claims = await requireSession(request, env);
  if (!env.GITHUB_CLIENT_ID || !env.GITHUB_CLIENT_SECRET) {
    throw new HttpError(503, "github_unavailable", "GitHub linking is not set up on this server");
  }
  const state = randomToken();
  const now = nowSeconds();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM github_links WHERE user_id = ? OR expires_at <= ?").bind(claims.sub, now),
    env.DB.prepare("INSERT INTO github_links (state, user_id, expires_at) VALUES (?, ?, ?)")
      .bind(state, claims.sub, now + GITHUB_LINK_SECONDS),
  ]);
  const url = new URL(AUTHORIZE_URL);
  // No scopes: Orchard only needs to know who you are on GitHub.
  url.search = new URLSearchParams({
    client_id: env.GITHUB_CLIENT_ID,
    redirect_uri: githubCallbackUrl(env),
    state,
    allow_signup: "true",
  }).toString();
  return json({ url: url.toString(), expires_in: GITHUB_LINK_SECONDS });
}

async function exchangeGithubCode(env, code) {
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { accept: "application/json", "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.GITHUB_CLIENT_ID,
      client_secret: env.GITHUB_CLIENT_SECRET,
      code,
      redirect_uri: githubCallbackUrl(env),
    }),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || !body.access_token) throw new Error(`GitHub token exchange failed: ${body.error || response.status}`);

  const profileResponse = await githubFetch(env, "/user", { token: body.access_token });
  if (!profileResponse.ok) throw new Error(`GitHub /user returned ${profileResponse.status}`);
  const profile = await profileResponse.json();
  // The token proved identity; keeping it would only be a liability.
  await fetch(`${GITHUB_API}/applications/${env.GITHUB_CLIENT_ID}/token`, {
    method: "DELETE",
    headers: {
      accept: "application/vnd.github+json",
      authorization: `Basic ${btoa(`${env.GITHUB_CLIENT_ID}:${env.GITHUB_CLIENT_SECRET}`)}`,
      "content-type": "application/json",
      "user-agent": "OrchardAccount/1.0",
    },
    body: JSON.stringify({ access_token: body.access_token }),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  }).catch(() => {});
  if (!profile.id || !profile.login) throw new Error("GitHub returned no profile");
  return { id: String(profile.id), login: String(profile.login), avatar: String(profile.avatar_url || "") };
}

export async function finishGithubLink(request, env) {
  const params = new URL(request.url).searchParams;
  const pending = await env.DB.prepare("DELETE FROM github_links WHERE state = ? RETURNING *")
    .bind(params.get("state") || "").first();
  if (!pending || pending.expires_at <= nowSeconds()) {
    return htmlPage("Link expired", "Start linking GitHub again from Orchard.", 400);
  }
  const code = params.get("code");
  if (!code) return htmlPage("GitHub not linked", "You can close this tab.", 400);

  let profile;
  try {
    profile = await exchangeGithubCode(env, code);
  } catch (error) {
    console.error("GitHub link failed:", error.message);
    return htmlPage("GitHub not linked", "GitHub did not finish signing in. Try again from Orchard.", 502);
  }

  const owner = await env.DB.prepare("SELECT user_id FROM identities WHERE provider = 'github' AND subject = ?")
    .bind(profile.id).first();
  if (owner && owner.user_id !== pending.user_id) {
    return htmlPage("Already linked", "This GitHub account is linked to a different Orchard account.", 409);
  }
  const now = nowSeconds();
  await env.DB.batch([
    // One GitHub account per Orchard account; relinking replaces the old one.
    env.DB.prepare("DELETE FROM identities WHERE provider = 'github' AND user_id = ? AND subject != ?")
      .bind(pending.user_id, profile.id),
    env.DB.prepare(
      `INSERT INTO identities (provider, subject, user_id, email, login, picture, created_at)
       VALUES ('github', ?, ?, '', ?, ?, ?)
       ON CONFLICT (provider, subject) DO UPDATE SET login = excluded.login, picture = excluded.picture`,
    ).bind(profile.id, pending.user_id, profile.login, profile.avatar, now),
  ]);
  return htmlPage("GitHub linked", `Linked as @${profile.login}. You can close this tab and go back to Orchard.`, 200);
}

export async function unlinkGithub(request, env) {
  const claims = await requireSession(request, env);
  await env.DB.prepare("DELETE FROM identities WHERE provider = 'github' AND user_id = ?").bind(claims.sub).run();
  return new Response(null, { status: 204 });
}
