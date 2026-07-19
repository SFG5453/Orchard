import test from 'node:test';
import assert from 'node:assert/strict';
import { audioRecoveryPlan, videoRecoveryPlan } from '../src/app/playback/audioRecovery.js';
import { installPlaybackRecoveryActions } from '../src/app/playback/playbackRecoveryActions.js';

const track = {
  id: 'track-1',
  mediaKind: 'audio',
  itag: 140,
  mimeType: 'audio/mp4; codecs="mp4a.40.2"'
};

test('alternate stream recovery avoids only the failed itag by default', () => {
  const recovery = audioRecoveryPlan(track, { avoidCurrentFormat: true });

  assert.deepEqual(recovery.avoidItags, ['140']);
  assert.deepEqual(recovery.avoidMimeTypes, []);
});

test('source format errors can reject the failed MIME family', () => {
  const recovery = audioRecoveryPlan(track, {
    avoidCurrentFormat: true,
    avoidCurrentMimeType: true
  });

  assert.deepEqual(recovery.avoidItags, ['140']);
  assert.deepEqual(recovery.avoidMimeTypes, ['audio/mp4']);
});

test('duplicate stall recovery preserves the queue and reuses one request', async () => {
  let finishPlayback;
  const calls = [];
  const ctx = {
    activeTrack: { value: track },
    playTrack(recoveryTrack, options) {
      calls.push({ recoveryTrack, options });
      return new Promise((resolve) => { finishPlayback = resolve; });
    }
  };
  installPlaybackRecoveryActions(ctx);

  const first = ctx.retryAudioStream(track, { refreshStream: true });
  const duplicate = ctx.retryAudioStream(track, { refreshStream: true });

  assert.equal(duplicate, first);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.preserveQueue, true);
  assert.equal('queueSource' in calls[0].options, false);

  finishPlayback();
  await first;
});

test('video recovery rejects the failed itag while preserving playback state', () => {
  const recovery = videoRecoveryPlan({
    id: 'video-1',
    mediaKind: 'video',
    itag: 137
  }, { avoidCurrentFormat: true });

  assert.deepEqual(recovery.avoidItags, ['137']);
  assert.deepEqual(recovery.track.failedVideoItags, ['137']);
  assert.equal(recovery.track.playbackFallbackTried, true);
});
