import assert from 'node:assert/strict';
import test from 'node:test';
import {
  installPlaybackResolve,
  playbackQueueSourceMatches,
  seedsPlaylistContext
} from '../src/app/playback/playbackResolve.js';

function playbackContext() {
  const ctx = {
    autoCrossfade: { isActive: () => false },
    isPlayableTrack: (item) => Boolean(item?.id),
    nextPreloadRequest: 0,
    nextTrackPreload: { value: null },
    resetNextCrossfadeAnalysis() {},
    standbyAudio: () => null,
    supportedAudioMimes: () => [],
    supportedVideoMimes: () => [],
    wsolaCrossfade: { isActive: () => false }
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

test('does not clear the transition-owned standby deck during a WSOLA overlap', () => {
  const ctx = playbackContext();
  const preload = {
    track: { id: 'incoming' },
    resolved: { streamUrl: 'https://example.test/incoming' }
  };
  let cleared = 0;
  ctx.nextTrackPreload.value = preload;
  ctx.standbyAudio = () => ({});
  ctx.clearAudioElement = () => {
    cleared += 1;
  };
  ctx.wsolaCrossfade.isActive = () => true;

  assert.equal(ctx.clearNextPreload({ force: true }), false);
  assert.equal(ctx.nextTrackPreload.value, preload);
  assert.equal(ctx.nextPreloadRequest, 0);
  assert.equal(cleared, 0);
});

test('does not replace the transition-owned standby source after a queue edit', async () => {
  const ctx = playbackContext();
  ctx.queue = { value: [{ id: 'new-queue-head' }] };
  ctx.wsolaCrossfade.isActive = () => true;

  assert.equal(await ctx.preloadNextTrack({ force: true }), false);
  assert.equal(ctx.nextPreloadRequest, 0);
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

test('shuffling a collection seeds a playlist context despite looking like a queue click', () => {
  // playCollection sets queueAlreadyShuffled so its chosen order survives, and
  // that alone makes isPlayFromQueue true. Reading it as a click inside the
  // queue left the playlist with no context, so nothing could page past the
  // first hundred tracks.
  assert.equal(seedsPlaylistContext({ isPlayFromQueue: true, queueAlreadyShuffled: true }), true);
});

test('clicking a track already in the queue leaves the playlist context alone', () => {
  assert.equal(seedsPlaylistContext({ isPlayFromQueue: true, queueAlreadyShuffled: false }), false);
});

test('playing a collection outright always seeds a context', () => {
  assert.equal(seedsPlaylistContext({ isPlayFromQueue: false, queueAlreadyShuffled: false }), true);
  assert.equal(seedsPlaylistContext({ isPlayFromQueue: false, queueAlreadyShuffled: true }), true);
});

test('a shuffled collection play is recognized as already-shuffled, not as a fresh source', () => {
  // The real signal chain: playCollection hands playTrack the shuffled order as
  // queueSource while the queue is still empty, so only the explicit flag can
  // distinguish it.
  const shuffledOrder = [{ id: 'c' }, { id: 'a' }, { id: 'b' }];
  assert.equal(playbackQueueSourceMatches(shuffledOrder, [], null), false);
  assert.equal(
    seedsPlaylistContext({
      isPlayFromQueue: Boolean(true || playbackQueueSourceMatches(shuffledOrder, [], null)),
      queueAlreadyShuffled: true
    }),
    true
  );
});
