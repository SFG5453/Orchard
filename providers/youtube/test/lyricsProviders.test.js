import assert from 'node:assert/strict';
import test from 'node:test';
import { createLyricsResolver, lyricsProviders, resolveLyricsChain } from '../src/catalog/lyricsResolver.js';

const track = { id: 'abcdefghijk', title: 'Song', artist: 'Artist', album: 'Album', durationSeconds: 120 };
const ttml = '<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s">Found lyrics</p></div></body></tt>';
const ready = (source) => ({ status: 'ready', mode: 'unsynced', lines: [{ text: source }], source });

const synced = (source) => ({ status: 'ready', mode: 'synced', lines: [{ text: source, startTime: 1 }], source });

test('custom order stops at the first synced provider and never calls disabled providers', async () => {
  const called = [];
  const result = await resolveLyricsChain(async (_, provider) => {
    called.push(provider);
    return synced(provider);
  }, track, ['betterlyrics', 'lrclib']);
  assert.deepEqual(called, ['betterlyrics']);
  assert.equal(result.source, 'betterlyrics');
});

test('plain lyrics keep looking for a synced copy further down the chain', async () => {
  const called = [];
  const result = await resolveLyricsChain(async (_, provider) => {
    called.push(provider);
    return provider === 'apple' ? synced(provider) : ready(provider);
  }, track, ['lrclib', 'youtube', 'apple']);
  assert.deepEqual(called, ['lrclib', 'youtube', 'apple']);
  assert.equal(result.source, 'apple');
  assert.equal(result.mode, 'synced');
});

test('without any synced copy the first plain result still wins', async () => {
  const result = await resolveLyricsChain(async (_, provider) => ready(provider), track, ['lrclib', 'youtube']);
  assert.equal(result.source, 'lrclib');
  assert.equal(result.mode, 'unsynced');
});

test('failures and unavailable results fall through in configured order', async () => {
  const called = [];
  const result = await resolveLyricsChain(async (_, provider) => {
    called.push(provider);
    if (provider === 'bini') throw new Error('Network failed');
    if (provider === 'betterlyrics') return { status: 'unavailable', lines: [] };
    return ready(provider);
  }, track, ['bini', 'betterlyrics', 'youtube']);
  assert.deepEqual(called, ['bini', 'betterlyrics', 'youtube']);
  assert.equal(result.source, 'youtube');
});

test('disabling every provider makes no requests', async () => {
  const result = await resolveLyricsChain(() => assert.fail('Unexpected request'), track, []);
  assert.equal(result.status, 'unavailable');
});

test('unknown and duplicate providers are skipped', async () => {
  const called = [];
  await resolveLyricsChain(async (_, provider) => {
    called.push(provider);
    return { status: 'unavailable', lines: [] };
  }, track, ['unknown', 'lrclib', 'lrclib']);
  assert.deepEqual(called, ['lrclib']);
});

test('each provider resolves independently and reports its own source', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  for (const provider of lyricsProviders) {
    await t.test(provider, async () => {
      const called = [];
      globalThis.fetch = async (url) => {
        called.push(String(url));
        if (provider === 'bini') {
          if (String(url).startsWith('https://lyrics-api.binimum.org/'))
            return Response.json({ results: [{ lyricsUrl: 'https://fixture.test/song.ttml' }] });
          assert.equal(String(url), 'https://fixture.test/song.ttml');
          return new Response(ttml);
        }
        if (provider === 'betterlyrics') {
          assert.ok(String(url).startsWith('https://lyrics-api.boidu.dev/getLyrics?'));
          return Response.json({ ttml });
        }
        if (provider === 'lyricsplus') {
          assert.ok(String(url).startsWith('https://lyricsplus.binimum.org/v2/lyrics/get?'));
          return Response.json({ lyrics: [{ text: 'Found lyrics', time: 1000, duration: 2000 }] });
        }
        assert.equal(provider, 'lrclib');
        assert.ok(String(url).startsWith('https://lrclib.net/api/get?'));
        return Response.json({ syncedLyrics: '[00:01.00]Found lyrics' });
      };
      const resolver = createLyricsResolver({ loadYouTubeLyricsText: async (id) => {
        assert.equal(id, track.id);
        assert.equal(provider, 'youtube');
        return 'Found lyrics';
      }, appleTtml: provider === 'apple' ? ttml : '' });
      const result = await resolver(track, provider);
      assert.equal(result.status, 'ready');
      assert.equal(result.source, provider);
      assert.equal(result.lines[0].text, 'Found lyrics');
      if (provider === 'youtube' || provider === 'apple') assert.equal(called.length, 0);
      else assert.equal(result.lines[0].startTime, 1);
    });
  }
});

test('Apple lyrics without TTML from the host are unavailable and never fetch', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = () => assert.fail('Unexpected request');
  const resolver = createLyricsResolver({ loadYouTubeLyricsText: async () => '' });
  const result = await resolver(track, 'apple');
  assert.equal(result.status, 'unavailable');
  assert.equal(result.source, 'apple');
});
