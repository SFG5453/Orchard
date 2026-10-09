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
import { loadUpNext } from '../src/runtime/catalog.js';

const session = { cookie: 'SAPISID=test-cookie' };

test('autoplay requests a Music radio queue and normalizes panel tracks', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.match(url, /\/next\?/);
    const body = JSON.parse(options.body);
    assert.equal(body.videoId, 'seed');
    assert.equal(body.playlistId, 'RDAMVMseed');
    assert.equal(body.isAudioOnly, true);
    const row = (videoId, extras = {}) => ({ playlistPanelVideoRenderer: {
      videoId, title: { runs: [{ text: 'Radio song' }] },
      longBylineText: { runs: [{ text: 'Artist', navigationEndpoint: {
        browseEndpoint: { browseId: 'UCartist' }
      } }] },
      lengthText: { simpleText: '3:12' },
      thumbnail: { thumbnails: [{ url: 'https://example.com/art.jpg', width: 120 }] },
      ...extras
    } });
    return { ok: true, text: async () => JSON.stringify({ contents: { tabs: [{
      playlistPanelRenderer: { contents: [row('seed'), row('next'), row('blocked', { isPlayable: false })] }
    }] } }) };
  };
  try {
    const tracks = await loadUpNext({ session, videoId: 'seed' });
    assert.equal(tracks.length, 1);
    assert.equal(tracks[0].id, 'next');
    assert.equal(tracks[0].title, 'Radio song');
    assert.equal(tracks[0].artist, 'Artist');
    assert.equal(tracks[0].durationSeconds, 192);
    assert.equal(tracks[0].thumbnail, 'https://example.com/art.jpg');
  } finally { globalThis.fetch = original; }
});

test('autoplay validates the seed and reports network failures', async () => {
  await assert.rejects(loadUpNext({ session }), /video ID/);
  const original = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: false, status: 503, text: async () => '{}' });
  try { await assert.rejects(loadUpNext({ session, videoId: 'seed' }), /503/); }
  finally { globalThis.fetch = original; }
});
