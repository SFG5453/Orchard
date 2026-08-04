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
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { createBeatModelHost } from '../electron/audio/beatModelHost.js';

// Stands in for an Electron utility process: an EventEmitter with the same
// message/exit/kill surface, whose reply behaviour each test scripts.
function fakeChild() {
  const child = new EventEmitter();
  child.sent = [];
  child.killed = false;
  child.postMessage = (message) => {
    child.sent.push(message);
    child.onSend?.(message);
  };
  child.kill = () => {
    child.killed = true;
    child.emit('exit', 0);
  };
  return child;
}

const spectrogram = { frames: 10, mels: 128, framesPerSecond: 50, values: new Float32Array(1280) };

test('requests round-trip through the child and share one fork', async () => {
  const children = [];
  const host = createBeatModelHost({
    modelPath: '/model.onnx',
    fork: async () => {
      const child = fakeChild();
      child.onSend = (message) => {
        queueMicrotask(() => child.emit('message', { id: message.id, result: { bpm: 128 } }));
      };
      children.push(child);
      return child;
    }
  });

  const [first, second] = await Promise.all([host.track(spectrogram), host.track(spectrogram)]);
  assert.deepEqual(first, { bpm: 128 });
  assert.deepEqual(second, { bpm: 128 });
  assert.equal(children.length, 1, 'concurrent requests must share one child');
  assert.equal(children[0].sent[0].modelPath, '/model.onnx');
  host.stop();
});

test('a crash resolves in-flight requests null and the next request re-forks', async () => {
  const children = [];
  const host = createBeatModelHost({
    fork: async () => {
      const child = fakeChild();
      children.push(child);
      return child;
    }
  });

  const pending = host.track(spectrogram);
  // Let the fork settle and the request register, so this exercises the exit
  // handler rather than quietly falling through to the timeout.
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(children[0].sent.length, 1, 'the request should be in flight by now');
  children[0].emit('exit', 139);
  assert.equal(await pending, null, 'a crash must be a routing decision, not an error');

  // The next request must not be poisoned by the dead child.
  const retry = host.track(spectrogram);
  // Wait for the second fork to happen, then answer it.
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(children.length, 2, 'a crash must cost one verdict, not the feature');
  children[1].emit('message', { id: children[1].sent[0].id, result: { bpm: 92 } });
  assert.deepEqual(await retry, { bpm: 92 });
  host.stop();
});

test('a wedged inference is killed at the timeout', async () => {
  const children = [];
  const host = createBeatModelHost({
    timeoutMs: 20,
    fork: async () => {
      const child = fakeChild();
      children.push(child);
      return child; // never replies
    }
  });

  const result = await host.track(spectrogram);
  assert.equal(result, null);
  assert.equal(children[0].killed, true, 'the only way to reclaim the cores is to kill the child');
  host.stop();
});

test('unusable spectrograms never fork a process', async () => {
  let forks = 0;
  const host = createBeatModelHost({
    fork: async () => {
      forks += 1;
      return fakeChild();
    }
  });
  assert.equal(await host.track(null), null);
  assert.equal(await host.track({ frames: 0 }), null);
  assert.equal(forks, 0, 'an empty spectrogram must not pay for a process');
});

test('stop resolves whatever is still in flight', async () => {
  const children = [];
  const host = createBeatModelHost({
    fork: async () => {
      const child = fakeChild();
      children.push(child);
      return child; // never replies
    }
  });
  const pending = host.track(spectrogram);
  await new Promise((resolve) => setImmediate(resolve));
  host.stop();
  assert.equal(await pending, null);
  assert.equal(children[0].killed, true);
});
