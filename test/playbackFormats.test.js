import assert from 'node:assert/strict';
import test from 'node:test';
import {
  chooseAudioFormatFromFormats,
  createPreferredAudioTrack,
  playbackAudioBitrate
} from '../electron/playback/playbackFormats.js';

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
