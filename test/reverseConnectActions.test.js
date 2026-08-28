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
import { ref } from 'vue';

import { installConnectActions } from '../src/app/platform/connectActions.js';
import { installPlaybackControls } from '../src/app/playback/playbackControls.js';

function createMockSocket() {
  const listeners = new Map();
  const emitted = [];
  return {
    connected: true,
    emitted,
    listeners,
    on(event, handler) {
      listeners.set(event, handler);
    },
    emit(event, payload) {
      emitted.push({ event, payload });
    }
  };
}

function createMockContext() {
  const socket = createMockSocket();
  const ctx = {
    socket: ref(socket),
    socketState: ref('connected'),
    activePlaybackTarget: ref('local'),
    remoteDeviceStates: ref({}),
    orchardConnect: ref({
      status: 'idle',
      devices: [
        { id: 'phone-1', name: 'Pixel 9', connected: true, protocolVersion: 4 },
        { id: 'phone-2', name: 'Galaxy S24', connected: false, protocolVersion: 4 }
      ]
    }),
    orchardConnectPairingMessage: ref(''),
    activeTrack: ref({ id: 'track-1', title: 'Song 1', artist: 'Artist 1', album: 'Album 1' }),
    isPlaying: ref(true),
    buffering: ref(false),
    currentTime: ref(45.5),
    duration: ref(200),
    volume: ref(0.8),
    shuffleEnabled: ref(false),
    repeatMode: ref('off'),
    autoplayEnabled: ref(true),
    isSeeking: ref(false),
    seekPosition: ref(0),
    queue: ref([
      { id: 'track-2', title: 'Song 2', artist: 'Artist 2' }
    ]),
    history: ref([]),
    listeningParty: ref({ status: 'idle' }),
    lyricsState: ref({ status: 'idle', mode: '', lines: [] }),
    trackCover: () => '',
    nowArtworkImage: ref(''),
    nowArtworkVideo: ref(''),
    enhancedArtwork: ref(null),
    currentPlaybackElement: () => ({
      paused: false,
      pause() { ctx.isPlaying.value = false; }
    }),
    cancelActiveCrossfade: () => {},
    isPlayableTrack: (t) => Boolean(t?.id),
    emitWithReply: async (event, payload) => {
      socket.emitted.push({ event, payload });
      return { delivered: true };
    },
    playTrack: async (track, options = {}) => {
      ctx.activeTrack.value = track;
      ctx.isPlaying.value = true;
      if (options.resumeAt) ctx.currentTime.value = options.resumeAt;
      if (options.queueSource) ctx.queue.value = options.queueSource.filter(t => t.id !== track.id);
    }
  };

  installConnectActions(ctx);
  return ctx;
}

test('sendConnectTargetCommand sends commands to remote device target', () => {
  const ctx = createMockContext();
  const socket = ctx.socket.value;

  // Local target returns false and emits nothing
  const localRes = ctx.sendConnectTargetCommand({ type: 'play-pause' });
  assert.equal(localRes, false);
  assert.equal(socket.emitted.length, 0);

  // Active target set to phone-1
  ctx.activePlaybackTarget.value = 'phone-1';
  const remoteRes = ctx.sendConnectTargetCommand({ type: 'play-pause' });
  assert.equal(remoteRes, true);
  assert.equal(socket.emitted.length, 1);
  assert.deepEqual(socket.emitted[0], {
    event: 'connect:device-command',
    payload: {
      deviceId: 'phone-1',
      command: { type: 'play-pause' }
    }
  });
});

test('selectPlaybackTarget transfers context to phone and pauses local player when phone is idle', async () => {
  const ctx = createMockContext();
  const socket = ctx.socket.value;

  await ctx.selectPlaybackTarget('phone-1');

  assert.equal(ctx.activePlaybackTarget.value, 'phone-1');
  assert.equal(ctx.isPlaying.value, false);

  const transferEmit = socket.emitted.find(e => e.event === 'connect:device-command' && e.payload.command.type === 'transfer');
  assert.ok(transferEmit);
  assert.equal(transferEmit.payload.deviceId, 'phone-1');
  assert.equal(transferEmit.payload.command.value.track.id, 'track-1');
  assert.equal(transferEmit.payload.command.value.positionSeconds, 45.5);
  assert.equal(transferEmit.payload.command.value.queue.length, 1);
  assert.equal(transferEmit.payload.command.value.queue[0].id, 'track-2');
  assert.equal(transferEmit.payload.command.value.autoplay, true);
});

