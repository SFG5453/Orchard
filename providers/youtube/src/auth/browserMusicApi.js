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

// Encapsulates authenticated browser-style YouTube Music requests used by main-process services.
import { sha1Hex } from './sha1.js';
import { parseCookieString } from './youtubeAuthCookies.js';

export function browserAuthHeader(cookie = '', origin, epochSeconds = Math.floor(Date.now() / 1000)) {
  const cookies = parseCookieString(cookie);
  const sapisid = cookies.SAPISID || cookies['__Secure-3PAPISID'] || cookies.APISID;
  const signedCookies = [
    sapisid && ['SAPISIDHASH', sapisid],
    cookies['__Secure-1PAPISID'] && ['SAPISID1PHASH', cookies['__Secure-1PAPISID']],
    cookies['__Secure-3PAPISID'] && ['SAPISID3PHASH', cookies['__Secure-3PAPISID']]
  ].filter(Boolean);

  return signedCookies.map(([scheme, value]) => {
    const hash = sha1Hex(`${epochSeconds} ${value} ${origin}`);
    return `${scheme} ${epochSeconds}_${hash}`;
  }).join(' ');
}

export function cookieWithPlaybackDefaults(cookie = '') {
  if (!cookie) return '';

  const parts = cookie.split(';').map((part) => part.trim()).filter(Boolean);
  const names = new Set(parts.map((part) => part.split('=', 1)[0]));
  if (!names.has('SOCS')) parts.push('SOCS=CAI');
  if (!names.has('PREF')) parts.push('PREF=f2=8000000&hl=en');
  return parts.join('; ');
}

export function createBrowserMusicFetch({
  authState,
  fetchImpl = globalThis.fetch,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin
}) {
  return async function browserMusicFetch(input, init = {}) {
    const requestUrl = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
    if (!/\/youtubei\/v1\//.test(requestUrl.pathname)) {
      return fetchImpl(input, init);
    }

    const cookie = cookieWithPlaybackDefaults(authState.browser.cookie || '');
    const authorization = browserAuthHeader(cookie, youtubeMusicOrigin);
    if (!authorization) return fetchImpl(input, init);

    const musicUrl = new URL(`${requestUrl.pathname}${requestUrl.search}`, youtubeMusicOrigin);
    const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
    headers.set('Authorization', authorization);
    headers.set('Cookie', cookie);
    headers.set('Origin', youtubeMusicOrigin);
    headers.set('Referer', `${youtubeMusicOrigin}/`);
    headers.set('User-Agent', youtubeMusicClientUserAgent);
    headers.set('X-Origin', youtubeMusicOrigin);
    headers.set('X-YouTube-Client-Name', '67');
    headers.set('X-YouTube-Client-Version', youtubeMusicClientVersion);
    headers.set('X-Goog-AuthUser', String(authState.browser.accountIndex || 0));
    headers.set('X-Youtube-Bootstrap-Logged-In', 'true');
    if (authState.browser.dataSyncId) headers.set('X-Goog-PageId', authState.browser.dataSyncId);
    else headers.delete('X-Goog-PageId');

    let body = init.body;
    if (typeof body === 'string') {
      try {
        const payload = JSON.parse(body);
        payload.context ||= {};
        payload.context.client ||= {};
        payload.context.client.clientName = 'WEB_REMIX';
        payload.context.client.clientVersion = youtubeMusicClientVersion;
        if (authState.browser.visitorData) payload.context.client.visitorData = authState.browser.visitorData;
        if (authState.browser.dataSyncId) {
          payload.context.user ||= {};
          payload.context.user.onBehalfOfUser = authState.browser.dataSyncId;
        }
        body = JSON.stringify(payload);
      } catch {
        // Keep the original request body if YouTube.js changes its serialization.
      }
    }

    const method = init.method || (input instanceof Request ? input.method : undefined);
    return fetchImpl(musicUrl, { ...init, method, headers, body });
  };
}

