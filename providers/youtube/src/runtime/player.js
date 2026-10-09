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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { extract } from './extractor.js';

let cachedPlayer;
let cachedAt = 0;

function queryValue(url, key) {
  const query = String(url).split('?', 2)[1] || '';
  const pair = query.split('&').find(part => part.split('=', 1)[0] === key);
  return pair ? decodeURIComponent(pair.slice(pair.indexOf('=') + 1).replace(/\+/g, ' ')) : '';
}

function setQueryValue(url, key, value) {
  const [base, query = ''] = url.split('?', 2);
  const parts = query.split('&').filter(part => part && part.split('=', 1)[0] !== key);
  parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return `${base}?${parts.join('&')}`;
}

export function playerFromSource(source) {
  const extracted = extract(source);
  const start = Date.now();
  const player = playerFromExtraction(extracted);
  player.timings = { cache: 'cold', ...extracted.timings, compileMs: Date.now() - start };
  return player;
}

export function playerFromExtraction(extracted) {
  const signature_timestamp = Number(extracted.exportedRawValues?.signatureTimestampVar);
  if (!signature_timestamp || !extracted.exported.includes('nsigFunction')) {
    throw new Error('Could not extract the current YouTube player.');
  }
  // Same processor as YouTube.js Player.decipher, with arguments passed as
  // values rather than interpolated into downloaded JavaScript.
  const process = new Function('n', 'sp', 's', `${extracted.output}
    const url = exportedVars.nsigFunction('https://ytjs.googlevideo.com/videoplayback?expire=1234567890&n=' + encodeURIComponent(n), sp, s);
    for (const name of Object.getOwnPropertyNames(Object.getPrototypeOf(url))) {
      if (!['constructor', 'clone', 'set', 'get'].includes(name) && typeof url[name] === 'function') url[name]();
    }
    return { n: url.get('n'), sig: url.get(sp) };
  `);
  return {
    signature_timestamp,
    extraction: extracted,
    async decipher(url, signatureCipher, cipher) {
      const encoded = signatureCipher || cipher;
      let result = encoded ? queryValue(`?${encoded}`, 'url') : url;
      if (!result) throw new Error('YouTube returned an empty stream URL.');
      const n = queryValue(result, 'n');
      const s = encoded ? queryValue(`?${encoded}`, 's') : '';
      const sp = encoded ? queryValue(`?${encoded}`, 'sp') || 'signature' : '';
      if (n || s) {
        let solved;
        try { solved = process(n, sp, s); }
        catch (error) { throw new Error(`YouTube signature execution failed: ${error?.message || String(error)}`); }
        if (n) {
          if (!solved.n || solved.n.startsWith('enhanced_except_')) throw new Error('Could not decipher the YouTube stream.');
          result = setQueryValue(result, 'n', decodeURIComponent(solved.n));
        }
        if (s) {
          if (!solved.sig) throw new Error('Could not decipher the YouTube signature.');
          result = setQueryValue(result, sp, decodeURIComponent(solved.sig));
        }
      }
      return result;
    }
  };
}

function playerFromDisk(extraction) {
  const start = Date.now();
  const player = playerFromExtraction(extraction);
  player.timings = { cache: 'disk', compileMs: Date.now() - start };
  return player;
}

export async function loadPlayer(fetchImpl = globalThis.fetch, { refresh = false } = {}) {
  if (refresh || Date.now() - cachedAt > 60 * 60_000) cachedPlayer = null;
  if (cachedPlayer) {
    const start = Date.now();
    const player = await cachedPlayer;
    return { ...player, timings: { cache: 'memory', waitMs: Date.now() - start } };
  }
  if (!cachedPlayer) {
    cachedAt = Date.now();
    cachedPlayer = (async () => {
      let stored;
      if (!refresh && typeof globalThis.__orchardPlayerCache === 'function') {
        try {
          stored = JSON.parse(globalThis.__orchardPlayerCache() || 'null');
          const age = Date.now() - Number(stored?.checkedAt || 0);
          if (stored?.version === 1 && age >= 0 && age < 60 * 60_000)
            return playerFromDisk(stored.extraction);
        } catch { stored = null; }
      }
      const downloadStart = Date.now();
      const iframe = await fetchImpl('https://www.youtube.com/iframe_api');
      if (!iframe.ok) throw new Error(`Could not load the YouTube player (HTTP ${iframe.status}).`);
      const source = await iframe.text();
      const playerId = /player\\\/([a-zA-Z0-9_-]+)\\\//.exec(source)?.[1];
      if (!playerId) throw new Error('Could not locate the current YouTube player.');
      if (!refresh && typeof globalThis.__orchardPlayerCache === 'function') {
        try {
          if (stored?.version === 1 && stored.playerId === playerId) {
            const player = playerFromDisk(stored.extraction);
            globalThis.__orchardPlayerCache(JSON.stringify({ ...stored, checkedAt: Date.now() }));
            return player;
          }
        } catch { /* A missing or corrupt cache is rebuilt below. */ }
      }
      const response = await fetchImpl(`https://www.youtube.com/s/player/${playerId}/player_es6.vflset/en_US/base.js`);
      if (!response.ok) throw new Error(`Could not load the YouTube player script (HTTP ${response.status}).`);
      const playerSource = await response.text();
      const downloadMs = Date.now() - downloadStart;
      try {
        const player = playerFromSource(playerSource);
        player.timings.downloadMs = downloadMs;
        if (typeof globalThis.__orchardPlayerCache === 'function') {
          try { globalThis.__orchardPlayerCache(JSON.stringify({ version: 1, playerId, checkedAt: Date.now(), extraction: player.extraction })); }
          catch { /* Playback does not depend on the cache being writable. */ }
        }
        return player;
      }
      catch (error) { throw new Error(`YouTube player extraction failed: ${error?.message || String(error)}`); }
    })().catch(error => { cachedPlayer = null; throw error; });
  }
  return cachedPlayer;
}
