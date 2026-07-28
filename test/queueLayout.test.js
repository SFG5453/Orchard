import assert from 'node:assert/strict';
import test from 'node:test';

import { installPlaybackControls } from '../src/app/playback/playbackControls.js';
import {
  advanceToQueueEntry,
  continuousQueueEntries,
  normalizeQueueLayout,
  rewindToHistoryEntry
} from '../src/app/playback/queueLayout.js';

function track(id) {
  return { id, title: id };
}

function ids(tracks) {
  return tracks.map((item) => item.id);
}

function controlsContext({ history = [], activeTrack = null, queue = [] } = {}) {
  const played = [];
  const ctx = {
    activeTrack: { value: activeTrack },
    history: { value: history },
    queue: { value: queue },
    smartCrossfadeMix: { value: { visible: false } },
    syncManualQueueOrder: () => {},
    playTrack: (item, options) => played.push({ item, options })
  };

  installPlaybackControls(ctx);
  ctx.seek = (position) => played.push({ seek: position });
  return { ctx, played };
}

test('unknown queue layouts fall back to the up next list', () => {
  assert.equal(normalizeQueueLayout('continuous'), 'continuous');
  assert.equal(normalizeQueueLayout('upNext'), 'upNext');
  assert.equal(normalizeQueueLayout('youtube'), 'upNext');
  assert.equal(normalizeQueueLayout(undefined), 'upNext');
});

test('continuous entries read oldest played first and end with the queue', () => {
  const entries = continuousQueueEntries({
    history: [track('played-2'), track('played-1')],
    activeTrack: track('current'),
    queue: [track('next-1'), track('next-2')]
  });

  assert.deepEqual(entries.map((entry) => entry.track.id), [
    'played-1',
    'played-2',
    'current',
    'next-1',
    'next-2'
  ]);
  assert.deepEqual(entries.map((entry) => entry.section), [
    'previous',
    'previous',
    'current',
    'next',
    'next'
  ]);
  assert.deepEqual(entries.map((entry) => entry.sectionStart), [true, false, true, true, false]);
  assert.deepEqual(entries.map((entry) => entry.historyIndex), [1, 0, -1, -1, -1]);
  assert.deepEqual(entries.map((entry) => entry.queueIndex), [-1, -1, -1, 0, 1]);
  assert.equal(new Set(entries.map((entry) => entry.key)).size, entries.length);
});

test('continuous entries skip missing tracks and survive an empty player', () => {
  assert.deepEqual(continuousQueueEntries(), []);
  assert.deepEqual(
    continuousQueueEntries({ history: [null, {}], activeTrack: null, queue: [{ title: 'no id' }] }),
    []
  );
});

test('playing an earlier track pushes the tracks in between back onto the queue', () => {
  const next = rewindToHistoryEntry({
    history: [track('played-3'), track('played-2'), track('played-1')],
    activeTrack: track('current'),
    queue: [track('queued')],
    historyIndex: 2
  });

  assert.equal(next.track.id, 'played-1');
  assert.deepEqual(ids(next.queue), ['played-2', 'played-3', 'current', 'queued']);
  assert.deepEqual(ids(next.history), []);
});

test('playing the track just before the current one matches skipping back once', () => {
  const next = rewindToHistoryEntry({
    history: [track('played-2'), track('played-1')],
    activeTrack: track('current'),
    queue: [track('queued')],
    historyIndex: 0
  });

  assert.equal(next.track.id, 'played-2');
  assert.deepEqual(ids(next.queue), ['current', 'queued']);
  assert.deepEqual(ids(next.history), ['played-1']);
  assert.equal(rewindToHistoryEntry({ history: [], activeTrack: track('current'), historyIndex: 0 }), null);
});

test('jumping ahead keeps the skipped tracks as history instead of dropping them', () => {
  const next = advanceToQueueEntry({
    history: [track('played')],
    activeTrack: track('current'),
    queue: [track('next-1'), track('next-2'), track('next-3')],
    queueIndex: 2
  });

  assert.equal(next.track.id, 'next-3');
  assert.deepEqual(ids(next.queue), []);
  assert.deepEqual(ids(next.history), ['next-2', 'next-1', 'current', 'played']);
  assert.equal(advanceToQueueEntry({ queue: [], queueIndex: 0 }), null);
});

test('jumping ahead keeps history within the stored limit', () => {
  const next = advanceToQueueEntry({
    history: Array.from({ length: 30 }, (_, index) => track(`played-${index}`)),
    activeTrack: track('current'),
    queue: Array.from({ length: 5 }, (_, index) => track(`next-${index}`)),
    queueIndex: 4
  });

  assert.equal(next.history.length, 30);
  assert.deepEqual(ids(next.history).slice(0, 5), ['next-3', 'next-2', 'next-1', 'next-0', 'current']);
});

test('the continuous queue plays an earlier track without recording it as history', () => {
  const { ctx, played } = controlsContext({
    history: [track('played-2'), track('played-1')],
    activeTrack: track('current'),
    queue: [track('queued')]
  });

  ctx.playContinuousQueueEntry({ section: 'previous', historyIndex: 1, track: track('played-1') });

  assert.deepEqual(ids(ctx.queue.value), ['played-2', 'current', 'queued']);
  assert.deepEqual(ids(ctx.history.value), []);
  assert.equal(played.length, 1);
  assert.equal(played[0].item.id, 'played-1');
  assert.equal(played[0].options.skipHistory, true);
  assert.equal(played[0].options.preserveQueue, true);
  assert.equal(played[0].options.sessionAction, 'previous');
});

test('the continuous queue plays a queued track and keeps the skipped ones above it', () => {
  const { ctx, played } = controlsContext({
    history: [],
    activeTrack: track('current'),
    queue: [track('next-1'), track('next-2')]
  });

  ctx.playContinuousQueueEntry({ section: 'next', queueIndex: 1, track: track('next-2') });

  assert.deepEqual(ids(ctx.queue.value), []);
  assert.deepEqual(ids(ctx.history.value), ['next-1', 'current']);
  assert.equal(played[0].item.id, 'next-2');
  assert.equal(played[0].options.sessionAction, 'manual');
});

test('the continuous queue restarts the current track and defers to a listening party host', () => {
  const { ctx, played } = controlsContext({
    history: [track('played')],
    activeTrack: track('current'),
    queue: [track('queued')]
  });

  ctx.playContinuousQueueEntry({ section: 'current', track: track('current') });
  assert.deepEqual(played, [{ seek: 0 }]);

  const requests = [];
  ctx.requestListeningPartyHostControl = (payload) => {
    requests.push(payload);
    return true;
  };
  ctx.playContinuousQueueEntry({ section: 'next', queueIndex: 0, track: track('queued') });

  assert.equal(requests.length, 1);
  assert.equal(requests[0].track.id, 'queued');
  assert.deepEqual(ids(ctx.queue.value), ['queued']);
  assert.deepEqual(ids(ctx.history.value), ['played']);
  assert.equal(played.length, 1);
});
