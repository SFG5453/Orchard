import assert from 'node:assert/strict';
import test from 'node:test';

import { callLastfm, cleanOpaqueToken, lastfmSignature, normalizeTrack } from '../src/index.js';

test('lastfmSignature sorts fields and excludes response formatting', () => {
  assert.equal(
    lastfmSignature({ method: 'track.scrobble', artist: 'Björk', format: 'json', api_key: 'key' }, 'secret'),
    'b814e2b06cb791bfa98825390aeb6870'
  );
});

test('normalizeTrack bounds metadata and duration', () => {
  assert.deepEqual(normalizeTrack({
    title: '  Song  ',
    artist: ' Artist ',
    album: ' Album ',
    duration: 245.6
  }), {
    title: 'Song',
    artist: 'Artist',
    album: 'Album',
    albumArtist: '',
    duration: 246
  });
  assert.equal(normalizeTrack({ title: 'Song' }), null);
});

test('cleanOpaqueToken accepts modern opaque Last.fm credentials', () => {
  const token = 'AbCdEf0123456789_-AbCdEf0123456789_-AbCdEf0123456789_-';
  assert.equal(cleanOpaqueToken(token, 'invalid'), token);
  assert.throws(() => cleanOpaqueToken('too-short', 'invalid'), /invalid/);
  assert.throws(() => cleanOpaqueToken(`${token}\nvalue`, 'invalid'), /invalid/);
});

test('callLastfm signs and sends HTTPS form requests', async () => {
  let captured;
  const result = await callLastfm(
    { LASTFM_API_KEY: 'api-key', LASTFM_SHARED_SECRET: 'shared-secret' },
    'track.updateNowPlaying',
    { artist: 'Artist', track: 'Song', sk: 'session' },
    async (url, options) => {
      captured = { url, options };
      return Response.json({ nowplaying: { track: { '#text': 'Song' } } });
    }
  );

  assert.ok(result.nowplaying);
  assert.equal(captured.url, 'https://ws.audioscrobbler.com/2.0/');
  assert.equal(captured.options.method, 'POST');
  assert.equal(captured.options.body.get('api_key'), 'api-key');
  assert.match(captured.options.body.get('api_sig'), /^[a-f0-9]{32}$/);
});