export function createBrowserMusicApi({
  authState,
  fetchImpl = globalThis.fetch,
  musicBrowseRequest,
  musicBrowseRequests,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin
}) {
  const browserCookie = (cookie = '') => cookieWithPlaybackDefaults(cookie || authState.browser.cookie || '');

  function browserMusicContext() {
    return {
      context: {
        client: {
          clientName: 'WEB_REMIX',
          clientVersion: youtubeMusicClientVersion,
          hl: 'en',
          gl: 'US',
          visitorData: authState.browser.visitorData || undefined
        },
        user: authState.browser.dataSyncId ? {
          onBehalfOfUser: authState.browser.dataSyncId
        } : undefined
      }
    };
  }

  async function rawBrowserMusicBrowse(request) {
    const authorization = browserAuthHeader(authState.browser.cookie, youtubeMusicOrigin);
    if (!authorization || !authState.browser.cookie) {
      throw new Error('Browser YouTube Music login is unavailable.');
    }

    const response = await fetchImpl(`${youtubeMusicOrigin}/youtubei/v1/browse?prettyPrint=false`, {
      method: 'POST',
      headers: {
        'Authorization': authorization,
        'Content-Type': 'application/json',
        'Cookie': browserCookie(),
        'X-YouTube-Client-Name': '67',
        'X-YouTube-Client-Version': youtubeMusicClientVersion,
        'X-Origin': youtubeMusicOrigin,
        'Origin': youtubeMusicOrigin,
        'Referer': `${youtubeMusicOrigin}/`,
        'User-Agent': youtubeMusicClientUserAgent,
        'Accept': 'application/json',
        'X-Goog-AuthUser': String(authState.browser.accountIndex || 0),
        ...(authState.browser.dataSyncId ? { 'X-Goog-PageId': authState.browser.dataSyncId } : {})
      },
      body: JSON.stringify({
        ...browserMusicContext(),
        ...request
      })
    });
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = {};
    }

    if (!response.ok) {
      const message = data.error?.message || `Browser-cookie YouTube Music browse failed with HTTP ${response.status}`;
      const error = new Error(message);
      error.status = response.status;
      error.info = text;
      throw error;
    }

    return data;
  }

  async function sendBrowserHistoryStat(sourceUrl, params = {}) {
    const authorization = browserAuthHeader(authState.browser.cookie, youtubeMusicOrigin);
    if (!authorization || !authState.browser.cookie) {
      throw new Error('Browser YouTube Music login is unavailable.');
    }

    // QuickJS has no URL class, so merge the query by hand.
    const [base, query = ''] = String(sourceUrl || '').replace('https://s.', 'https://music.').split('?', 2);
    // Mirrors the web player's pings; the endpoint returns 204 even for ones it discards.
    const overrides = {
      ver: '2',
      c: 'WEB_REMIX',
      cver: youtubeMusicClientVersion,
      cbr: 'Chrome',
      cbrver: /Chrome\/([\d.]+)/.exec(youtubeMusicClientUserAgent || '')?.[1] || '141.0.0.0',
      cplayer: 'UNIPLAYER',
      cos: 'Windows',
      cosver: '10.0',
      cplatform: 'DESKTOP',
      hl: 'en_US',
      cr: 'US',
      ...params
    };
    const kept = query.split('&').filter((part) => part && !(decodeURIComponent(part.split('=', 1)[0]) in overrides));
    const added = Object.entries(overrides)
      .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
    const url = `${base}?${[...kept, ...added].join('&')}`;

    const docid = /(?:^|&)docid=([^&]+)/.exec(query)?.[1];
    // The web player sends these as empty-body POST beacons.
    const response = await fetchImpl(url, {
      method: 'POST',
      headers: {
        Authorization: authorization,
        Cookie: browserCookie(),
        'Content-Type': 'text/plain;charset=UTF-8',
        Origin: youtubeMusicOrigin,
        Referer: docid ? `${youtubeMusicOrigin}/watch?v=${docid}` : `${youtubeMusicOrigin}/`,
        'User-Agent': youtubeMusicClientUserAgent,
        'X-Origin': youtubeMusicOrigin,
        'X-Goog-AuthUser': String(authState.browser.accountIndex || 0),
        ...(authState.browser.dataSyncId ? { 'X-Goog-PageId': authState.browser.dataSyncId } : {})
      }
    });

    if (!response.ok) {
      const error = new Error(`Browser YouTube Music history request failed with HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
  }

  async function resolveMusicCollectionWithBrowserAuth(kind, payload = {}) {
    const errors = [];

    for (const request of musicBrowseRequests(kind, payload)) {
      try {
        return {
          browseId: request.browseId,
          data: await rawBrowserMusicBrowse(request),
          browse: (browsePayload) => rawBrowserMusicBrowse(musicBrowseRequest('artist', browsePayload)),
          search: () => null,
          continue: (continuation) => rawBrowserMusicBrowse({
            continuation
          })
        };
      } catch (error) {
        errors.push(error);
      }
    }

    throw errors[errors.length - 1] || new Error('Browser-cookie browse failed');
  }

  return {
    cookieWithPlaybackDefaults: browserCookie,
    rawBrowserMusicBrowse,
    resolveMusicCollectionWithBrowserAuth,
    sendBrowserHistoryStat
  };
}
