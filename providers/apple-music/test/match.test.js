import assert from 'node:assert/strict';
import test from 'node:test';
import { findSong } from '../src/match.js';

const song = (id, name, durationInMillis, albumName = 'Album') =>
  ({ id, attributes: { name, artistName: 'Artist', albumName, durationInMillis } });

function catalog(songs) {
  return {
    storefront: () => 'es',
    get: async () => ({ results: { songs: { data: songs } } })
  };
}

test('a known length picks the matching cut when titles fold together', async () => {
  const client = catalog([song('live', 'Song (Live)', 262000, 'Live Album'), song('studio', 'Song', 200400)]);
  assert.equal((await findSong(client, { title: 'Song', artist: 'Artist', durationSeconds: 200 })).id, 'studio');
  assert.equal((await findSong(client, { title: 'Song', artist: 'Artist', durationSeconds: 261 })).id, 'live');
});

test('without a length, or with no close cut, the old choice stands', async () => {
  const client = catalog([song('live', 'Song (Live)', 262000), song('studio', 'Song', 200400)]);
  assert.equal((await findSong(client, { title: 'Song', artist: 'Artist' })).id, 'live');
  assert.equal((await findSong(client, { title: 'Song', artist: 'Artist', durationSeconds: 400 })).id, 'live');
});

test('the album still breaks ties between cuts of the same length', async () => {
  const client = catalog([song('single', 'Song', 200000, 'Song - Single'), song('lp', 'Song', 200500, 'Album')]);
  assert.equal((await findSong(client, { title: 'Song', artist: 'Artist', album: 'Album', durationSeconds: 200 })).id, 'lp');
});
