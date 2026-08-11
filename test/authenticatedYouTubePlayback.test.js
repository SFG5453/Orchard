/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createAuthenticatedYouTubePlayback,
  decipherHlsManifestUrl,
  hlsMimeType
} from '../electron/playback/authenticatedYouTubePlayback.js';
import { rewriteHlsManifest } from '../electron/playback/streamProxy.js';

test('authenticated WEB_REMIX uses one live player for timestamp and deciphering', async () => {
  let requestBody;
  let decipherArgs;
  const player = {
    signature_timestamp: 20672,
    decipher: async (...args) => {
      decipherArgs = args;
      return 'https://rr1.googlevideo.com/videoplayback?expire=2000000000&sig=solved';
    }
  };
  const playback = createAuthenticatedYouTubePlayback({
    authState: {
      browser: {
        accountIndex: 2,
        cookie: '__Secure-3PAPISID=three',
        dataSyncId: 'channel',
        visitorData: 'visitor'
      }
    },
    cookieWithPlaybackDefaults: (cookie) => cookie,
    fetchImpl: async (_url, init) => {
      requestBody = JSON.parse(init.body);
      assert.equal(init.headers['X-Youtube-Bootstrap-Logged-In'], 'true');
      assert.match(init.headers.Authorization, /^SAPISIDHASH /);
      assert.match(init.headers.Authorization, / SAPISID3PHASH /);
      return new Response(JSON.stringify({
        playabilityStatus: { status: 'OK' },
        streamingData: {
          formats: [{
            itag: 18,
            mimeType: 'video/mp4; codecs="avc1.42001E, mp4a.40.2"',
            bitrate: 256000,
            signatureCipher: 'url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback&s=encrypted&sp=sig'
          }]
        }
      }), { status: 200 });
    },
    getBrowserInnertube: async () => ({ session: { player } }),
    hasBrowserLoginCookie: () => true,
    refreshBrowserAuth: async () => {},
    youtubeMusicClientUserAgent: 'music-agent',
    youtubeMusicClientVersion: '1.20260213.01.00',
    youtubeMusicOrigin: 'https://music.youtube.com',
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  const stream = await playback.resolveDirect('video-id');

  assert.equal(requestBody.playbackContext.contentPlaybackContext.signatureTimestamp, 20672);
  assert.deepEqual(decipherArgs, [
    undefined,
    'url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback&s=encrypted&sp=sig',
    undefined
  ]);
  assert.equal(stream.format.itag, 18);
  assert.equal(stream.authenticated, true);
});

test('a rejected timestamp refreshes the player before retrying and deciphering', async () => {
  const timestamps = [];
  const decipheredBy = [];
  let forceRefreshes = 0;
  let requests = 0;
  let activePlayer = {
    signature_timestamp: 20000,
    decipher: async () => {
      decipheredBy.push('old');
      return 'https://rr1.googlevideo.com/old';
    }
  };
  const newPlayer = {
    signature_timestamp: 20672,
    decipher: async () => {
      decipheredBy.push('new');
      return 'https://rr1.googlevideo.com/videoplayback?expire=2000000000';
    }
  };
  const playback = createAuthenticatedYouTubePlayback({
    authState: { browser: { cookie: 'SAPISID=secret' } },
    cookieWithPlaybackDefaults: (cookie) => cookie,
    fetchImpl: async (_url, init) => {
      timestamps.push(JSON.parse(init.body).playbackContext.contentPlaybackContext.signatureTimestamp);
      requests += 1;
      if (requests === 1) {
        return new Response(JSON.stringify({
          playabilityStatus: { status: 'ERROR', reason: 'This page needs to be reloaded' }
        }), { status: 200 });
      }
      return new Response(JSON.stringify({
        playabilityStatus: { status: 'OK' },
        streamingData: { formats: [{ itag: 18, url: 'https://rr1.googlevideo.com/raw' }] }
      }), { status: 200 });
    },
    getBrowserInnertube: async () => ({ session: { player: activePlayer } }),
    hasBrowserLoginCookie: () => true,
    refreshBrowserAuth: async (_contents, options = {}) => {
      if (options.forceAccountRefresh) {
        forceRefreshes += 1;
        activePlayer = newPlayer;
      }
    },
    youtubeMusicClientUserAgent: 'music-agent',
    youtubeMusicClientVersion: '1.20260213.01.00',
    youtubeMusicOrigin: 'https://music.youtube.com',
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  await playback.resolveDirect('video-id');

  assert.deepEqual(timestamps, [20000, 20672]);
  assert.deepEqual(decipheredBy, ['new']);
  assert.equal(forceRefreshes, 1);
});

test('Safari HLS path challenge is solved by the active player', async () => {
  const player = {
    decipher: async (url) => {
      const solved = new URL(url);
      solved.searchParams.set('n', 'solved-n');
      return solved.toString();
    }
  };
  const manifest = await decipherHlsManifestUrl(
    player,
    'https://manifest.googlevideo.com/api/manifest/hls_variant/n/challenge/playlist.m3u8'
  );

  assert.equal(
    manifest,
    'https://manifest.googlevideo.com/api/manifest/hls_variant/n/solved-n/playlist.m3u8'
  );
});

test('HLS manifests route playlists, keys, and segments through the local proxy', () => {
  const rewritten = rewriteHlsManifest([
    '#EXTM3U',
    '#EXT-X-KEY:METHOD=AES-128,URI="https://rr1.googlevideo.com/key?id=1"',
    'audio/playlist.m3u8',
    'https://rr2.googlevideo.com/segment.ts?x=1'
  ].join('\n'), 'https://manifest.googlevideo.com/root/master.m3u8');

  assert.match(rewritten, /URI="\/hls\?url=/);
  assert.match(rewritten, /\/hls\?url=https%3A%2F%2Fmanifest\.googlevideo\.com%2Froot%2Faudio%2Fplaylist\.m3u8/);
  assert.match(rewritten, /\/hls\?url=https%3A%2F%2Frr2\.googlevideo\.com%2Fsegment\.ts%3Fx%3D1/);
  assert.equal(hlsMimeType, 'application/x-mpegURL');
});

test('HLS manifest rewriting rejects non-YouTube media hosts', () => {
  assert.throws(
    () => rewriteHlsManifest('#EXTM3U\nhttps://example.com/segment.ts', 'https://manifest.googlevideo.com/master.m3u8'),
    /unexpected HLS host/
  );
});
