import assert from 'node:assert/strict';
import test from 'node:test';

import {
  bestTransitionOrder,
  transitionCost
} from '../src/app/playback/queueTransitionSort.js';

function ids(tracks) {
  return tracks.map((track) => track.id);
}

test('transition scoring ignores artist and album identity', () => {
  const left = {
    bpm: 120,
    beatConfidence: 0.8,
    key: 'C major',
    keyConfidence: 0.8
  };
  const right = {
    bpm: 124,
    beatConfidence: 0.8,
    key: 'G major',
    keyConfidence: 0.8
  };
  const baseline = transitionCost(left, right);
  const identityTagged = transitionCost(
    { ...left, album: 'same album', artist: 'same artist' },
    { ...right, album: 'same album', artist: 'same artist' }
  );
  assert.equal(identityTagged, baseline);
});

test('queues without musical evidence remain untouched', () => {
  const queue = [
    { id: 'one', artist: 'Repeated Artist' },
    { id: 'two', artist: 'Repeated Artist' },
    { id: 'three', artist: 'Someone Else' }
  ];
  const result = bestTransitionOrder(queue, new Map(), {});
  assert.equal(result.comparisons, 0);
  assert.deepEqual(ids(result.ordered), ['one', 'two', 'three']);
});

test('BPM and harmonic compatibility determine the route', () => {
  const queue = [{ id: 'rough' }, { id: 'smooth' }, { id: 'followup' }];
  const analyses = new Map([
    ['rough', { bpm: 145, beatConfidence: 0.9, key: 'F♯ major', keyConfidence: 0.8 }],
    ['smooth', { bpm: 102, beatConfidence: 0.9, key: 'G major', keyConfidence: 0.8 }],
    ['followup', { bpm: 105, beatConfidence: 0.9, key: 'D major', keyConfidence: 0.8 }]
  ]);
  const result = bestTransitionOrder(queue, analyses, {
    bpm: 100,
    beatConfidence: 0.9,
    key: 'C major',
    keyConfidence: 0.8
  });
  assert.equal(result.ordered[0].id, 'smooth');
  assert.notDeepEqual(ids(result.ordered), ids(queue));
});

test('unanalyzed tracks keep their positions and split sortable segments', () => {
  const queue = [{ id: 'known-a' }, { id: 'unknown' }, { id: 'known-b' }, { id: 'known-c' }];
  const analyses = new Map([
    ['known-a', { bpm: 130, key: 'A major' }],
    ['known-b', { bpm: 90, key: 'E major' }],
    ['known-c', { bpm: 92, key: 'B major' }]
  ]);
  const result = bestTransitionOrder(queue, analyses, { bpm: 128, key: 'D major' });
  assert.equal(result.ordered[1].id, 'unknown');
  assert.deepEqual(ids(result.ordered), ['known-a', 'unknown', 'known-b', 'known-c']);
});
