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
import {
  chooseAudioFormatFromFormats,
  chooseVideoFormatFromFormats
} from '../electron/playback/playbackFormats.js';
import { createPlaybackStreamCache } from '../electron/playback/playbackStreamCache.js';
import { installPlaybackResolve } from '../src/app/playback/playbackResolve.js';
import { installArtworkService } from '../src/app/appearance/artworkService.js';
import { DEFAULT_STREAM_QUALITY, normalizeStreamQuality } from '../shared/streamQuality.js';

const aacMimes = [{ mimeType: 'audio/mp4; codecs="mp4a.40.2"', support: 'probably' }];
const audioFormats = [
  { itag: 141, mime_type: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 256_000 },
  { itag: 140, mime_type: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 129_000 },
  { itag: 139, mime_type: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 48_000 }
];

const avcMimes = [{ mimeType: 'video/mp4; codecs="avc1.640028"', support: 'probably' }];
const videoFormats = [
  { itag: 137, mime_type: 'video/mp4; codecs="avc1.640028"', height: 1080, bitrate: 4_000_000 },
  { itag: 136, mime_type: 'video/mp4; codecs="avc1.640028"', height: 720, bitrate: 2_000_000 },
  { itag: 135, mime_type: 'video/mp4; codecs="avc1.640028"', height: 480, bitrate: 1_000_000 }
];

function playbackContext(overrides = {}) {
  const ctx = {
    isPlayableTrack: (item) => Boolean(item?.id),
    supportedAudioMimes: () => [],
    supportedVideoMimes: () => [],
    autoCrossfade: { isActive: () => false },
    wsolaCrossfade: { isActive: () => false },
    nextTrackPreload: { value: null },
    resetNextCrossfadeAnalysis() {},
    standbyAudio: () => null,
    nextPreloadRequest: 0,
    ...overrides
  };
  installPlaybackResolve(ctx);
  return ctx;
}

test('an unknown or missing streaming quality keeps the best available streams', () => {
  assert.equal(normalizeStreamQuality(undefined), 'high');
  assert.equal(normalizeStreamQuality('lowest'), 'high');
  assert.equal(DEFAULT_STREAM_QUALITY, 'high');
});

test('the saver tier takes the thriftiest audio format YouTube offers', () => {
  assert.equal(
    chooseAudioFormatFromFormats(audioFormats, aacMimes, { streamQuality: 'saver' }).itag,
    139
  );
});

test('the normal tier stops at the 128 kbps track rather than the high-bitrate one', () => {
  assert.equal(
    chooseAudioFormatFromFormats(audioFormats, aacMimes, { streamQuality: 'normal' }).itag,
    140
  );
});

test('the high tier and an absent quality both keep the best audio format', () => {
  assert.equal(
    chooseAudioFormatFromFormats(audioFormats, aacMimes, { streamQuality: 'high' }).itag,
    141
  );
  assert.equal(chooseAudioFormatFromFormats(audioFormats, aacMimes).itag, 141);
});

test('a tier whose ceiling no format meets still resolves to the smallest one', () => {
  const onlyLarge = [audioFormats[0]];

  assert.equal(
    chooseAudioFormatFromFormats(onlyLarge, aacMimes, { streamQuality: 'normal' }).itag,
    141
  );
});

test('streaming quality caps video resolution as well as audio bitrate', () => {
  assert.equal(chooseVideoFormatFromFormats(videoFormats, avcMimes, { streamQuality: 'saver' }).itag, 135);
  assert.equal(chooseVideoFormatFromFormats(videoFormats, avcMimes, { streamQuality: 'normal' }).itag, 136);
  assert.equal(chooseVideoFormatFromFormats(videoFormats, avcMimes, { streamQuality: 'high' }).itag, 137);
});

test('automatically selected streams are cached per quality tier, explicit formats are not', () => {
  const cache = createPlaybackStreamCache();

  assert.notEqual(
    cache.key('track', { mediaKind: 'audio', streamQuality: 'saver' }),
    cache.key('track', { mediaKind: 'audio', streamQuality: 'high' })
  );
  assert.equal(
    cache.key('track', { mediaKind: 'audio', itag: 140, streamQuality: 'saver' }),
    cache.key('track', { mediaKind: 'audio', itag: 140, streamQuality: 'high' })
  );
});

test('turning music videos off resolves even an explicit video request as audio', () => {
  const ctx = playbackContext({ videoPlaybackEnabled: { value: false }, streamQuality: { value: 'saver' } });
  const payload = ctx.trackResolvePayload({ id: 'video-id', title: 'Official video', type: 'video' }, { mediaKind: 'video' });

  assert.equal(payload.mediaKind, 'audio');
  assert.equal(payload.preferAudioOnly, true);
  assert.equal(payload.streamQuality, 'saver');
  assert.equal(ctx.trackHasVideoVersion({ id: 'video-id', type: 'video' }), false);
});

test('music videos still resolve as video while the setting is on', () => {
  const ctx = playbackContext({ videoPlaybackEnabled: { value: true }, streamQuality: { value: 'high' } });
  const payload = ctx.trackResolvePayload({ id: 'video-id', title: 'Official video', type: 'video' }, { mediaKind: 'video' });

  assert.equal(payload.mediaKind, 'video');
  assert.equal(payload.streamQuality, 'high');
});

function artworkContext(animatedArtworkEnabled) {
  const ctx = {
    animatedArtworkEnabled: { value: animatedArtworkEnabled },
    artworkApiProviders: [
      { id: 'm8tec', baseUrl: 'https://artwork.m8tec.top/' },
      { id: 'boidu', baseUrl: 'https://artwork.boidu.dev/' },
      { id: 'spotify', baseUrl: 'https://spclient.wg.spotify.com/' },
      { id: 'orchard', baseUrl: 'https://artwork.sfg545.dev/' }
    ]
  };
  installArtworkService(ctx);
  return ctx;
}

test('turning animated artwork off keeps the still cover and drops the motion sources', async () => {
  const ctx = artworkContext(false);
  const artwork = await ctx.normalizeEnhancedArtwork({
    name: 'Song',
    static: 'https://example.test/cover.jpg',
    animated: 'https://example.test/cover.m3u8',
    videoUrl: 'https://example.test/cover.mp4'
  });

  assert.equal(artwork.static, 'https://example.test/cover.jpg');
  assert.equal(artwork.animated, '');
  assert.equal(artwork.videoUrl, '');
});

test('a motion-only artwork result is dropped rather than kept as an empty cover', async () => {
  const ctx = artworkContext(false);

  assert.equal(await ctx.normalizeEnhancedArtwork({ animated: 'https://example.test/cover.m3u8' }), null);
});

test('providers that only ever answer with motion artwork are not asked at all', () => {
  const ctx = artworkContext(false);
  const asked = ctx.artworkApiProviders.filter((provider) => ctx.animatedArtworkProviderAllowed(provider));

  assert.deepEqual(asked.map((provider) => provider.id), ['boidu', 'orchard']);
  assert.deepEqual(
    artworkContext(true).artworkApiProviders.filter((provider) => artworkContext(true).animatedArtworkProviderAllowed(provider)).map((provider) => provider.id),
    ['m8tec', 'boidu', 'spotify', 'orchard']
  );
});
