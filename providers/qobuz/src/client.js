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

import { md5Hex } from './md5.js';
import { formEncode } from './query.js';
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
  return md5Hex(`${method}${serialized}${timestamp}${secret}`);
}

export function qobuzRequestSignature(method, args, timestamp, secret) {
  return signedRequest(method, args, String(timestamp), secret);
}

export function createQobuzClient({
  credentials,
  fetchImpl = fetch,
  softwareVersion = '@orchardmusic/qobuz'
} = {}) {
  async function authHeaders() {
    const auth = await credentials();
    if (!auth?.token) throw new Error('Qobuz is not connected');
    if (!auth.appId || !auth.appSecret) throw new Error('Qobuz needs your own app ID and app secret');
    return {
      auth,
      headers: {
        'Accept': 'application/json',
        'User-Agent': QOBUZ_USER_AGENT,
        'X-App-Id': String(auth.appId),
        'X-User-Auth-Token': auth.token
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

  async function apiGet(path, params = {}) {
    const { headers } = await authHeaders();
    const query = formEncode(params);
    return responseJson(await fetchImpl(`${QOBUZ_BASE_URL}/${path}?${query}`, { headers }), `Qobuz ${path}`);
  }

  async function apiPost(path, body, { json = false } = {}) {
    const { headers } = await authHeaders();
    const payload = json ? JSON.stringify(body) : formEncode(body);
    return responseJson(await fetchImpl(`${QOBUZ_BASE_URL}/${path}`, {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': json ? 'application/json' : 'application/x-www-form-urlencoded'
      },
      body: payload
    }), `Qobuz ${path}`);
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

  // Signed with the user's app secret; returns one plain FLAC URL.
  async function streamingInfo(trackId, quality) {
    const { auth, headers } = await authHeaders();
    const timestamp = String(Math.floor(Date.now() / 1000));
    const args = {
      format_id: QOBUZ_FORMAT_IDS[normalizeQobuzQuality(quality)],
      intent: 'stream',
      track_id: Number(trackId)
    };
    const query = formEncode({
      ...args,
      request_ts: timestamp,
      request_sig: signedRequest('trackgetFileUrl', args, timestamp, auth.appSecret)
    });
    const value = await responseJson(
      await fetchImpl(`${QOBUZ_BASE_URL}/track/getFileUrl?${query}`, { headers }),
      'Qobuz track/getFileUrl'
    );
    if (!value?.url) throw new Error('Qobuz returned no file URL');
    if (value.sample) throw new Error('Qobuz returned a preview clip; the account cannot stream this track');
    return value;
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

  function reset() {}

  return {
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
