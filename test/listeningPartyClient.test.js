import assert from 'node:assert/strict';
import test from 'node:test';
import { computed, ref } from 'vue';
import { ListeningPartyClient } from '../src/app/social/listeningPartyClient.js';
import { installListeningPartyActions } from '../src/app/social/listeningPartyActions.js';
import { installPlaybackControls } from '../src/app/playback/playbackControls.js';
import { continuousQueueEntries } from '../src/app/playback/queueLayout.js';

class FakeWebSocket extends EventTarget {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 3;

  constructor(url) {
    super();
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    queueMicrotask(() => {
      this.readyState = FakeWebSocket.OPEN;
      this.dispatchEvent(new Event('open'));
    });
  }

  send(message) {
    this.sent.push(JSON.parse(message));
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED;
    this.dispatchEvent(new Event('close'));
  }
}

function track(id) {
  return { id, title: id };
}

function listeningPartyContext({
  activeTrack = track('current'),
  queue = [track('queued')],
  history = [track('played')]
} = {}) {
  const playedTracks = [];
  const ctx = {
    activeTrack: ref(activeTrack),
    activeMediaKind: ref('audio'),
    currentTime: ref(0),
    duration: ref(180),
    history: ref(history),
    isPlaying: ref(false),
    queue: ref(queue),
    rightPanelMode: ref('queue'),
    smartCrossfadeMix: ref({ visible: false }),
    currentPlaybackElement: () => null,
    isPlayableTrack: (item) => Boolean(item?.id),
    playTrack: async (item, options) => {
      playedTracks.push({ item, options });
    },
    seek: () => {},
    syncManualQueueOrder: () => {}
  };
  ctx.playedTracks = playedTracks;
  ctx.continuousQueue = computed(() => continuousQueueEntries({
    activeTrack: ctx.activeTrack.value,
    queue: ctx.queue.value,
    history: ctx.history.value
  }));
  installPlaybackControls(ctx);
  installListeningPartyActions(ctx);
  return ctx;
}

test('waits for the listening-party socket before reporting connected', async (t) => {
  const originalWebSocket = globalThis.WebSocket;
  globalThis.WebSocket = FakeWebSocket;
  t.after(() => { globalThis.WebSocket = originalWebSocket; });

  const client = new ListeningPartyClient();
  const connecting = client.connect({
    room: { id: 'ABC234', socketUrl: 'wss://party.example/rooms/ABC234/socket' },
    participant: { id: 'abc123', token: 'def456', role: 'guest' }
  });

  assert.equal(client.status, 'connecting');
  await connecting;
  assert.equal(client.status, 'connected');
});

test('uses one WebSocket path for state updates and host requests', (t) => {
  const originalWebSocket = globalThis.WebSocket;
  globalThis.WebSocket = FakeWebSocket;
  t.after(() => { globalThis.WebSocket = originalWebSocket; });

  const client = new ListeningPartyClient();
  const sent = [];
  client.socket = { readyState: FakeWebSocket.OPEN, send: (message) => sent.push(JSON.parse(message)) };
  client.peers.set('peer', {
    channel: { readyState: 'open', send: () => assert.fail('control messages must not use the peer channel') }
  });

  client.participant = { id: 'host', role: 'host' };
  client.broadcast('party:state', { track: { id: 'song' } });
  client.participant = { id: 'guest', role: 'guest' };
  client.requestHost({ action: 'next' });

  assert.deepEqual(sent, [
    { type: 'party:update', payload: { track: { id: 'song' } } },
    { type: 'party:request', payload: { action: 'next' } }
  ]);
});

test('listening-party state includes the shared playback history', () => {
  const ctx = listeningPartyContext({
    history: [track('played-2'), track('played-1')]
  });

  const state = ctx.currentListeningPartyState('test');

  assert.deepEqual(state.history.map((item) => item.id), ['played-2', 'played-1']);
});

test('party guests replace stale local history with the host history', async (t) => {
  const originalWindow = globalThis.window;
  globalThis.window = { setTimeout: (callback) => { callback(); return 0; } };
  t.after(() => { globalThis.window = originalWindow; });

  const ctx = listeningPartyContext({
    history: [track('local-history')]
  });

  await ctx.applyListeningPartyState({
    track: track('current'),
    queue: [track('queued')],
    history: [track('party-history')],
    currentTime: 0,
    isPlaying: false,
    sentAt: Date.now()
  });

  assert.deepEqual(ctx.history.value.map((item) => item.id), ['party-history']);

  await ctx.applyListeningPartyState({
    track: track('current'),
    queue: [track('queued')],
    currentTime: 0,
    isPlaying: false,
    sentAt: Date.now()
  });

  assert.deepEqual(ctx.history.value, []);
});

test('the host applies a requested continuous queue transition by entry', async () => {
  const ctx = listeningPartyContext({
    queue: [track('next-1'), track('next-2')]
  });

  await ctx.handleListeningPartyRequest({
    action: 'play-continuous-entry',
    section: 'next',
    queueIndex: 1,
    trackId: 'next-2'
  });

  assert.deepEqual(ctx.queue.value, []);
  assert.deepEqual(ctx.history.value.map((item) => item.id), ['current', 'played']);
  assert.equal(ctx.playedTracks.length, 1);
  assert.equal(ctx.playedTracks[0].item.id, 'next-2');
  assert.equal(ctx.playedTracks[0].options.preserveQueue, true);
  assert.equal(ctx.playedTracks[0].options.listeningPartySync, true);
});
