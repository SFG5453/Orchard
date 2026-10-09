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

import test from 'node:test';
import assert from 'node:assert/strict';
import { resolvePlayback as resolve, resolveVideoPlayback } from '../src/runtime/playback.js';

function resolvePlayback(payload, fetchImpl, loader, tokenLoader) {
  return resolve(payload, (url, init) => url.startsWith('https://example.googlevideo.com/')
    ? Promise.resolve({ ok: true, status: 206, headers: { 'content-range': 'bytes 0-0/42' } })
    : fetchImpl(url, init), loader, tokenLoader);
}
const track = { id: 'abcdefghijk', type: 'song' };
const session = { cookie: 'SAPISID=test', visitorData: 'visitor', accountIndex: 2, dataSyncId: 'channel', clientVersion: '1.test' };
const player = { signature_timestamp: 20702, decipher: async () => 'https://example.googlevideo.com/audio?sig=solved' };
const response = (data, status = 200) => ({ ok: status === 200, status, text: async () => JSON.stringify(data) });
const success = (durationSeconds) => response({ videoDetails: { lengthSeconds: durationSeconds }, playabilityStatus: { status: 'OK' }, streamingData: { formats: [
  { itag: 18, mimeType: 'video/mp4', contentLength: '42', bitrate: 128000, signatureCipher: 'encrypted' }
] } });

test('uses v2 authenticated WEB_REMIX with the selected account and a live player', async () => {
  let decipherArgs;
  const result = await resolvePlayback({ track, session }, async (url, init) => {
    assert.match(url, /^https:\/\/music.youtube.com\/youtubei\/v1\/player/);
    const body = JSON.parse(init.body);
    assert.equal(body.context.client.clientName, 'WEB_REMIX');
    assert.equal(body.context.client.clientVersion, '1.test');
    assert.equal(body.playbackContext.contentPlaybackContext.signatureTimestamp, 20702);
    assert.equal(body.context.user.onBehalfOfUser, 'channel');
    assert.equal(init.headers['X-Goog-AuthUser'], '2');
    assert.equal(init.headers['X-Goog-Visitor-Id'], 'visitor');
    assert.equal(init.headers.Origin, 'https://music.youtube.com');
    assert.match(init.headers.Cookie, /SAPISID=test/);
    assert.match(init.headers.Authorization, /^SAPISIDHASH /);
    return success();
  }, async () => ({ ...player, decipher: async (...args) => { decipherArgs = args; return player.decipher(); } }));
  assert.deepEqual(decipherArgs, [undefined, 'encrypted', undefined]);
  assert.equal(result.authenticated, true);
  assert.equal(result.itag, 18);
  assert.equal(result.contentLength, 42);
  assert.equal(result.bitrate, 128000);
});
test('rejects unsupported items and missing credentials without network access', async () => {
  for (const type of ['playlist', 'album', 'artist']) {
    await assert.rejects(resolvePlayback({ track: { ...track, type }, session }, () => assert.fail()), /valid YouTube video ID/);
  }
  await assert.rejects(resolvePlayback({ track: { ...track, type: 'video', unplayable: true }, session }, () => assert.fail()), /valid YouTube video ID/);
  await assert.rejects(resolvePlayback({ track }, () => assert.fail()), /Sign in to YouTube/);
});
test('preserves the player API error instead of hiding HTTP 400 details', async () => {
  await assert.rejects(resolvePlayback({ track, session }, async () => response({ error: { message: 'Invalid player context' } }, 400), async () => player), /Invalid player context/);
});
test('bot checks stay authenticated and do not invoke a guest or VR fallback', async () => {
  let requests = 0;
  await assert.rejects(resolvePlayback({ track, session }, async () => {
    requests++;
    return response({ playabilityStatus: { status: 'LOGIN_REQUIRED', reason: 'Sign in to confirm you are not a bot' } });
  }, async () => player), /not a bot/);
  assert.equal(requests, 1);
});
test('stream retries refresh the player while retaining account credentials', async () => {
  await resolvePlayback({ track, session, refreshStream: true }, async (url, init) => {
    assert.match(init.headers.Cookie, /SAPISID=test/);
    return success();
  }, async (fetch, options) => { assert.equal(options.refresh, true); return player; });
});

