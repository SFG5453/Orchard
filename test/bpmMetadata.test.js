import assert from 'node:assert/strict';
import test from 'node:test';

import {
  bpmCacheKey,
  bpmTrackQuery,
  createBpmMetadataClient,
  mergeBpmMetadata,
  normalizeMusicalKey
} from '../src/audio/crossfade/bpmMetadata.js';

test('builds BPM queries from Orchard track shapes and skips speech or live media', () => {
  assert.deepEqual(
    bpmTrackQuery({ title: 'Master of Puppets', artists: ['Metallica'], type: 'song' }),
    { title: 'Master of Puppets', artist: 'Metallica' }
  );
  assert.deepEqual(
    bpmTrackQuery({ title: 'Example', artists: [{ name: 'Example Artist' }] }),
    { title: 'Example', artist: 'Example Artist' }
  );
  assert.equal(bpmTrackQuery({ title: 'Live Set', type: 'concert' }), null);
  assert.equal(bpmTrackQuery({ title: 'Episode 1', type: 'podcast_episode' }), null);
});

test('normalizes GetSongBPM key notation for transition planning', () => {
  assert.equal(normalizeMusicalKey('Em'), 'E minor');
  assert.equal(normalizeMusicalKey('F#'), 'F♯ major');
  assert.equal(normalizeMusicalKey('B♭ minor'), 'B♭ minor');
  assert.equal(normalizeMusicalKey('2m'), '');
});

test('merges catalog BPM and key while keeping the analyzed beat octave', () => {
  const merged = mergeBpmMetadata(
    { bpm: 109, beatConfidence: 0.7, key: 'G major', keyConfidence: 0.5 },
    { bpm: 220, tempoConfidence: 0.82, key: 'Em', keyConfidence: 0.82, source: 'GetSongBPM' }
  );
  assert.equal(merged.bpm, 110);
  assert.equal(merged.analyzedBpm, 109);
  assert.equal(merged.key, 'E minor');
  assert.equal(merged.analyzedKey, 'G major');
  assert.equal(merged.bpmSource, 'GetSongBPM');
  assert.equal(merged.beatConfidence, 0.7);
  assert.equal(merged.tempoConfidence, 0.82);
});

test('catalog tempo does not invent beat-grid confidence', () => {
  const merged = mergeBpmMetadata({}, {
    bpm: 120,
    tempoConfidence: 0.82,
    key: 'C',
    keyConfidence: 0.82,
    source: 'GetSongBPM'
  });

  assert.equal(merged.bpm, 120);
  assert.equal(merged.beatConfidence, 0);
  assert.equal(merged.tempoConfidence, 0.82);
});

test('deduplicates lookups and loads queue metadata with bounded concurrency', async () => {
  let calls = 0;
  const client = createBpmMetadataClient({
    endpoint: 'https://bpm.example/bpm',
    storage: null,
    fetcher: async (url) => {
      calls += 1;
      const parsed = new URL(url);
      return Response.json({
        title: parsed.searchParams.get('title'),
        artist: parsed.searchParams.get('artist'),
        bpm: parsed.searchParams.get('title') === 'First' ? 100 : 104,
        key: 'C'
      });
    }
  });
  const tracks = [
    { id: 'one', title: 'First', artist: 'Artist' },
    { id: 'two', title: 'Second', artist: 'Artist' },
    { id: 'one', title: 'First', artist: 'Artist' }
  ];
  const [first, metadata] = await Promise.all([
    client.lookup(tracks[0]),
    client.lookupMany(tracks, { concurrency: 2 })
  ]);

  assert.equal(first.bpm, 100);
  assert.equal(metadata.get('one').key, 'C major');
  assert.equal(metadata.get('two').bpm, 104);
  assert.equal(calls, 2);
});

test('retries transient BPM lookup failures after the recovery window', async () => {
  const originalNow = Date.now;
  let now = originalNow();
  let calls = 0;
  Date.now = () => now;
  try {
    const client = createBpmMetadataClient({
      endpoint: 'https://bpm.example/bpm',
      storage: null,
      fetcher: async () => {
        calls += 1;
        if (calls === 1) return new Response(null, { status: 503 });
        return Response.json({ title: 'Recovered', artist: 'Artist', bpm: 120, key: 'C' });
      }
    });
    const track = { id: 'song', title: 'Recovered', artist: 'Artist' };

    assert.equal(await client.lookup(track), null);
    now += 30_001;
    assert.equal((await client.lookup(track)).bpm, 120);
    assert.equal(calls, 2);
  } finally {
    Date.now = originalNow;
  }
});

test('preserves a concurrent rate-limit cooldown after an earlier request succeeds', async () => {
  const originalNow = Date.now;
  let now = originalNow();
  let calls = 0;
  let releaseSuccess;
  const successGate = new Promise((resolve) => { releaseSuccess = resolve; });
  Date.now = () => now;
  try {
    const client = createBpmMetadataClient({
      endpoint: 'https://bpm.example/bpm',
      storage: null,
      report(event) {
        if (event === 'request-miss') releaseSuccess();
      },
      fetcher: async (url) => {
        calls += 1;
        const title = new URL(url).searchParams.get('title');
        if (title === 'Limited') return new Response(null, { status: 429 });
        await successGate;
        return Response.json({ title, artist: 'Artist', bpm: 120, key: 'C' });
      }
    });

    await client.lookupMany([
      { id: 'limited', title: 'Limited', artist: 'Artist' },
      { id: 'success', title: 'Success', artist: 'Artist' }
    ], { concurrency: 2 });

    assert.equal(
      await client.lookup({ id: 'blocked', title: 'Blocked', artist: 'Artist' }),
      null
    );
    assert.equal(calls, 2);

    now += 30_001;
    assert.equal(
      (await client.lookup({ id: 'recovered', title: 'Recovered', artist: 'Artist' })).bpm,
      120
    );
    assert.equal(calls, 3);
  } finally {
    Date.now = originalNow;
  }
});

test('reuses fresh BPM metadata from persistent storage without a network request', async () => {
  const query = { title: 'Master of Puppets', artist: 'Metallica' };
  let calls = 0;
  const client = createBpmMetadataClient({
    endpoint: 'https://bpm.example/bpm',
    fetcher: async () => {
      calls += 1;
      throw new Error('network should not be used');
    },
    storage: {
      load: async () => [{
        key: bpmCacheKey(query),
        cachedAt: Date.now(),
        metadata: { ...query, bpm: 220, key: 'E minor' }
      }],
      save: async () => {}
    }
  });

  const metadata = await client.lookup({ id: 'song', ...query });
  assert.equal(metadata.bpm, 220);
  assert.equal(metadata.key, 'E minor');
  assert.equal(calls, 0);
});
