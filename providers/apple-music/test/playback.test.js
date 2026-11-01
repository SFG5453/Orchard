/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { createAppleMusic, parsePlaylist, playbackId } from '../src/index.js';

const PLAYLIST_URL = 'https://aod-ssl.itunes.apple.com/itunes-assets/x/P697195462_default.m3u8';
const MEDIA_URL = 'https://aod-ssl.itunes.apple.com/itunes-assets/x/mzaf_A697195462.rmhq.aac.wa.mp4';
const KEY_URI = 'data:;base64,AAAAACmOW8YAHbtJTWkWjg==';

// The shape of Apple's 28:ctrp256 playlists, shortened to two segments.
function playlist(secondRange = '326173@382710', key = `#EXT-X-KEY:METHOD=ISO-23001-7,URI="${KEY_URI}"\n`) {
  return '#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n' + key +
    '#EXT-X-MAP:URI="mzaf_A697195462.rmhq.aac.wa.mp4",BYTERANGE="1006@0"\n' +
    '#EXTINF:9.98458,\t\n#EXT-X-BYTERANGE:381704@1006\nmzaf_A697195462.rmhq.aac.wa.mp4\n' +
    `#EXTINF:9.98458,\t\n#EXT-X-BYTERANGE:${secondRange}\nmzaf_A697195462.rmhq.aac.wa.mp4\n` +
    '#EXT-X-ENDLIST\n';
}

test('parses ranges, key and duration', () => {
  const parsed = parsePlaylist(PLAYLIST_URL, playlist());
  assert.equal(parsed.media, MEDIA_URL);
  assert.deepEqual(parsed.ranges, [[0, 1006], [1006, 381704], [382710, 326173]]);
  assert.equal(parsed.keyUri, KEY_URI);
  assert.equal(parsed.durations.length, 2);
  assert.ok(Math.abs(parsed.duration - 19.96916) < 1e-6);
});

test('rejects gaps and playlists without a Widevine key', () => {
  assert.throws(() => parsePlaylist(PLAYLIST_URL, playlist('326173@400000')), /gap/);
  const fairPlay = '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/P0/s1/e1",KEYFORMAT="com.apple.streamingkeydelivery"\n';
  assert.throws(() => parsePlaylist(PLAYLIST_URL, playlist(undefined, fairPlay)), /Widevine/);
  assert.equal(parsePlaylist(PLAYLIST_URL, playlist(undefined, fairPlay + `#EXT-X-KEY:METHOD=ISO-23001-7,URI="${KEY_URI}"\n`)).keyUri, KEY_URI);
});

test('streams only catalog songs', () => {
  assert.equal(playbackId({ id: 'am.songs.697195462' }), '697195462');
  assert.equal(playbackId({ id: 'am.songs.i.abc', appleId: '42' }), '42');
  assert.equal(playbackId({ id: 'am.songs.i.abc' }), '');
});

function appleMusic(routes) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url, init = {}) => {
      const method = init.method || 'GET';
      requests.push({ method, url, headers: init.headers || {}, body: init.body ? JSON.parse(init.body) : undefined });
      const route = routes.find(([pattern]) => pattern.test(`${method} ${url}`));
      assert.ok(route, `Unexpected ${method} ${url}`);
      const [, status, body] = route;
      const text = typeof body === 'string' ? body : JSON.stringify(body);
      return { ok: status < 400, status, url, json: async () => JSON.parse(text), text: async () => text };
    },
    session: () => ({ developerToken: 'dev', userToken: 'user', storefront: 'us', origin: 'https://appassets.androidplatform.net' })
  });
  return { client, requests };
}

test('standard quality prefers the 64 kbps flavor', async () => {
  const { client } = appleMusic([
    [/^POST .*webPlayback$/, 200, { songList: [{
      'hls-key-server-url': 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense',
      assets: [{ flavor: '28:ctrp256', URL: 'https://example.invalid/high.m3u8' }, { flavor: '32:ctrp64', URL: PLAYLIST_URL }]
    }] }],
    [/^GET .*P697195462_default\.m3u8$/, 200, playlist()]
  ]);
  const stream = await client.playback.stream({ id: 'am.songs.697195462' }, 'standard');
  assert.equal(stream.flavor, '32:ctrp64');
});

test('resolves the preferred flavor and licenses it', async () => {
  const { client, requests } = appleMusic([
    [/^POST .*webPlayback$/, 200, { songList: [{
      'hls-key-server-url': 'https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense',
      assets: [{ flavor: '30:cbcp256', URL: 'https://example.invalid/cbcs.m3u8' }, { flavor: '28:ctrp256', URL: PLAYLIST_URL }]
    }] }],
    [/^GET .*P697195462_default\.m3u8$/, 200, playlist()],
    [/^POST .*acquireWebPlaybackLicense$/, 200, { status: 0, license: 'TElDRU5TRQ==' }]
  ]);
  const stream = await client.playback.stream({ id: 'am.songs.697195462' });
  assert.equal(stream.flavor, '28:ctrp256');
  assert.equal(stream.appleId, '697195462');
  assert.equal(stream.media, MEDIA_URL);
  assert.deepEqual(requests[0].body, { salableAdamId: '697195462' });
  assert.equal(requests[0].headers['Music-User-Token'], 'user');
  assert.equal(requests[0].headers.Referer, 'https://appassets.androidplatform.net/');

  const { license } = await client.playback.license({ ...stream, challenge: 'Q0hBTExFTkdF' });
  assert.equal(license, 'TElDRU5TRQ==');
  assert.deepEqual(requests[2].body, {
    challenge: 'Q0hBTExFTkdF', 'key-system': 'com.widevine.alpha', uri: KEY_URI, adamId: '697195462',
    isLibrary: false, 'user-initiated': true
  });
});