test('probes the deciphered browser stream when the player omits its length', async () => {
  const result = await resolve({ track, session }, async (url, init) => {
    if (url.startsWith('https://example.googlevideo.com/')) {
      assert.equal(init.headers.Range, 'bytes=0-0');
      assert.equal(init.headers.Origin, 'https://music.youtube.com');
      assert.equal(init.headers.Cookie, undefined);
      return { ok: true, status: 206, headers: { 'content-range': 'bytes 0-0/12345' } };
    }
    return response({ playabilityStatus: { status: 'OK' }, streamingData: { formats: [{ itag: 18, url: 'raw' }] } });
  }, async () => player);
  assert.equal(result.contentLength, 12345);
});

test('high-quality audio carries a video-bound PO token in the player request and CDN URL', async () => {
  const calls = [];
  const result = await resolve({ track, session, streamQuality: 'high', refreshStream: true }, async (url, init) => {
    if (url.startsWith('https://example.googlevideo.com/')) {
      calls.push('probe');
      const params = new URL(url).searchParams;
      assert.equal(params.get('pot'), 'video-bound/token');
      assert.equal(params.get('sig'), 'solved');
      assert.equal(params.getAll('pot').length, 1);
      return { ok: true, status: 206, headers: { 'content-range': 'bytes 0-0/42' } };
    }
    calls.push('player');
    assert.equal(JSON.parse(init.body).serviceIntegrityDimensions.poToken, 'video-bound/token');
    return response({ playabilityStatus: { status: 'OK' }, streamingData: {
      adaptiveFormats: [
        { itag: 251, mimeType: 'audio/webm; codecs="opus"', bitrate: 160000, signatureCipher: 'opus' },
        { itag: 140, mimeType: 'audio/mp4', bitrate: 128000, url: 'aac' }
      ], formats: [{ itag: 18, mimeType: 'video/mp4', url: 'fallback' }]
    } });
  }, async () => ({ ...player, decipher: async (url, cipher) => {
    assert.equal(cipher, 'opus');
    return 'https://example.googlevideo.com/audio?sig=solved&pot=stale';
  } }), async (id, refresh) => {
    calls.push('token');
    assert.equal(id, track.id);
    assert.equal(refresh, true);
    return 'video-bound/token';
  });
  assert.deepEqual(calls, ['token', 'player', 'probe']);
  assert.equal(result.itag, 251);
  assert.equal(result.bitrate, 160000);
});

test('token failures surface before returning an unprotected adaptive stream', async () => {
  await assert.rejects(resolve({ track, session }, () => assert.fail('No unprotected request'),
    async () => player, async () => { throw new Error('YouTube integrity request failed (HTTP 503).'); }),
  /integrity request failed/);
});


test('album tracks resolve their exact ID regardless of browse video classification', async () => {
  for (const musicVideoType of ['MUSIC_VIDEO_TYPE_ATV', 'MUSIC_VIDEO_TYPE_OMV', 'MUSIC_VIDEO_TYPE_UGC', '']) {
    let requestedId;
    const result = await resolvePlayback({ track: { ...track, type: 'track', musicVideoType }, session }, async (_url, init) => {
      requestedId = JSON.parse(init.body).videoId;
      return success();
    }, async () => player);
    assert.equal(requestedId, track.id);
    assert.equal(result.contentLength, 42);
  }
});

