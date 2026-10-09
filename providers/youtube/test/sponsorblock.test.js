import assert from 'node:assert/strict';
import test from 'node:test';
import { getNonMusicSegments } from '../src/integrations/sponsorblock.js';

function stubFetch(payload, status = 200) {
  const requests = [];
  globalThis.fetch = async (url) => {
    requests.push(new URLSearchParams(String(url).split('?')[1]));
    return { status, ok: status >= 200 && status < 300, json: async () => payload };
  };
  return requests;
}

const original = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = original; });

test('asks only for the non-music category and merges touching spans', async () => {
  const requests = stubFetch([
    { UUID: 'a', category: 'music_offtopic', segment: [0, 12], videoDuration: 200 },
    { UUID: 'b', category: 'music_offtopic', segment: [12.1, 20], videoDuration: 200 },
    { UUID: 'c', category: 'music_offtopic', segment: [190, 400], videoDuration: 200 }
  ]);
  const segments = await getNonMusicSegments('merge000001', 200);
  assert.deepEqual(requests[0].getAll('category'), ['music_offtopic']);
  assert.deepEqual(segments.map(({ startTime, endTime }) => [startTime, endTime]), [[0, 20], [190, 200]]);
});

test('drops segments timed against a different cut of the video', async () => {
  stubFetch([{ UUID: 'a', category: 'music_offtopic', segment: [0, 30], videoDuration: 260 }]);
  assert.deepEqual(await getNonMusicSegments('cut00000001', 200), []);
});

test('answers with nothing when SponsorBlock fails or has no data', async () => {
  stubFetch([], 404);
  assert.deepEqual(await getNonMusicSegments('none0000001', 200), []);
  stubFetch([], 500);
  assert.deepEqual(await getNonMusicSegments('down0000001', 200), []);
});

test('music videos also ask for sponsor, self-promotion and interaction spans', async () => {
  const requests = stubFetch([
    { UUID: 's', category: 'sponsor', segment: [60, 75], videoDuration: 245 }
  ]);
  const segments = await getNonMusicSegments('video000001', 245, { video: true });
  assert.deepEqual(requests[0].getAll('category'), ['music_offtopic', 'sponsor', 'selfpromo', 'interaction']);
  assert.deepEqual(segments.map(({ category, startTime, endTime }) => [category, startTime, endTime]),
    [['sponsor', 60, 75]]);
});
