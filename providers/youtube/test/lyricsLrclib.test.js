import assert from 'node:assert/strict';
import test from 'node:test';
import { createLyricsResolver, pickLrclibResult } from '../src/catalog/lyricsResolver.js';

const track = { id: 'abcdefghijk', title: 'Song', artist: 'Artist', album: 'Album', durationSeconds: 200 };
const live = { duration: 262, syncedLyrics: '[00:20.00]Live words', plainLyrics: 'Live words' };
const edit = { duration: 199.4, syncedLyrics: '[00:05.00]Album words', plainLyrics: 'Album words' };

function lrclib(t, { get, search }) {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  const urls = [];
  globalThis.fetch = async (url) => {
    urls.push(String(url));
    if (String(url).startsWith('https://lrclib.net/api/get?'))
      return get ? Response.json(get) : new Response('{}', { status: 404 });
    return Response.json(search || []);
  };
  return urls;
}

test('search picks the cut whose length matches, not the first hit', () => {
  assert.equal(pickLrclibResult([live, edit], 200), edit);
  assert.equal(pickLrclibResult([live, edit], undefined), live);
});

test('a cut of the wrong length lends its words without its timings', () => {
  const picked = pickLrclibResult([{ duration: 262, syncedLyrics: '[00:20.00]Live words\n[00:25.00]More' }], 200);
  assert.equal(picked.syncedLyrics, null);
  assert.equal(picked.plainLyrics, 'Live words\nMore');
});

test('a missing exact match falls back to a matching search result, synced', async (t) => {
  lrclib(t, { search: [live, edit] });
  const result = await createLyricsResolver({ loadYouTubeLyricsText: async () => '' })(track, 'lrclib');
  assert.equal(result.mode, 'synced');
  assert.equal(result.lines[0].text, 'Album words');
});

test('an exact match with only plain text still looks for a synced copy', async (t) => {
  const urls = lrclib(t, { get: { duration: 200, plainLyrics: 'Plain words' }, search: [edit] });
  const result = await createLyricsResolver({ loadYouTubeLyricsText: async () => '' })(track, 'lrclib');
  assert.equal(result.mode, 'synced');
  assert.equal(urls.length, 2);
});

test('an exact synced match skips the search entirely', async (t) => {
  const urls = lrclib(t, { get: edit, search: [live] });
  const result = await createLyricsResolver({ loadYouTubeLyricsText: async () => '' })(track, 'lrclib');
  assert.equal(result.lines[0].text, 'Album words');
  assert.equal(urls.length, 1);
});

test('without a matching cut the exact plain lyrics beat another cut', async (t) => {
  lrclib(t, { get: { duration: 200, plainLyrics: 'Exact words' }, search: [live] });
  const result = await createLyricsResolver({ loadYouTubeLyricsText: async () => '' })(track, 'lrclib');
  assert.equal(result.mode, 'unsynced');
  assert.equal(result.lines[0].text, 'Exact words');
});
