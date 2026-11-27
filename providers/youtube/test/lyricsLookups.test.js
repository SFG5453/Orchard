import assert from 'node:assert/strict';
import test from 'node:test';
import { createLyricsResolver, lyricsLookups } from '../src/catalog/lyricsResolver.js';

const spellings = (track) => lyricsLookups(track).map((lookup) => [lookup.title, lookup.artist]);

test('featured guests and remaster tags get a bare fallback lookup', () => {
  assert.deepEqual(spellings({ title: 'Despacito (feat. Daddy Yankee)', artist: 'Luis Fonsi, Daddy Yankee' }),
    [['Despacito (feat. Daddy Yankee)', 'Luis Fonsi, Daddy Yankee'], ['Despacito', 'Luis Fonsi']]);
  assert.deepEqual(spellings({ title: 'Here Comes the Sun - 2019 Remaster', artist: 'The Beatles' }),
    [['Here Comes the Sun - 2019 Remaster', 'The Beatles'], ['Here Comes the Sun', 'The Beatles']]);
  assert.deepEqual(spellings({ title: 'Old Town Road ft. Billy Ray Cyrus', artist: 'Lil Nas X' }),
    [['Old Town Road ft. Billy Ray Cyrus', 'Lil Nas X'], ['Old Town Road', 'Lil Nas X']]);
});

test('names that only look like lists stay whole when the catalog lists them as one artist', () => {
  assert.deepEqual(spellings({ title: 'The Boxer', artist: 'Simon & Garfunkel', artists: ['Simon & Garfunkel'] }),
    [['The Boxer', 'Simon & Garfunkel']]);
  assert.deepEqual(spellings({ title: 'Song', artist: 'Artist' }), [['Song', 'Artist']]);
});

test('the bare lookup only runs after the exact one finds nothing', async (t) => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  const queries = [];
  globalThis.fetch = async (url) => {
    const params = new URLSearchParams(String(url).split('?')[1]);
    queries.push(`${String(url).includes('/get?') ? 'get' : 'search'}:${params.get('track_name')}`);
    if (params.get('track_name') === 'Despacito')
      return Response.json({ duration: 229, syncedLyrics: '[00:01.00]First line' });
    return String(url).includes('/get?') ? new Response('{}', { status: 404 }) : Response.json([]);
  };
  const track = { id: 'kJQP7kiw5Fk', title: 'Despacito (feat. Daddy Yankee)', artist: 'Luis Fonsi, Daddy Yankee', durationSeconds: 229 };
  const result = await createLyricsResolver({ loadYouTubeLyricsText: async () => '' })(track, 'lrclib');
  assert.equal(result.mode, 'synced');
  assert.deepEqual(queries, ['get:Despacito (feat. Daddy Yankee)', 'search:Despacito (feat. Daddy Yankee)', 'get:Despacito']);
});
