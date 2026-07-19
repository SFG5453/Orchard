import assert from 'node:assert/strict';
import test from 'node:test';

import { lastfmTrackPayload, shouldScrobble } from '../src/app/social/lastfmScrobbling.js';

test('Last.fm track payload uses structured Orchard metadata', () => {
  assert.deepEqual(lastfmTrackPayload({
    title: ' Song ',
    artists: [{ name: 'Artist' }],
    album: 'Album',
    durationSeconds: 181
  }), {
    title: 'Song',
    artist: 'Artist',
    album: 'Album',
    albumArtist: '',
    duration: 181
  });
});

test('Last.fm eligibility follows the half-track or four-minute rule', () => {
  assert.equal(shouldScrobble(30, 30), false);
  assert.equal(shouldScrobble(180, 89), false);
  assert.equal(shouldScrobble(180, 90), true);
  assert.equal(shouldScrobble(600, 239), false);
  assert.equal(shouldScrobble(600, 240), true);
});
