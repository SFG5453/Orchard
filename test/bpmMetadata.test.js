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
    { bpm: 220, beatConfidence: 0.82, key: 'Em', keyConfidence: 0.82, source: 'GetSongBPM' }
  );
  assert.equal(merged.bpm, 110);
  assert.equal(merged.analyzedBpm, 109);
  assert.equal(merged.key, 'E minor');
  assert.equal(merged.analyzedKey, 'G major');
  assert.equal(merged.bpmSource, 'GetSongBPM');
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