test('reports Apple\'s own reason when a song is not offered', async () => {
  const { client } = appleMusic([[/^POST .*webPlayback$/, 200, { failureType: '3076', customerMessage: 'Subscribe to play.' }]]);
  await assert.rejects(client.playback.stream({ id: 'am.songs.1' }), { code: 'playback_3076', message: 'Subscribe to play.' });
});

test('a refused license is an error', async () => {
  const { client } = appleMusic([[/^POST .*license$/, 200, { status: -1002 }]]);
  await assert.rejects(client.playback.license({ licenseUrl: 'https://x/license', challenge: 'a', keyUri: KEY_URI, appleId: '1' }),
    { code: 'license_refused' });
});

test('a music video resolves to its master playlist', async () => {
  const { client, requests } = appleMusic([[/^POST .*webPlayback$/, 200, { songList: [{
    'hls-playlist-url': 'https://play-edge.itunes.apple.com/master.m3u8',
    'hls-key-server-url': 'https://play.itunes.apple.com/license'
  }] }]]);
  assert.deepEqual(await client.playback.video('am.music-videos.1458003118'), {
    appleId: '1458003118', licenseUrl: 'https://play.itunes.apple.com/license', master: 'https://play-edge.itunes.apple.com/master.m3u8'
  });
  assert.deepEqual(requests[0].body, { salableAdamId: '1458003118' });
  await assert.rejects(client.playback.video('am.songs.1'), { code: 'not_playable' });
});

test('Atmos resolution returns the enhanced playlist and the playback license endpoint', async () => {
  const { createPlayback } = await import('../src/playback.js');
  const calls = [];
  const client = { storefront: () => 'us', playbackHeaders: () => ({}), editorial: async (path, query, options) => {
    calls.push({ path, query, options });
    return { data: [{ attributes: { audioTraits: ['atmos'], extendedAssetUrls: { enhancedHls: 'https://example.com/master.m3u8' } } }] };
  } };
  const fetched = [];
  const playback = createPlayback(client, async (url) => {
    fetched.push(url);
    if (url === 'https://example.com/master.m3u8') return { ok: true, status: 200, url, text: async () => '#EXTM3U' };
    return { ok: true, status: 200, text: async () => JSON.stringify({ songList: [{ 'hls-key-server-url': 'https://example.com/license' }] }) };
  });
  assert.deepEqual(await playback.atmos('am.songs.123'), {
    appleId: '123', available: true, master: 'https://example.com/master.m3u8', licenseUrl: 'https://example.com/license', masterText: '#EXTM3U'
  });
  assert.equal(fetched.length, 2);
  assert.equal((await playback.atmos('123')).appleId, '123');
  assert.deepEqual(calls[0].query, { extend: 'audioTraits,extendedAssetUrls' });
  await assert.rejects(playback.atmos('am.songs.i.1'), { code: 'not_playable' });
});


test('Atmos resolution still answers when the master playlist fails', async () => {
  const { createPlayback } = await import('../src/playback.js');
  const client = { storefront: () => 'us', playbackHeaders: () => ({}), editorial: async () =>
    ({ data: [{ attributes: { audioTraits: ['atmos'], extendedAssetUrls: { enhancedHls: 'https://example.com/master.m3u8' } } }] }) };
  const playback = createPlayback(client, async (url) => url.endsWith('master.m3u8')
    ? { ok: false, status: 503, text: async () => '' }
    : { ok: true, status: 200, text: async () => JSON.stringify({ songList: [{ 'hls-key-server-url': 'https://example.com/license' }] }) });
  assert.deepEqual(await playback.atmos('am.songs.123'),
    { appleId: '123', available: true, master: 'https://example.com/master.m3u8', licenseUrl: 'https://example.com/license' });
});

test('non-Atmos tracks use stereo without requesting a playback license', async () => {
  const { createPlayback } = await import('../src/playback.js');
  let requested = false;
  const client = { storefront: () => 'us', editorial: async () => ({ data: [{ attributes: { audioTraits: ['lossless', 'lossy-stereo'], extendedAssetUrls: { enhancedHls: 'https://example.com/master' } } }] }) };
  const playback = createPlayback(client, async () => { requested = true; throw new Error('unexpected'); });
  assert.deepEqual(await playback.atmos('am.songs.123'), { appleId: '123', available: false });
  assert.equal(requested, false);
});
