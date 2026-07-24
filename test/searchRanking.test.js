import assert from 'node:assert/strict';
import test from 'node:test';
import { sortBySearchPopularity, sortByTopMatch } from '../src/app/browse/searchRanking.js';

test('an exact song title outranks a popular partial artist match', () => {
  const song = { id: 'usher-burn', type: 'song', title: 'Burn', artist: 'Usher' };
  const artist = {
    browseId: 'let-babylon-burn',
    type: 'artist',
    title: 'Let Babylon Burn',
    subtitle: 'Artist • 1.12M monthly audience'
  };

  assert.deepEqual(sortByTopMatch([artist, song], 'burn'), [song, artist]);
});

test('a title and artist credit match outranks an artist matching only one query word', () => {
  const song = { id: 'usher-burn', type: 'song', title: 'Burn', artist: 'Usher' };
  const artist = {
    browseId: 'usher',
    type: 'artist',
    title: 'USHER',
    subtitle: 'Artist • 82.5M monthly audience'
  };

  assert.deepEqual(sortByTopMatch([artist, song], 'burn usher'), [song, artist]);
});

test('popularity breaks ties between equally relevant results', () => {
  const lessPopular = { browseId: 'one', title: 'Burn', subtitle: 'Artist • 137K subscribers' };
  const morePopular = { browseId: 'two', title: 'Burn', subtitle: 'Artist • 82.5M monthly audience' };

  assert.deepEqual(sortByTopMatch([lessPopular, morePopular], 'burn'), [morePopular, lessPopular]);
  assert.deepEqual(sortBySearchPopularity([lessPopular, morePopular]), [morePopular, lessPopular]);
});

test('hydrated track views outrank subscriber metadata for an exact-title match', () => {
  const smallArtist = { browseId: 'burn', title: 'Burn', subtitle: 'Artist • 4 subscribers' };
  const popularSong = {
    id: 'popular-burn',
    type: 'song',
    title: 'Burn',
    artists: ['Usher'],
    searchPopularity: 64_000_000
  };

  assert.deepEqual(sortByTopMatch([smallArtist, popularSong], 'burn'), [popularSong, smallArtist]);
});
