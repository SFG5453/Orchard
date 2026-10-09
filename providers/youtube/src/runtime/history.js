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

import { createBrowserMusicApi } from '../auth/browserMusicApi.js';

const musicOrigin = 'https://music.youtube.com';
const defaultClientVersion = '1.20260213.01.00';
const defaultUserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';

function clean(value) {
  return String(value || '').trim();
}

function positiveSeconds(value) {
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : 0;
}

// Sends stats pings using tracking URLs from the signed-in playback.resolve response.
export function createRuntimeHistory(fetchImpl = globalThis.fetch, now = () => Date.now()) {
  // Per-cpn state; the chain keeps a quick skip's final ping behind its start ping.
  const sessions = new Map();

  function sessionFor(cpn) {
    let state = sessions.get(cpn);
    if (!state) {
      state = { startedAt: now(), lastEt: 0, chain: Promise.resolve() };
      sessions.set(cpn, state);
    }
    return state;
  }

  function send(payload, url, params) {
    const session = payload.session || {};
    const cpn = clean(payload.cpn);
    if (!cpn || !clean(url)) throw new Error('YouTube history requires tracking URLs and a cpn.');
    const api = createBrowserMusicApi({
      authState: { browser: session },
      fetchImpl,
      youtubeMusicClientUserAgent: clean(session.userAgent) || defaultUserAgent,
      youtubeMusicClientVersion: clean(session.clientVersion) || defaultClientVersion,
      youtubeMusicOrigin: musicOrigin
    });
    const state = sessionFor(cpn);
    const itag = Number(payload.tracking?.itag) || 251;
    const rt = ((now() - state.startedAt) / 1000).toFixed(3);
    const request = state.chain.catch(() => {}).then(() => api.sendBrowserHistoryStat(url, {
      cpn,
      fmt: itag,
      afmt: itag,
      fs: 0,
      rt,
      rtn: rt,
      euri: '',
      lact: 1000,
      volume: 100,
      muted: 0,
      vis: 10,
      ...params
    }));
    state.chain = request;
    if (payload.final) request.finally(() => sessions.delete(cpn)).catch(() => {});
    return request.then(() => ({ recorded: true }));
  }

  return {
    start: (payload = {}) => {
      if (!clean(payload.cpn)) throw new Error('YouTube history requires a cpn.');
      const position = positiveSeconds(payload.watchTime);
      const state = sessionFor(clean(payload.cpn));
      state.lastEt = position;
      return send(payload, payload.tracking?.playbackUrl, {
        cmt: position.toFixed(3),
        mos: 0
      });
    },
    update: (payload = {}) => {
      if (!clean(payload.cpn)) throw new Error('YouTube history requires a cpn.');
      const position = positiveSeconds(payload.watchTime);
      const state = sessionFor(clean(payload.cpn));
      // Seeks backwards start a fresh interval at the new position.
      const st = Math.min(state.lastEt, position);
      state.lastEt = position;
      return send(payload, payload.tracking?.watchtimeUrl, {
        cmt: position.toFixed(3),
        st: st.toFixed(3),
        et: position.toFixed(3),
        state: payload.final ? 'paused' : 'playing',
        ...(payload.final ? { final: '1' } : {})
      });
    }
  };
}
