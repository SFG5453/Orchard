import assert from 'node:assert/strict';
import test from 'node:test';
import { installMediaHandlers } from '../src/app/playback/mediaHandlers.js';
import { installPlaybackControls, playbackNeedsFreshStream } from '../src/app/playback/playbackControls.js';

test('paused playback leaves buffering so the play control is usable again', () => {
  let stallRecoveryCleared = false;
  const media = {};
  const ctx = {
    activeTrackIsVideo: { value: false },
    buffering: { value: true },
    currentPlaybackElement: () => media,
    isCurrentAudioEvent: (event) => event.target === media,
    isPlaying: { value: true },
    reportYouTubeHistoryProgress: () => {},
    videoAudioRef: { value: null }
  };

  installMediaHandlers(ctx);
  ctx.clearPlaybackStallRecovery = () => { stallRecoveryCleared = true; };
  ctx.onAudioPause({ target: media });

  assert.equal(stallRecoveryCleared, true);
  assert.equal(ctx.buffering.value, false);
  assert.equal(ctx.isPlaying.value, false);
});

test('ended, failed, and source-less media require a fresh stream', () => {
  assert.equal(playbackNeedsFreshStream({ ended: true }), true);
  assert.equal(playbackNeedsFreshStream({ error: { code: 2 } }), true);
  assert.equal(playbackNeedsFreshStream({ networkState: 3 }), true);
  assert.equal(playbackNeedsFreshStream({ paused: true, networkState: 1 }), false);
  assert.equal(playbackNeedsFreshStream({ paused: true }, 'Playback stalled'), true);
});

test('restarting ended video playback refreshes the active media kind', () => {
  const calls = [];
  const media = { ended: true, src: 'http://127.0.0.1/expired' };
  const track = { id: 'video-1' };
  const ctx = {
    activeMediaKind: { value: 'video' },
    activeTrack: { value: track },
    currentPlaybackElement: () => media,
    isPlaying: { value: false },
    listeningParty: { value: { status: 'offline' } },
    listeningPartyIsHost: { value: true },
    playbackError: { value: '' },
    playTrack: (item, options) => calls.push({ item, options }),
    queue: { value: [] }
  };

  installPlaybackControls(ctx);
  ctx.togglePlayback();

  assert.equal(calls.length, 1);
  assert.equal(calls[0].item, track);
  assert.equal(calls[0].options.mediaKind, 'video');
  assert.equal(calls[0].options.refreshStream, true);
  assert.equal(calls[0].options.queueAlreadyShuffled, false);
});

test('advancing past an unavailable track removes it and plays the following queue item', async () => {
  const unavailable = { id: 'unavailable-track' };
  const playable = { id: 'playable-track' };
  const played = [];
  const ctx = {
    activeTrack: { value: { id: 'current-track' } },
    autoplayEnabled: { value: false },
    clearNextPreload: () => {},
    nextTrackPreload: { value: null },
    playbackError: { value: '' },
    preloadedTrackMatches: () => false,
    queue: { value: [unavailable, playable] },
    removeUnavailableQueueTrack(track) {
      this.queue.value = this.queue.value.filter((item) => item.id !== track.id);
    },
    repeatMode: { value: 'off' },
    resolvePlayableTrack: async (track) => {
      if (track.id === unavailable.id) throw new Error('No playable audio format was returned by YouTube');
      return { id: track.id, streamUrl: 'https://example.test/stream' };
    },
    shuffleEnabled: { value: false },
    playTrack: (track, options) => played.push({ track, options })
  };

  installPlaybackControls(ctx);
  await ctx.playNext({ fromEnded: true });

  assert.deepEqual(ctx.queue.value, [playable]);
  assert.equal(played.length, 1);
  assert.equal(played[0].track, playable);
  assert.deepEqual(played[0].options.queueSource, [playable]);
  assert.equal(played[0].options.resolved.id, playable.id);
});

