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
  createPreferredAudioTrack,
  playbackAudioBitrate,
  rawPlayableAudioFormats
} from '../src/playback/playbackFormats.js';

test('selects YouTube HE-AAC audio when Chromium reports support', () => {
  const heAac = { itag: 139, mime_type: 'audio/mp4; codecs="mp4a.40.5"', bitrate: 48_000 };

  assert.equal(chooseAudioFormatFromFormats([heAac], [
    { mimeType: 'audio/mp4; codecs="mp4a.40.5"', support: 'probably' }
  ]), heAac);
});

test('does not report a browser codec mismatch when InnerTube returned no formats', () => {
  assert.equal(chooseAudioFormatFromFormats([], [
    { mimeType: 'audio/mp4; codecs="mp4a.40.2"', support: 'probably' }
  ]), undefined);
});

test('reports the audio companion bitrate for video playback', () => {
  const stream = {
    format: { bitrate: 17_852_000 },
    audioFormat: { bitrate: 256_000 }
  };

  assert.equal(playbackAudioBitrate(stream, 'video'), 256_000);
});

test('does not present a muxed video bitrate as an audio bitrate', () => {
  const stream = { format: { bitrate: 17_852_000 } };

  assert.equal(playbackAudioBitrate(stream, 'video'), 0);
});

test('continues to report the selected format bitrate for audio playback', () => {
  const stream = { format: { bitrate: 128_000 } };

  assert.equal(playbackAudioBitrate(stream, 'audio'), 128_000);
});

test('keeps the account-owned video ID for an uploaded track', async () => {
  let searches = 0;
  const preferredAudioTrack = createPreferredAudioTrack({
    normalizedLookupText: (value) => String(value).toLowerCase(),
    shelfItems: (items) => items
  });
  const yt = { music: { search: async () => {
    searches += 1;
    return { songs: [] };
  } } };

  const selected = await preferredAudioTrack(yt, {
    videoId: 'private-upload-id',
    title: 'Uploaded song',
    artist: 'Local artist',
    isUpload: true,
    preferAudioOnly: true
  });

  assert.equal(selected, 'private-upload-id');
  assert.equal(searches, 0);
});

test('selects another exact audio-only ID after an age gate', async () => {
  const preferredAudioTrack = createPreferredAudioTrack({
    normalizedLookupText: (value) => String(value).toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(),
    shelfItems: (items) => items
  });
  const yt = { music: { search: async () => ({ songs: [
    { id: 'gated-id', title: 'Fuck Ya!', artist: 'YoungBoy Never Broke Again', album: 'Top', duration: '3:05', explicit: true, isAudioOnly: true },
    { id: 'audio-id', title: 'Fuck Ya!', artist: 'YoungBoy Never Broke Again', album: 'Top', duration: '3:05', isAudioOnly: true }
  ] }) } };

  const selected = await preferredAudioTrack(yt, {
    videoId: 'gated-id',
    excludedVideoIds: ['gated-id'],
    title: 'Fuck Ya!',
    artist: 'YoungBoy Never Broke Again',
    album: 'Top',
    durationSeconds: 185,
    explicit: true,
    musicVideoType: 'MUSIC_VIDEO_TYPE_ATV',
    isAudioOnly: true,
    preferAudioOnly: true,
    retryAlternateAudio: true
  });

  assert.equal(selected, 'audio-id');
});

test('rawPlayableAudioFormats preserves cipher properties and ignores video streams', () => {
  const formats = [
    { itag: 18, mimeType: 'video/mp4; codecs="avc1.42001E, mp4a.40.2"', bitrate: 96000, signatureCipher: 'cipher18' },
    { itag: 251, mimeType: 'audio/webm; codecs="opus"', bitrate: 160000, signatureCipher: 'cipher251' },
    { itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 128000, url: 'https://example.com/140' }
  ];
  const audio = rawPlayableAudioFormats(formats);
  assert.equal(audio.length, 2);
  assert.equal(audio[0].itag, 251);
  assert.equal(audio[0].signatureCipher, 'cipher251');
  assert.equal(audio[1].itag, 140);
  assert.equal(audio[1].url, 'https://example.com/140');
});

test('selects appropriate audio formats across quality tiers', () => {
  const raw = [
    { itag: 251, mimeType: 'audio/webm; codecs="opus"', bitrate: 160000, url: 'https://example.com/251' },
    { itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 128000, url: 'https://example.com/140' },
    { itag: 250, mimeType: 'audio/webm; codecs="opus"', bitrate: 70000, url: 'https://example.com/250' },
    { itag: 249, mimeType: 'audio/webm; codecs="opus"', bitrate: 50000, url: 'https://example.com/249' },
    { itag: 139, mimeType: 'audio/mp4; codecs="mp4a.40.5"', bitrate: 48000, url: 'https://example.com/139' }
  ];
  const audio = rawPlayableAudioFormats(raw);

  // High tier selects highest bitrate (160 kbps Opus)
  const high = chooseAudioFormatFromFormats(audio, [], { streamQuality: 'high' });
  assert.equal(high.itag, 251);
  assert.equal(high.bitrate, 160000);

  // Default without quality also selects highest bitrate
  const def = chooseAudioFormatFromFormats(audio, []);
  assert.equal(def.itag, 251);

  // Normal tier caps at 140 kbps, picking 128 kbps AAC
  const normal = chooseAudioFormatFromFormats(audio, [], { streamQuality: 'normal' });
  assert.equal(normal.itag, 140);
  assert.equal(normal.bitrate, 128000);

  // Saver tier selects lowest bitrate format (48 kbps AAC)
  const saver = chooseAudioFormatFromFormats(audio, [], { streamQuality: 'saver' });
  assert.equal(saver.itag, 139);
  assert.equal(saver.bitrate, 48000);

  // Restricting codecs keeps saver off HE-AAC and on the thriftiest Opus
  const restricted = chooseAudioFormatFromFormats(audio, [
    { mimeType: 'audio/webm; codecs="opus"', support: 'probably' },
    { mimeType: 'audio/mp4; codecs="mp4a.40.2"', support: 'probably' }
  ], { streamQuality: 'saver' });
  assert.equal(restricted.itag, 249);
});
