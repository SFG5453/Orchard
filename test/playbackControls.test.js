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
});