test('repeat queue wraps from the final playlist track to the first track', async () => {
  const first = { id: 'first-track' };
  const middle = { id: 'middle-track' };
  const final = { id: 'final-track' };
  const played = [];
  const ctx = {
    activeTrack: { value: final },
    autoplayEnabled: { value: false },
    clearNextPreload() {},
    history: { value: [] },
    isPlayableTrack: (track) => Boolean(track?.id),
    nextTrackPreload: { value: null },
    playbackError: { value: '' },
    playbackPlaylistContext: {
      value: {
        allTracks: [first, middle, final],
        browseId: 'playlist-id'
      }
    },
    preloadedTrackMatches: () => false,
    queue: { value: [] },
    repeatMode: { value: 'queue' },
    resolvePlayableTrack: async (track) => ({
      id: track.id,
      streamUrl: `https://example.test/${track.id}`
    }),
    shuffleEnabled: { value: true },
    shuffleItems: (tracks) => [...tracks],
    playTrack: (track, options) => played.push({ track, options })
  };

  installPlaybackControls(ctx);
  await ctx.playNext({ fromEnded: true });

  assert.equal(played.length, 1);
  assert.equal(played[0].track, first);
  assert.deepEqual(played[0].options.queueSource, [first, middle, final]);
  assert.equal(played[0].options.queueAlreadyShuffled, true);
  assert.equal(played[0].options.resetHistory, true);
  assert.equal(played[0].options.resetPlaylistCycle, true);
});

test('seeking cancels an active transition before moving the current deck', () => {
  const media = { currentTime: 105 };
  let canceled = 0;
  const ctx = {
    activeTrackIsLive: { value: false },
    applyingListeningPartyState: false,
    currentPlaybackElement: () => media,
    currentTime: { value: 105 },
    duration: { value: 180 },
    isPlaying: { value: true },
    listeningParty: { value: { status: 'offline' } },
    listeningPartyIsHost: { value: true },
    queueDiscordPresenceSync() {},
    seekPosition: { value: 105 },
    syncVideoCompanionAudio() {}
  };

  installPlaybackControls(ctx);
  ctx.cancelActiveCrossfade = () => {
    canceled += 1;
  };
  ctx.seek(60);

  assert.equal(canceled, 1);
  assert.equal(media.currentTime, 60);
  assert.equal(ctx.currentTime.value, 60);
});

test('resuming a persisted shuffled queue preserves its saved order', () => {
  const calls = [];
  const track = { id: 'saved-track' };
  const ctx = {
    activeMediaKind: { value: 'audio' },
    activeTrack: { value: track },
    currentPlaybackElement: () => null,
    isPlaying: { value: false },
    listeningParty: { value: { status: 'offline' } },
    listeningPartyIsHost: { value: true },
    playbackError: { value: '' },
    playTrack: (item, options) => calls.push({ item, options }),
    queue: { value: [{ id: 'second' }, { id: 'third' }] },
    shuffleEnabled: { value: true }
  };

  installPlaybackControls(ctx);
  ctx.togglePlayback();

  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].options.queueSource.map((item) => item.id), [
    'saved-track',
    'second',
    'third'
  ]);
  assert.equal(calls[0].options.queueAlreadyShuffled, true);
});

test('pausing the current deck cancels an in-progress transition', () => {
  const media = {};
  let canceled = 0;
  const ctx = {
    activeTrackIsVideo: { value: false },
    autoCrossfade: { isActive: () => true },
    buffering: { value: true },
    cancelActiveCrossfade: () => {
      canceled += 1;
    },
    currentPlaybackElement: () => media,
    isCurrentAudioEvent: (event) => event.target === media,
    isPlaying: { value: true },
    reportYouTubeHistoryProgress: () => {},
    videoAudioRef: { value: null }
  };

  installMediaHandlers(ctx);
  ctx.clearPlaybackStallRecovery = () => {};
  ctx.onAudioPause({ target: media });

  assert.equal(canceled, 1);
  assert.equal(ctx.isPlaying.value, false);
});

test('playing from history does not enqueue the rest of listening history', () => {
  const played = [];
  const selected = { id: 'selected' };
  const ctx = {
    playTrack: (track, options) => played.push({ track, options })
  };

  installPlaybackControls(ctx);
  ctx.playHistoryTrack(selected);

  assert.deepEqual(played, [{
    track: selected,
    options: { queueSource: [selected] }
  }]);
});
