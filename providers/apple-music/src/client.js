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

import { WEB_ORIGIN, createWebToken } from './webToken.js';

const API_ORIGIN = 'https://api.music.apple.com';
// music.apple.com's own API; with its web token it returns editorialVideo and editorialArtwork.
const EDITORIAL_ORIGIN = 'https://amp-api.music.apple.com';
const MAX_PAGES = 8;

export class AppleMusicError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'AppleMusicError';
    this.status = status;
    this.code = code;
  }
}

const statusCodes = new Map([
  [401, ['developer_token', 'Apple Music rejected Orchard\'s developer token.']],
  [403, ['user_token', 'Sign in to Apple Music again.']],
  [404, ['not_found', 'Apple Music could not find that.']],
  [429, ['rate_limited', 'Apple Music is busy. Try again in a minute.']]
]);

function encodeQuery(query) {
  return Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
}

/** Joins a path that may already carry a query (Apple's `next` links do). */
export function apiUrl(path, query = {}, origin = API_ORIGIN) {
  const base = path.startsWith('http') ? path : `${origin}${path.startsWith('/') ? '' : '/'}${path}`;
  const extra = encodeQuery(query);
  if (!extra) return base;
  return `${base}${base.includes('?') ? '&' : '?'}${extra}`;
}

/**
 * GET requests against the public Apple Music API. [session] returns
 * {developerToken, userToken, storefront, origin}; the user token is optional.
 */
export function createAppleMusicClient({ fetchImpl, session }) {
  const webToken = createWebToken(fetchImpl);

  function headers(personal) {
    const current = session() || {};
    if (!current.developerToken) throw new AppleMusicError(0, 'not_connected', 'Apple Music is not connected.');
    const result = { Authorization: `Bearer ${current.developerToken}`, Accept: 'application/json' };
    if (current.userToken) result['Music-User-Token'] = current.userToken;
    else if (personal) throw new AppleMusicError(0, 'not_signed_in', 'Sign in to Apple Music first.');
    // Tokens carry an origin claim; native hosts present the page origin it names.
    if (current.origin) result.Origin = current.origin;
    return result;
  }

  async function get(path, query = {}) {
    const personal = /^\/?v1\/me\//.test(path);
    const response = await fetchImpl(apiUrl(path, query), { headers: headers(personal) });
    if (!response.ok) {
      const [code, message] = statusCodes.get(response.status) || ['http', `Apple Music returned ${response.status}.`];
      throw new AppleMusicError(response.status, code, message);
    }
    return response.json();
  }

  /** POST, PUT or DELETE on the listener's library; null when Apple answers without a body. */
  async function send(method, path, body) {
    const init = { method, headers: headers(true) };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const response = await fetchImpl(apiUrl(path), init);
    if (!response.ok) {
      const [code, message] = statusCodes.get(response.status) || ['http', `Apple Music returned ${response.status}.`];
      throw new AppleMusicError(response.status, code, message);
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }

  /**
   * [path] from music.apple.com's API with its web token, for listeners signed in to Apple
   * Music only. Null when unavailable so callers fall back to [get]. [personal] reads such
   * as lyrics also send the listener's token, which music.apple.com calls Media-User-Token.
   */
  async function editorial(path, query = {}, { personal = false } = {}) {
    const userToken = (session() || {}).userToken;
    if (!userToken) return null;
    const token = await webToken.get();
    if (!token) return null;
    const headers = { Authorization: `Bearer ${token}`, Accept: 'application/json', Origin: WEB_ORIGIN, Referer: `${WEB_ORIGIN}/` };
    if (personal) headers['Media-User-Token'] = userToken;
    let response;
    try {
      response = await fetchImpl(apiUrl(path, query, EDITORIAL_ORIGIN), { headers });
    } catch {
      return null;
    }
    if (response.ok) return response.json();
    if (response.status === 404) throw new AppleMusicError(404, 'not_found', statusCodes.get(404)[1]);
    // Apple rotates the web token; refetch it on the next request. A personal read may
    // instead be refusing the listener's token, which a new web token would not fix.
    if (!personal && (response.status === 401 || response.status === 403)) webToken.reset();
    return null;
  }

  /** Every resource behind a paginated list, following `next` up to [maxPages]. */
  async function all(path, query = {}, maxPages = MAX_PAGES) {
    const items = [];
    const carried = Object.entries(query)
      .filter(([key]) => key === 'limit' || key === 'include' || key.startsWith('include['));
    let page = await get(path, query);
    for (let count = 1; ; count++) {
      items.push(...(page.data || []));
      if (!page.next || count >= maxPages) return { items, next: page.next || '' };
      // Apple's next links keep the offset but may drop the page size and includes.
      const next = page.next;
      page = await get(next, Object.fromEntries(carried.filter(([key]) => !next.includes(`${encodeURIComponent(key)}=`))));
    }
  }

  /** Headers for play.itunes.apple.com, which wants the listener and a page Referer. */
  function playbackHeaders() {
    const result = headers(true);
    if (result.Origin) result.Referer = `${result.Origin}/`;
    return result;
  }

  function storefront() {
    return (session() || {}).storefront || 'us';
  }

  return { get, send, editorial, all, storefront, playbackHeaders };
}
