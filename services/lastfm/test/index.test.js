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

import assert from 'node:assert/strict';
import test from 'node:test';

import { callLastfm, cleanOpaqueToken, lastfmSignature, normalizeTrack } from '../src/index.js';

test('lastfmSignature sorts fields and excludes response formatting', () => {
  assert.equal(
    lastfmSignature({ method: 'track.scrobble', artist: 'Björk', format: 'json', api_key: 'key' }, 'secret'),
    'b814e2b06cb791bfa98825390aeb6870'
  );
});

test('normalizeTrack bounds metadata and duration', () => {
  assert.deepEqual(normalizeTrack({
    title: '  Song  ',
    artist: ' Artist ',
    album: ' Album ',
    duration: 245.6
  }), {
    title: 'Song',
    artist: 'Artist',
    album: 'Album',
    albumArtist: '',
    duration: 246
  });
  assert.equal(normalizeTrack({ title: 'Song' }), null);
});

test('cleanOpaqueToken accepts modern opaque Last.fm credentials', () => {
  const token = 'AbCdEf0123456789_-AbCdEf0123456789_-AbCdEf0123456789_-';
  assert.equal(cleanOpaqueToken(token, 'invalid'), token);
  assert.throws(() => cleanOpaqueToken('too-short', 'invalid'), /invalid/);
  assert.throws(() => cleanOpaqueToken(`${token}\nvalue`, 'invalid'), /invalid/);
});

test('callLastfm signs and sends HTTPS form requests', async () => {
  let captured;
  const result = await callLastfm(
    { LASTFM_API_KEY: 'api-key', LASTFM_SHARED_SECRET: 'shared-secret' },
    'track.updateNowPlaying',
    { artist: 'Artist', track: 'Song', sk: 'session' },
    async (url, options) => {
      captured = { url, options };
      return Response.json({ nowplaying: { track: { '#text': 'Song' } } });
    }
  );

  assert.ok(result.nowplaying);
  assert.equal(captured.url, 'https://ws.audioscrobbler.com/2.0/');
  assert.equal(captured.options.method, 'POST');
  assert.equal(captured.options.body.get('api_key'), 'api-key');
  assert.match(captured.options.body.get('api_sig'), /^[a-f0-9]{32}$/);
});

test('gateway answers 429 once the per-IP limiter says no', async () => {
  const { default: worker } = await import('../src/index.js');
  const keys = [];
  const env = {
    LASTFM_API_KEY: 'key', LASTFM_SHARED_SECRET: 'secret',
    LASTFM_LIMITER: { limit: async ({ key }) => { keys.push(key); return { success: false }; } }
  };
  const request = new Request('https://lastfm.test/now-playing', {
    method: 'POST', headers: { 'cf-connecting-ip': '203.0.113.7', 'content-type': 'application/json' }, body: '{}'
  });
  const response = await worker.fetch(request, env);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '60');
  assert.deepEqual(keys, ['203.0.113.7']);
  const health = await worker.fetch(new Request('https://lastfm.test/health'), env);
  assert.equal(health.status, 200);
});