test('video playlist items play their selected ID when the actual duration is within five seconds', async () => {
  for (const duration of [115, 120, 125]) {
    let requestedId;
    const result = await resolvePlayback({ track: { ...track, type: 'video', duration: '2:00' }, session }, async (_url, init) => {
      requestedId = JSON.parse(init.body).videoId;
      return success(duration);
    }, async () => player);
    assert.equal(requestedId, track.id);
    assert.equal(result.durationSeconds, duration);
    assert.equal(result.contentLength, 42);
  }
});

test('music-video audio rejects a six-minute source for a two-minute song before opening the stream', async () => {
  for (const source of [
    { type: 'video' },
    { type: 'track', musicVideoType: 'MUSIC_VIDEO_TYPE_OMV' },
    { type: 'track', musicVideoType: 'MUSIC_VIDEO_TYPE_UGC' },
    { type: 'track', musicVideoAudioFallback: true, fallbackTargetDurationSeconds: 120, durationSeconds: 360 }
  ]) {
    for (const actualDuration of [126, 360]) {
      await assert.rejects(resolve({ track: { ...track, durationSeconds: 120, ...source }, session }, async (url) => {
        assert.match(url, /\/youtubei\/v1\/player/);
        return success(actualDuration);
      }, async () => player), /more than five seconds/);
    }
  }
});

test('music-video duration verification requires the player length and a fallback song duration', async () => {
  await assert.rejects(resolvePlayback({ track: { ...track, type: 'video', durationSeconds: 120 }, session }, async () => success(), async () => player), /Unable to verify/);
  await assert.rejects(resolvePlayback({ track: { ...track, musicVideoAudioFallback: true }, session }, async () => success(120), async () => player), /Unable to verify/);
});

test('video-backed album songs resolve matching audio before requesting the longer music video', async () => {
  const selected = { id: 'original123', type: 'video', musicVideoType: 'MUSIC_VIDEO_TYPE_OMV',
    title: 'Scream', artist: 'Michael Jackson', album: 'Scream', duration: '4:38', durationSeconds: 278 };
  const requestedIds = [];
  const result = await resolvePlayback({ track: selected, session }, async (url, init) => {
    const body = JSON.parse(init.body);
    if (url.includes('/search?')) {
      assert.equal(body.query, 'Scream Michael Jackson');
      assert.equal(body.params, 'EgWKAQIIAQ%3D%3D');
      const candidates = [
        ['toolong1234', 'Scream', 'Michael Jackson', '6:00'],
        ['wrongart123', 'Scream', 'Another Artist', '4:38'],
        ['liveremix12', 'Scream (Live)', 'Michael Jackson', '4:38'],
        ['albumaudio1', 'Scream', 'Michael Jackson', '4:38']
      ].map(([id, title, artist, duration]) => ({ musicResponsiveListItemRenderer: {
        playlistItemData: { videoId: id },
        flexColumns: [
          { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: title, navigationEndpoint: {
            watchEndpoint: { videoId: id, watchEndpointMusicSupportedConfigs: {
              watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' }
            } }
          } }] } } },
          { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [
            { text: artist, navigationEndpoint: { browseEndpoint: { browseId: 'UCartist' } } },
            { text: ' • ' }, { text: duration }
          ] } } }
        ]
      } }));
      return response({ contents: { tabbedSearchResultsRenderer: { tabs: [{ tabRenderer: {
        content: { sectionListRenderer: { contents: [{ musicShelfRenderer: { contents: candidates } }] } }
      } }] } } });
    }
    requestedIds.push(body.videoId);
    return success(body.videoId === 'albumaudio1' ? 278 : 360);
  }, async () => player, async (id, refresh) => {
    assert.equal(id, 'albumaudio1');
    assert.equal(refresh, false);
    return 'album-audio-token';
  });
  assert.deepEqual(requestedIds, ['albumaudio1']);
  assert.equal(result.youtubeVideoId, 'albumaudio1');
  assert.equal(result.durationSeconds, 278);
});