test('selectPlaybackTarget transfers the selected desktop session even when the phone has stale state', async () => {
  const ctx = createMockContext();
  const socket = ctx.socket.value;

  ctx.remoteDeviceStates.value['phone-1'] = {
    track: { id: 'mobile-track-99', title: 'Mobile Song', artist: 'Mobile Artist' },
    playback: { isPlaying: true, currentTime: 20, duration: 180, shuffle: true, repeatMode: 'off' },
    queue: [{ id: 'mobile-track-99', title: 'Mobile Song' }, { id: 'mobile-next', title: 'Mobile Next' }]
  };

  await ctx.selectPlaybackTarget('phone-1');

  assert.equal(ctx.activePlaybackTarget.value, 'phone-1');
  assert.equal(ctx.activeTrack.value.id, 'track-1');

  const transferEmit = socket.emitted.find(e => e.event === 'connect:device-command' && e.payload.command.type === 'transfer');
  assert.ok(transferEmit);
  assert.equal(transferEmit.payload.command.value.track.id, 'track-1');
});

test('selectPlaybackTarget switches back to local and resumes playback from remote state', async () => {
  const ctx = createMockContext();
  const socket = ctx.socket.value;

  ctx.activePlaybackTarget.value = 'phone-1';
  ctx.remoteDeviceStates.value['phone-1'] = {
    track: { id: 'track-3', title: 'Remote Song', artist: 'Remote Artist' },
    playback: { isPlaying: true, currentTime: 88, shuffle: true, repeatMode: 'queue' },
    queue: [{ id: 'track-3', title: 'Remote Song' }, { id: 'track-4', title: 'Song 4' }]
  };

  await ctx.selectPlaybackTarget('local');

  assert.equal(ctx.activePlaybackTarget.value, 'local');
  assert.equal(ctx.activeTrack.value.id, 'track-3');
  assert.equal(ctx.currentTime.value, 88);
  assert.equal(ctx.shuffleEnabled.value, true);
  assert.equal(ctx.repeatMode.value, 'queue');

  // Paused the remote device
  const pauseEmit = socket.emitted.find(e => e.event === 'connect:device-command' && e.payload.command.type === 'pause');
  assert.ok(pauseEmit);
  assert.equal(pauseEmit.payload.deviceId, 'phone-1');
});

test('applyRemotePlaybackState syncs remote state to desktop UI', () => {
  const ctx = createMockContext();
  const socket = ctx.socket.value;
  ctx.activePlaybackTarget.value = 'phone-1';

  ctx.applyRemotePlaybackState({
    track: { id: 'phone-song', title: 'Phone Song', artist: 'Phone Artist', durationSeconds: 240 },
    playback: {
      isPlaying: true,
      buffering: false,
      currentTime: 12.3,
      duration: 240,
      volume: 0.95,
      shuffle: true,
      repeatMode: 'one'
    },
    queue: [
      { id: 'phone-next', title: 'Phone Next' }
    ]
  });

  assert.equal(ctx.activeTrack.value.id, 'phone-song');
  assert.equal(ctx.isPlaying.value, true);
  assert.equal(ctx.currentTime.value, 12.3);
  assert.equal(ctx.duration.value, 240);
  assert.equal(ctx.volume.value, 0.95);
  assert.equal(ctx.shuffleEnabled.value, true);
  assert.equal(ctx.repeatMode.value, 'one');
  assert.equal(ctx.queue.value.length, 1);
  assert.equal(socket.emitted.length, 0, 'remote volume snapshots must not echo a volume command');
});

test('incoming connect:device-state automatically updates UI when target is active', () => {
  const ctx = createMockContext();
  ctx.bindOrchardConnectEvents();
  const socket = ctx.socket.value;
  const handler = socket.listeners.get('connect:device-state');
  assert.ok(handler);

  ctx.activePlaybackTarget.value = 'phone-1';
  handler({
    deviceId: 'phone-1',
    state: {
      track: { id: 'stream-1', title: 'Live Stream' },
      playback: { isPlaying: true, currentTime: 30, duration: 100 }
    }
  });

  assert.equal(ctx.activeTrack.value.id, 'stream-1');
  assert.equal(ctx.currentTime.value, 30);
  assert.equal(ctx.remoteDeviceStates.value['phone-1'].track.id, 'stream-1');
});

