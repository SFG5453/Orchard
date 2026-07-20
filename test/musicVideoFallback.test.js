import assert from 'node:assert/strict';
import test from 'node:test';
import { createMusicVideoFallback, isAgeGateRiskTrack } from '../electron/playback/musicVideoFallback.js';

const normalizedLookupText = (value = '') => String(value).trim().toLowerCase();
const shelfItems = (shelf) => shelf?.items || [];

test('finds a duration-matched video when a standalone track has no duration', async () => {
  const searches = [];
  const yt = {
    music: {
      async search(query, options) {
        searches.push([query, options.type]);
        if (options.type === 'song') {
          return {
            songs: { items: [{
              id: 'song-id',
              title: 'Standalone Song',
              artist: 'The Artist',
              duration: { seconds: 213 }
            }] }
          };
        }
        return {
          videos: { items: [
            {
              id: 'wrong-duration',
              type: 'video',
              title: 'Standalone Song',
              artist: 'The Artist',
              duration: { seconds: 240 }
            },
            {
              id: 'matching-video',
              type: 'video',
              title: 'Standalone Song',
              artist: 'The Artist',
              duration: { seconds: 215 }
            }
          ] }
        };
      }
    }
  };
  const findFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });

  const fallback = await findFallback(yt, {
    videoId: 'song-id',
    title: 'Standalone Song',
    artist: 'The Artist'
  });

  assert.equal(fallback?.id, 'matching-video');
  assert.deepEqual(searches, [
    ['Standalone Song The Artist', 'song'],
    ['Standalone Song The Artist', 'video']
  ]);
});

test('chooses the closest video within the five-second duration window', async () => {
  const yt = { music: { search: async () => ({
    videos: { items: [
      { id: 'four-away', type: 'video', title: 'Album Song', artist: 'The Artist', durationSeconds: 184 },
      { id: 'one-away', type: 'video', title: 'Album Song', artist: 'The Artist', durationSeconds: 181 }
    ] }
  }) } };
  const findFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });

  const fallback = await findFallback(yt, {
    videoId: 'song-id',
    title: 'Album Song',
    artist: 'The Artist',
    durationSeconds: 180
  });

  assert.equal(fallback?.id, 'one-away');
});

test('rejects videos with a different title, artist, or duration', async () => {
  const yt = { music: { search: async () => ({
    videos: { items: [
      { id: 'wrong-title', type: 'video', title: 'Album Song Live', artist: 'The Artist', durationSeconds: 180 },
      { id: 'wrong-artist', type: 'video', title: 'Album Song', artist: 'Another Artist', durationSeconds: 180 },
      { id: 'wrong-duration', type: 'video', title: 'Album Song', artist: 'The Artist', durationSeconds: 186 }
    ] }
  }) } };
  const findFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });

  const fallback = await findFallback(yt, {
    videoId: 'song-id',
    title: 'Album Song',
    artist: 'The Artist',
    durationSeconds: 180
  });

  assert.equal(fallback, null);
});

test('recognizes the legacy proactive age-gate title risk', () => {
  assert.equal(isAgeGateRiskTrack({ title: 'Fuck Ya!' }), true);
  assert.equal(isAgeGateRiskTrack({ title: 'Ordinary Song' }), false);
});
