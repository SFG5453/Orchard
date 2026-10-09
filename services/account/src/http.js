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

import { verifyJwt } from "./jwt.js";

export const nowSeconds = () => Math.floor(Date.now() / 1000);

export class HttpError extends Error {
  constructor(status, code, description) {
    super(description || code);
    this.status = status;
    this.code = code;
  }
}

export function htmlPage(title, message, status) {
  const escape = (text) => text.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const body = `<!doctype html><meta charset="utf-8"><title>${escape(title)}</title>` +
    `<body style="font-family:system-ui;background:#1c211e;color:#f0eee7;display:grid;place-items:center;height:100vh;margin:0">` +
    `<main style="text-align:center"><h1>${escape(title)}</h1><p>${escape(message)}</p></main></body>`;
  return new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

export function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", ...headers },
  });
}

export async function readJson(request) {
  try {
    const body = await request.json();
    if (body && typeof body === "object") return body;
  } catch {
    // Fall through to the shared error.
  }
  throw new HttpError(400, "invalid_request", "Expected a JSON object");
}

export function issuer(env) {
  return new URL(env.PUBLIC_URL).origin;
}

// Also requires the device row, so revocation is immediate here.
export async function requireSession(request, env) {
  const header = request.headers.get("authorization") || "";
  const claims = header.startsWith("Bearer ")
    ? await verifyJwt(header.slice(7), env.SIGNING_KEY, { issuer: issuer(env) })
    : null;
  if (!claims) throw new HttpError(401, "invalid_token", "Sign in to Orchard again");
  const device = await env.DB.prepare("SELECT id FROM devices WHERE id = ? AND user_id = ?").bind(claims.did, claims.sub).first();
  if (!device) throw new HttpError(401, "invalid_token", "This device was signed out");
  return claims;
}