test('music videos resolve a muxed format through the same signed-in player and token', async () => {
  let tokenVideo;
  const result = await resolveVideoPlayback({ track: { id: 'abcdefghijk', type: 'video' }, session, streamQuality: 'normal' },
    async (url, init) => {
      if (url.startsWith('https://example.googlevideo.com/')) {
        assert.equal(init.headers.Range, 'bytes=0-0');
        return { ok: true, status: 206, headers: { 'content-range': 'bytes 0-0/9876' } };
      }
      assert.match(url, /^https:\/\/music.youtube.com\/youtubei\/v1\/player/);
      return response({ videoDetails: { lengthSeconds: 200 }, playabilityStatus: { status: 'OK' }, streamingData: {
        formats: [
          { itag: 22, mimeType: 'video/mp4; codecs="avc1.64001F, mp4a.40.2"', height: 720, signatureCipher: 'hd' },
          { itag: 18, mimeType: 'video/mp4; codecs="avc1.42001E, mp4a.40.2"', height: 360, signatureCipher: 'sd' }
        ],
        adaptiveFormats: [{ itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', signatureCipher: 'audio' }]
      } });
    },
    async () => ({ ...player, decipher: async (url, cipher) => `https://example.googlevideo.com/${cipher}` }),
    async (videoId) => { tokenVideo = videoId; return 'token'; });
  assert.equal(tokenVideo, 'abcdefghijk');
  assert.equal(result.itag, 22);
  assert.equal(result.url, 'https://example.googlevideo.com/hd?pot=token');
  assert.equal(result.contentLength, 9876);
});

test('music videos skip the length probe when the player reports it', async () => {
  const result = await resolveVideoPlayback({ track: { id: 'abcdefghijk', type: 'video' }, session },
    async (url) => {
      assert.match(url, /^https:\/\/music.youtube.com\/youtubei\/v1\/player/);
      return response({ videoDetails: { lengthSeconds: 200 }, playabilityStatus: { status: 'OK' }, streamingData: {
        formats: [{ itag: 18, mimeType: 'video/mp4; codecs="avc1.42001E, mp4a.40.2"', height: 360,
          contentLength: '5555', url: 'https://example.googlevideo.com/sd' }]
      } });
    },
    async () => player, async () => '');
  assert.equal(result.contentLength, 5555);
});

test('desktop video requests resolve the picture and its own soundtrack separately', async () => {
  const probed = [];
  const result = await resolveVideoPlayback({ track: { id: 'abcdefghijk', type: 'video' }, session, maxHeight: 1080 },
    async (url, init) => {
      if (url.startsWith('https://example.googlevideo.com/')) {
        probed.push(url);
        assert.equal(init.headers.Range, 'bytes=0-0');
        return { ok: true, status: 206, headers: { 'content-range': 'bytes 0-0/777' } };
      }
      return response({ videoDetails: { lengthSeconds: 245 }, playabilityStatus: { status: 'OK' }, streamingData: {
        adaptiveFormats: [
          { itag: 248, mimeType: 'video/webm; codecs="vp9"', height: 1440, signatureCipher: 'tall' },
          { itag: 137, mimeType: 'video/mp4; codecs="avc1.640028"', height: 1080, contentLength: '9000', signatureCipher: 'hd' },
          { itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 130000, signatureCipher: 'sound' }
        ]
      } });
    },
    async () => ({ ...player, decipher: async (url, cipher) => `https://example.googlevideo.com/${cipher}` }),
    async () => '');
  assert.equal(result.itag, 137);
  assert.equal(result.height, 1080);
  assert.deepEqual(result.heights, [1440, 1080]);
  assert.equal(result.contentLength, 9000);
  assert.equal(result.audio.itag, 140);
  assert.equal(result.audio.url, 'https://example.googlevideo.com/sound');
  assert.equal(result.audio.contentLength, 777);
  assert.equal(result.audio.durationSeconds, 245);
  assert.deepEqual(probed, ['https://example.googlevideo.com/sound']);
});
