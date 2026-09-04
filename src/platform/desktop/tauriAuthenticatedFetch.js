/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

const YOUTUBE_MUSIC_ORIGIN = 'https://music.youtube.com';
const YOUTUBE_MUSIC_CLIENT_VERSION = '1.20260213.01.00';
const YOUTUBE_MUSIC_USER_AGENT =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';

function parseCookies(cookie = '') {
  return String(cookie)
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean)
    .reduce((result, part) => {
      const separator = part.indexOf('=');
      if (separator > 0) result[part.slice(0, separator)] = part.slice(separator + 1);
      return result;
    }, {});
}

export function normalizedTauriYouTubeCookie(cookie = '') {
  const parts = String(cookie).split(';').map((part) => part.trim()).filter(Boolean);
  const cookies = parseCookies(cookie);
  if (!cookies.SAPISID && cookies['__Secure-3PAPISID']) {
    parts.push(`SAPISID=${cookies['__Secure-3PAPISID']}`);
  }
  if (!cookies.SOCS) parts.push('SOCS=CAI');
  if (!cookies.PREF) parts.push('PREF=f2=8000000&hl=en');
  return parts.join('; ');
}

async function sha1(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await globalThis.crypto.subtle.digest('SHA-1', bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

export async function tauriYouTubeAuthorization(
  cookie,
  epochSeconds = Math.floor(Date.now() / 1000),
  origin = YOUTUBE_MUSIC_ORIGIN
) {
  const cookies = parseCookies(cookie);
  const signingCookies = [
    [cookies.SAPISID || cookies['__Secure-3PAPISID'] || cookies.APISID, 'SAPISIDHASH'],
    [cookies['__Secure-1PAPISID'], 'SAPISID1PHASH'],
    [cookies['__Secure-3PAPISID'], 'SAPISID3PHASH']
  ].filter(([value]) => value);

  return Promise.all(signingCookies.map(async ([value, scheme]) => {
    const digest = await sha1(`${epochSeconds} ${value} ${origin}`);
    return `${scheme} ${epochSeconds}_${digest}`;
  })).then((values) => values.join(' '));
}

export function createTauriYouTubeFetch({
  fetchImpl,
  getSession,
  origin = YOUTUBE_MUSIC_ORIGIN,
  clientName = 'WEB_REMIX',
  clientHeaderName = '67',
  clientVersion = YOUTUBE_MUSIC_CLIENT_VERSION
}) {
  return async function authenticatedTauriFetch(input, init = {}) {
    const requestUrl = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
    if (!/\/youtubei\/v1\//.test(requestUrl.pathname)) return fetchImpl(input, init);

    const session = getSession();
    const cookie = normalizedTauriYouTubeCookie(session.cookie || '');
    const authorization = await tauriYouTubeAuthorization(cookie, undefined, origin);
    if (!authorization) return fetchImpl(input, init);

    const authenticatedUrl = new URL(`${requestUrl.pathname}${requestUrl.search}`, origin);
    const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
    headers.set('Authorization', authorization);
    headers.set('Cookie', cookie);
    headers.set('Origin', origin);
    headers.set('Referer', `${origin}/`);
    headers.set('User-Agent', YOUTUBE_MUSIC_USER_AGENT);
    headers.set('X-Origin', origin);
    if (clientHeaderName) headers.set('X-YouTube-Client-Name', clientHeaderName);
    if (clientVersion) headers.set('X-YouTube-Client-Version', clientVersion);
    headers.set('X-Goog-AuthUser', String(session.accountIndex || 0));
    headers.set('X-Youtube-Bootstrap-Logged-In', 'true');
    if (session.dataSyncId) headers.set('X-Goog-PageId', session.dataSyncId);
    else headers.delete('X-Goog-PageId');

    let body = init.body;
    if (typeof body === 'string') {
      try {
        const payload = JSON.parse(body);
        payload.context ||= {};
        payload.context.client ||= {};
        if (clientName) payload.context.client.clientName = clientName;
        if (clientVersion) payload.context.client.clientVersion = clientVersion;
        if (session.visitorData) payload.context.client.visitorData = session.visitorData;
        if (session.dataSyncId) {
          payload.context.user ||= {};
          payload.context.user.onBehalfOfUser = session.dataSyncId;
        }
        body = JSON.stringify(payload);
      } catch {
        // Preserve non-JSON YouTube requests unchanged.
      }
    }

    const method = init.method || (input instanceof Request ? input.method : undefined);
    return fetchImpl(authenticatedUrl, { ...init, method, headers, body });
  };
}