test('playback controls route to remote target when activePlaybackTarget is remote', () => {
  const ctx = createMockContext();
  installPlaybackControls(ctx);
  const socket = ctx.socket.value;

  ctx.activePlaybackTarget.value = 'phone-1';

  // Toggle playback
  ctx.togglePlayback();
  let lastCmd = socket.emitted[socket.emitted.length - 1];
  assert.deepEqual(lastCmd.payload.command, { type: 'play-pause' });

  // Play next
  ctx.playNext();
  lastCmd = socket.emitted[socket.emitted.length - 1];
  assert.deepEqual(lastCmd.payload.command, { type: 'next' });

  // Play previous
  ctx.playPrevious();
  lastCmd = socket.emitted[socket.emitted.length - 1];
  assert.deepEqual(lastCmd.payload.command, { type: 'previous' });

  // Seek
  ctx.seek(75);
  lastCmd = socket.emitted[socket.emitted.length - 1];
  assert.deepEqual(lastCmd.payload.command, { type: 'seek', value: 75 });

  // Toggle shuffle
  ctx.toggleShuffle();
  lastCmd = socket.emitted[socket.emitted.length - 1];
  assert.deepEqual(lastCmd.payload.command, { type: 'toggle-shuffle' });

  // Cycle repeat
  ctx.cycleRepeatMode();
  lastCmd = socket.emitted[socket.emitted.length - 1];
  assert.deepEqual(lastCmd.payload.command, { type: 'cycle-repeat' });
});

test('mergeConnectState does not revert activePlaybackTarget when state does not include devices array', () => {
  const ctx = createMockContext();
  ctx.bindOrchardConnectEvents();
  const socket = ctx.socket.value;
  const pairingStateListener = socket.listeners.get('connect:pairing-state');

  ctx.activePlaybackTarget.value = 'phone-1';

  // Server emits pairing-request which only has pending field
  const pairingReqListener = socket.listeners.get('connect:pairing-request');
  pairingReqListener({ id: 'pending-1', name: 'New Phone' });

  // activePlaybackTarget should STILL be phone-1
  assert.equal(ctx.activePlaybackTarget.value, 'phone-1');
  assert.equal(ctx.orchardConnect.value.devices.length, 2);
});

test('playTrack routes to remote target and optimistically updates UI', async () => {
  const ctx = createMockContext();
  const socket = ctx.socket.value;

  ctx.activePlaybackTarget.value = 'phone-1';

  const newSong = { id: 'new-song-1', title: 'New Song', artist: 'New Artist' };
  const newQueue = [newSong, { id: 'new-song-2', title: 'Upcoming Song' }];

  // Import playTrack dynamically from playbackResolve
  const { installPlaybackResolve } = await import('../src/app/playback/playbackResolve.js');
  installPlaybackResolve(ctx);

  await ctx.playTrack(newSong, { queueSource: newQueue });

  assert.equal(ctx.activeTrack.value.id, 'new-song-1');
  assert.equal(ctx.queue.value.length, 1);
  assert.equal(ctx.queue.value[0].id, 'new-song-2');
  assert.equal(ctx.isPlaying.value, true);

  const transferCmd = socket.emitted.find(e => e.event === 'connect:device-command' && e.payload.command.type === 'transfer');
  assert.ok(transferCmd);
  assert.equal(transferCmd.payload.deviceId, 'phone-1');
  assert.equal(transferCmd.payload.command.value.track.id, 'new-song-1');
  assert.equal(transferCmd.payload.command.value.queue.length, 2);
});

test('selectPlaybackTarget leaves local playback running when command delivery fails', async () => {
  const ctx = createMockContext();
  ctx.emitWithReply = async () => ({ delivered: false, reason: 'offline' });

  await ctx.selectPlaybackTarget('phone-1');

  assert.equal(ctx.activePlaybackTarget.value, 'local');
  assert.equal(ctx.isPlaying.value, true);
  assert.match(ctx.orchardConnectPairingMessage.value, /could not receive playback/i);
});

test('selectPlaybackTarget ignores phones that do not support reverse Connect', async () => {
  const ctx = createMockContext();
  ctx.orchardConnect.value.devices[0].protocolVersion = 3;

  await ctx.selectPlaybackTarget('phone-1');

  assert.equal(ctx.activePlaybackTarget.value, 'local');
  assert.equal(ctx.isPlaying.value, true);
  assert.equal(ctx.socket.value.emitted.length, 0);
});
