import assert from 'node:assert/strict';
import test from 'node:test';

import { createPlaybackStreamCache } from '../electron/playback/playbackStreamCache.js';

function stream(itag) {
  return {
    expiresAt: Date.now() + 60_000,
    format: { itag },
    mediaKind: 'audio',
    url: `https://example.test/audio-${itag}`
  };
}

test('explicit stream keys never alias a different resolved format', () => {
  const cache = createPlaybackStreamCache();
  const requestedKey = cache.key('track', { mediaKind: 'audio', itag: 140 });
  const resolved = stream(251);

  cache.cacheStream('track', requestedKey, resolved, { mediaKind: 'audio', itag: 140 });

  assert.equal(cache.getStream(requestedKey), undefined);
  assert.equal(cache.getStream(cache.key('track', { mediaKind: 'audio', itag: 251 })), resolved);
});

test('matching explicit stream formats remain cacheable', () => {
  const cache = createPlaybackStreamCache();
  const requestedKey = cache.key('track', { mediaKind: 'audio', itag: 140 });
  const resolved = stream(140);

  cache.cacheStream('track', requestedKey, resolved, {
    mediaKind: 'audio',
    itag: 140,
    refreshStream: true
  });

  assert.equal(cache.getStream(requestedKey), resolved);
  assert.equal(cache.getOptions(requestedKey).refreshStream, undefined);
});
