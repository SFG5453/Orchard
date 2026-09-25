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

import { createHash } from 'node:crypto';
import {
  QOBUZ_BASE_URL,
  QOBUZ_FORMAT_IDS,
  QOBUZ_USER_AGENT,
  normalizeQobuzQuality
} from './types.js';
import {
  normalizeQobuzAlbumQuality,
  normalizeQobuzTrackQuality,
  qobuzTrackItems
} from './quality.js';

const QOBUZ_TRACK_BATCH_SIZE = 50;

function signedRequest(method, args, timestamp, secret) {
  const serialized = Object.entries(args)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}${value}`)
    .join('');
  return createHash('md5').update(`${method}${serialized}${timestamp}${secret}`).digest('hex');
}

export function qobuzRequestSignature(method, args, timestamp, secret) {
  return signedRequest(method, args, String(timestamp), secret);
}

export function createQobuzClient({
  bootstrap,
  credentials,
  fetchImpl = fetch,
  softwareVersion = '@orchardmusic/qobuz'
} = {}) {
  let session = null;
  let pendingSession = null;

  async function authHeaders({ withSession = false } = {}) {
    const [web, auth] = await Promise.all([bootstrap.get(), credentials()]);
    if (!auth?.token) throw new Error('Qobuz is not connected');
    return {
      web,
      auth,
      headers: {
        'Accept': 'application/json',
        'User-Agent': QOBUZ_USER_AGENT,
        'X-App-Id': web.appId,
        'X-User-Auth-Token': auth.token,
        ...(withSession && session?.sessionId ? { 'X-Session-Id': session.sessionId } : {})
      }
    };
  }

  async function responseJson(response, label) {
    if (response.ok) return response.json();
    const body = await response.text().catch(() => '');
    const error = new Error(`${label} failed (${response.status})${body ? `: ${body.slice(0, 240)}` : ''}`);
    error.status = response.status;
    throw error;
  }

  async function apiGet(path, params = {}, options = {}) {
    const { headers } = await authHeaders(options);
    const query = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)]));
    return responseJson(await fetchImpl(`${QOBUZ_BASE_URL}/${path}?${query}`, { headers }), `Qobuz ${path}`);
  }

  async function apiPost(path, body, { json = false } = {}) {
    const { headers } = await authHeaders();
    const payload = json ? JSON.stringify(body) : new URLSearchParams(body).toString();
    return responseJson(await fetchImpl(`${QOBUZ_BASE_URL}/${path}`, {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': json ? 'application/json' : 'application/x-www-form-urlencoded'
      },
      body: payload
    }), `Qobuz ${path}`);
  }

  async function renewSession() {
    const { web, headers } = await authHeaders();
    const timestamp = String(Math.floor(Date.now() / 1000));
    const args = { profile: 'qbz-1' };
    const body = {
      ...args,
      request_ts: timestamp,
      request_sig: signedRequest('sessionstart', args, timestamp, web.rngInit)
    };
    const value = await responseJson(await fetchImpl(`${QOBUZ_BASE_URL}/session/start`, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(body).toString()
    }), 'Qobuz session/start');
    if (!value?.session_id || !value?.infos) throw new Error('Qobuz returned an incomplete playback session');
    session = {
      sessionId: String(value.session_id),
      infos: String(value.infos),
      expiresAt: Number(value.expires_at || 0) * 1000
    };
    return session;
  }

  async function ensureSession({ refresh = false } = {}) {
    if (!refresh && session?.expiresAt > Date.now() + 60_000) return session;
    if (!pendingSession) {
      pendingSession = renewSession().finally(() => {
        pendingSession = null;
      });
    }
    return pendingSession;
  }

  async function search(query) {
    return apiGet('catalog/search', { query, limit: 20 });
  }

  async function albumQuality(albumId) {
    const id = String(albumId);
    const value = await apiGet('album/get', { album_id: id });
    return normalizeQobuzAlbumQuality(value, id);
  }

  async function trackQuality(trackId) {
    const id = Number(trackId);
    const value = await apiGet('track/get', { track_id: id });
    return normalizeQobuzTrackQuality(value, id);
  }

  async function trackQualities(trackIds) {
    if (!Array.isArray(trackIds)) throw new TypeError('Qobuz trackQualities requires an array of track IDs');
    const ids = trackIds.map((trackId) => Number(trackId));
    if (!ids.length) return [];

    const values = [];
    for (let offset = 0; offset < ids.length; offset += QOBUZ_TRACK_BATCH_SIZE) {
      const batch = ids.slice(offset, offset + QOBUZ_TRACK_BATCH_SIZE);
      const value = await apiPost('track/getList', { tracks_id: batch }, { json: true });
      values.push(...qobuzTrackItems(value));
    }

    const byId = new Map();
    for (const value of values) {
      const id = Number(value?.id);
      if (Number.isSafeInteger(id) && !byId.has(id)) byId.set(id, value);
    }
    return ids.map((id) => normalizeQobuzTrackQuality(byId.get(id) || {}, id));
  }

  async function streamingInfo(trackId, quality, { retry = true } = {}) {
    await ensureSession();
    const { web, headers } = await authHeaders({ withSession: true });
    const timestamp = String(Math.floor(Date.now() / 1000));
    const args = {
      format_id: QOBUZ_FORMAT_IDS[normalizeQobuzQuality(quality)],
      intent: 'stream',
      track_id: Number(trackId)
    };
    const params = {
      ...args,
      request_ts: timestamp,
      request_sig: signedRequest('fileurl', args, timestamp, web.rngInit)
    };
    const query = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)]));
    const response = await fetchImpl(`${QOBUZ_BASE_URL}/file/url?${query}`, { headers });
    if (!response.ok && retry && [401, 403, 410].includes(response.status)) {
      session = null;
      await ensureSession({ refresh: true });
      return streamingInfo(trackId, quality, { retry: false });
    }
    const value = await responseJson(response, 'Qobuz file/url');
    if (!value?.url_template) throw new Error('Qobuz returned no segmented playback URL');
    return {
      ...value,
      session: { ...session },
      rngInit: web.rngInit
    };
  }

  async function reportStreamingStart(source) {
    const { auth } = await authHeaders();
    return apiPost('track/reportStreamingStart', {
      events: JSON.stringify([{
        track_id: source.trackId,
        date: source.startedAtUnix,
        user_id: auth.userId,
        format_id: source.formatId
      }])
    });
  }

  async function reportStreamingEnd(event) {
    return apiPost('track/reportStreamingEndJson', {
      events: [event],
      renderer_context: { software_version: softwareVersion }
    }, { json: true });
  }

  function reset() {
    session = null;
    pendingSession = null;
  }

  return {
    ensureSession,
    reportStreamingEnd,
    reportStreamingStart,
    reset,
    search,
    streamingInfo,
    albumQuality,
    trackQuality,
    trackQualities,
    getAlbumQuality: albumQuality,
    getTrackQuality: trackQuality,
    getTrackQualities: trackQualities
  };
}
