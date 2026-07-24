import assert from 'node:assert/strict';
import test from 'node:test';
import {
  installPlaybackResolve,
  playbackQueueSourceMatches
} from '../src/app/playback/playbackResolve.js';

function playbackContext() {
  const ctx = {
    isPlayableTrack: (item) => Boolean(item?.id),
    supportedAudioMimes: () => [],
    supportedVideoMimes: () => []
  };
  installPlaybackResolve(ctx);
  return ctx;
}

test('resolves an established music-video fallback by its video ID', () => {
  const ctx = playbackContext();
  const payload = ctx.trackResolvePayload({
    id: 'song-id',
    title: 'Age-gated song',
    mediaKind: 'video',
    musicVideoAudioFallback: true,
    musicVideoFallbackId: 'video-id',
    fallbackTargetDurationSeconds: 180
  });

  assert.equal(payload.videoId, 'video-id');
  assert.equal(payload.originalVideoId, 'song-id');
  assert.equal(payload.musicVideoAudioFallback, true);
  assert.equal(payload.fallbackTargetDurationSeconds, 180);
});

test('keeps song identity while preserving resolved fallback stream metadata', () => {
  const ctx = playbackContext();
  const active = ctx.activeTrackFromResolved(
    { id: 'song-id', title: 'Age-gated song', artists: ['The Artist'] },
    {
      id: 'song-id',
      youtubeVideoId: 'video-id',
      mediaKind: 'video',
      streamUrl: 'https://example.test/video',
      musicVideoAudioFallback: true,
      musicVideoFallbackId: 'video-id'
    }
  );

  assert.equal(active.id, 'song-id');
  assert.equal(active.youtubeVideoId, 'video-id');
  assert.equal(active.musicVideoAudioFallback, true);
  assert.equal(active.musicVideoFallbackId, 'video-id');
});

test('recognizes a persisted active track followed by its saved queue', () => {
  const activeTrack = { id: 'active' };
  const queue = [{ id: 'second' }, { id: 'third' }];

  assert.equal(
    playbackQueueSourceMatches([activeTrack, ...queue], queue, activeTrack),
    true
  );
  assert.equal(
    playbackQueueSourceMatches([activeTrack, queue[1], queue[0]], queue, activeTrack),
    false
  );
});
