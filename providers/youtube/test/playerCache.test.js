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

import test from 'node:test';
import assert from 'node:assert/strict';

const extraction = {
  exportedRawValues: { signatureTimestampVar: 20702 }, exported: ['nsigFunction'],
  output: 'const exportedVars = { nsigFunction: () => ({}) };'
};
let moduleId = 0;
async function freshLoader() {
  return (await import(`../src/runtime/player.js?cache-test=${++moduleId}`)).loadPlayer;
}
function iframe() {
  return { ok: true, text: async () => 'player\\/current123\\/' };
}

test('a matching disk cache skips the player download and extraction after restart', async () => {
  const previous = globalThis.__orchardPlayerCache;
  try {
    globalThis.__orchardPlayerCache = () => JSON.stringify({ version: 1, playerId: 'current123', extraction });
    for (let restart = 0; restart < 2; restart++) {
      const load = await freshLoader();
      const player = await load(async url => {
        assert.equal(url, 'https://www.youtube.com/iframe_api');
        return iframe();
      });
      assert.equal(player.signature_timestamp, 20702);
    }
  } finally { globalThis.__orchardPlayerCache = previous; }
});

test('stale, corrupt and explicitly refreshed caches require the current player script', async () => {
  const previous = globalThis.__orchardPlayerCache;
  try {
    for (const [stored, refresh] of [
      ['broken json', false],
      [JSON.stringify({ version: 1, playerId: 'old', extraction }), false],
      [JSON.stringify({ version: 1, playerId: 'current123', extraction }), true]
    ]) {
      globalThis.__orchardPlayerCache = () => stored;
      const load = await freshLoader();
      const urls = [];
      await assert.rejects(load(async url => {
        urls.push(url);
        return url.endsWith('/iframe_api') ? iframe() : { ok: false, status: 503 };
      }, { refresh }), /HTTP 503/);
      assert.equal(urls.length, 2);
      assert.match(urls[1], /\/s\/player\/current123\//);
    }
  } finally { globalThis.__orchardPlayerCache = previous; }
});

test('cold loads use native extraction, while memory and disk reuse report their own timings', async () => {
  const previousCache = globalThis.__orchardPlayerCache;
  const previousExtractor = globalThis.__orchardExtractPlayer;
  let stored;
  let extractions = 0;
  let requests = 0;
  globalThis.__orchardPlayerCache = value => value === undefined ? stored : (stored = value);
  globalThis.__orchardExtractPlayer = source => {
    assert.equal(source, 'downloaded player');
    extractions++;
    return { ...extraction, timings: { parseMs: 2, analyzeMs: 3, emitMs: 1 } };
  };
  const fetchImpl = async url => {
    requests++;
    return url.endsWith('/iframe_api') ? iframe() : { ok: true, text: async () => 'downloaded player' };
  };
  try {
    const load = await freshLoader();
    const cold = await load(fetchImpl);
    assert.equal(cold.timings.cache, 'cold');
    assert.equal(cold.timings.parseMs, 2);
    assert.equal(requests, 2);
    assert.equal(extractions, 1);
    const memory = await load(fetchImpl);
    assert.equal(memory.timings.cache, 'memory');
    assert.equal(memory.timings.parseMs, undefined);
    const restart = await freshLoader();
    assert.equal((await restart(fetchImpl)).timings.cache, 'disk');
    assert.equal(requests, 2);
    assert.equal(extractions, 1);
    assert.equal((await restart(fetchImpl, { refresh: true })).timings.cache, 'cold');
    assert.equal(requests, 4);
    assert.equal(extractions, 2);
  } finally {
    globalThis.__orchardPlayerCache = previousCache;
    globalThis.__orchardExtractPlayer = previousExtractor;
  }
});
