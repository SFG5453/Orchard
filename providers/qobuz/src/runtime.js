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

// QuickJS entry point. The host owns credentials, the OAuth callback and the
// loopback audio server; this bundle owns the Qobuz protocol.
import { albumCandidates, selectQobuzAlbum } from './album.js';
import { createQobuz } from './index.js';
import { createQobuzAuthorizationUrl, exchangeQobuzAuthorizationCode } from './oauth.js';

const hostCrypto = Object.freeze({
  hkdfSha256: ({ key, salt, info, length }) => globalThis.__orchardHkdfSha256(key, salt, info, length),
  decryptAes128Cbc: ({ key, iv, data }) => globalThis.__orchardAes128(0, key, iv, data),
  decryptAes128Ctr: ({ key, iv, data }) => globalThis.__orchardAes128(1, key, iv, data)
});

// Identifies a playback to Qobuz's reports; nothing secret depends on it.
function randomUuid() {
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16));
  hex[12] = '4';
  hex[16] = (8 + Math.floor(Math.random() * 4)).toString(16);
  const text = hex.join('');
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`;
}

const warnings = [];
const logger = Object.freeze({
  warn(message) {
    warnings.push(String(message));
    if (warnings.length > 20) warnings.shift();
  }
});

let credentials = null;
const qobuz = createQobuz({
  credentials: () => credentials,
  crypto: hostCrypto,
  fetchImpl: (...args) => fetch(...args),
  logger,
  randomUuid,
  softwareVersion: 'Orchard/3'
});

const methods = new Map([
  ['session.set', async (payload = {}) => {
    const token = String(payload.token || '');
    const userId = Number(payload.userId);
    credentials = token && Number.isSafeInteger(userId) ? { token, userId } : null;
    qobuz.client.reset();
    return { connected: Boolean(credentials) };
  }],
  ['oauth.start', async (payload = {}) => {
    const web = await qobuz.bootstrap.get({ refresh: true });
    return { url: createQobuzAuthorizationUrl({ appId: web.appId, redirectUrl: payload.redirectUrl }) };
  }],
  ['oauth.finish', async (payload = {}) => exchangeQobuzAuthorizationCode({
    code: payload.code,
    fetchImpl: (...args) => fetch(...args),
    web: await qobuz.bootstrap.get()
  })],
  ['playback.resolve', async (payload = {}) => {
    const resolved = await qobuz.resolveTrack(payload.track || {}, payload.quality);
    return resolved ? { match: resolved.match, source: resolved.source } : { miss: qobuz.lastMiss() };
  }],
  // Raw bytes: the host passes a top-level Uint8Array through without JSON.
  ['playback.read', async (payload = {}) =>
    (await qobuz.readRange(String(payload.playbackId || ''), { start: payload.start, end: payload.end })).bytes],
  ['playback.started', async (payload = {}) => {
    await qobuz.playbackStarted(String(payload.playbackId || ''), Number(payload.position || 0));
    return null;
  }],
  ['playback.ended', async (payload = {}) => {
    const playbackId = String(payload.playbackId || '');
    await qobuz.playbackEnded(playbackId, Number(payload.position || 0));
    qobuz.release(playbackId);
    return null;
  }],
  ['album.quality', async (payload = {}) => {
    const query = `${payload.title || ''} ${payload.artist || ''}`.trim();
    if (!query) return null;
    return selectQobuzAlbum(payload, albumCandidates(await qobuz.search(query)), payload.quality);
  }],
  ['runtime.warnings', async () => warnings.splice(0)],
  ['close', async () => {
    await qobuz.close();
    credentials = null;
    return null;
  }]
]);

async function invoke(method, payload) {
  const handler = methods.get(String(method || ''));
  if (!handler) throw new Error(`Unknown Qobuz provider method: ${method}`);
  return handler(payload);
}

globalThis.OrchardQobuzProvider = Object.freeze({ invoke });
