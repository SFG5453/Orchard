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

import assert from 'node:assert/strict';
import test from 'node:test';
import { addPoToken, createYouTubePoTokenService } from '../src/playback/youtubePoToken.js';

test('addPoToken protects a media URL without disturbing its signed parameters', () => {
  const result = new URL(addPoToken(
    'https://rr1.googlevideo.com/videoplayback?itag=140&pot=stale&sig=signed',
    'fresh/value'
  ));

  assert.equal(result.searchParams.get('itag'), '140');
  assert.equal(result.searchParams.get('sig'), 'signed');
  assert.equal(result.searchParams.get('pot'), 'fresh/value');
});

test('PO token service reuses one minter and caches video-bound tokens', async () => {
  let minterCreations = 0;
  let mints = 0;
  const service = createYouTubePoTokenService({
    createMinter: async () => {
      const generation = ++minterCreations;
      return {
        expiresAt: Date.now() + 60_000,
        minter: {
          mintAsWebsafeString: async (videoId) => {
            mints += 1;
            return `token-${generation}-${videoId}`;
          }
        }
      };
    }
  });

  assert.equal(await service.get('first'), 'token-1-first');
  assert.equal(await service.get('first'), 'token-1-first');
  assert.equal(await service.get('second'), 'token-1-second');
  assert.equal(minterCreations, 1);
  assert.equal(mints, 2);
});

test('a rejected token triggers one shared re-attestation', async () => {
  let minterCreations = 0;
  const service = createYouTubePoTokenService({
    createMinter: async () => {
      const generation = ++minterCreations;
      return {
        expiresAt: Date.now() + 60_000,
        minter: {
          mintAsWebsafeString: async (videoId) => `token-${generation}-${videoId}`
        }
      };
    }
  });
  const rejectedToken = await service.get('track');

  const [first, second] = await Promise.all([
    service.get('track', { rejectedToken }),
    service.get('track', { rejectedToken })
  ]);

  assert.equal(first, 'token-2-track');
  assert.equal(second, first);
  assert.equal(minterCreations, 2);
});

test('different videos share an attestation that is still in flight', async () => {
  let creations = 0;
  let finishAttestation;
  const service = createYouTubePoTokenService({
    createMinter: () => {
      creations++;
      return new Promise(resolve => { finishAttestation = resolve; });
    }
  });
  const first = service.get('first');
  const second = service.get('second');
  assert.equal(creations, 1);
  finishAttestation({ minter: { mintAsWebsafeString: async id => `token-${id}` } });
  assert.deepEqual(await Promise.all([first, second]), ['token-first', 'token-second']);
});

test('tokens refresh when the minter expires', async () => {
  let time = 1000;
  let creations = 0;
  const service = createYouTubePoTokenService({
    now: () => time,
    createMinter: async () => {
      const version = ++creations;
      return { expiresAt: time + 100, minter: { mintAsWebsafeString: async id => `${version}-${id}` } };
    }
  });
  assert.equal(await service.get('track'), '1-track');
  time += 101;
  assert.equal(await service.get('track'), '2-track');
  assert.equal(creations, 2);
});
