import assert from 'node:assert/strict';
import test from 'node:test';
import {
  normalizeText,
  rankArtistCandidates,
  releaseKey,
  releaseMatches
} from '../src/index.js';

test('normalizes artist and release names for matching', () => {
  assert.equal(normalizeText('Beyoncé & JAY-Z'), 'beyonce and jay z');
  assert.equal(releaseKey('SOS (Deluxe Edition)'), 'sos');
  assert.equal(releaseMatches('SOS (Deluxe Edition)', releaseKey('SOS')), true);
});

test('requires an album-confirmed exact-name candidate', () => {
  const artists = [
    { artistId: 605800394, artistName: 'SZA', primaryGenreName: 'R&B/Soul', primaryGenreId: 15 },
    { artistId: 1889121890, artistName: 'SZA', primaryGenreName: null, primaryGenreId: null }
  ];
  const albums = [
    { artistId: 605800394, artistName: 'SZA', collectionName: 'SOS', primaryGenreName: 'R&B/Soul' }
  ];

  const result = rankArtistCandidates(artists, albums, 'SZA', 'SOS')[0];
  assert.equal(result.artistId, 605800394);
  assert.equal(result.genre, 'R&B/Soul');
  assert.equal(result.confirmedByAlbum, true);
  assert.equal(result.confidence, 1);
});

test('does not confirm a same-name artist whose album belongs to another id', () => {
  const result = rankArtistCandidates(
    [{ artistId: 2, artistName: 'Example', primaryGenreName: 'Pop' }],
    [{ artistId: 1, artistName: 'Example', collectionName: 'First Light' }],
    'Example',
    'First Light'
  )[0];

  assert.equal(result.confirmedByAlbum, false);
});
