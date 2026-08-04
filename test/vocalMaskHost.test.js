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

import { createVocalMaskHost } from '../electron/audio/vocalMaskHost.js';

// The shared fork/crash/timeout lifecycle is fully exercised by
// beatModelHost.test.js against the same underlying modelProcessHost.js; this
// file only pins that the vocal-mask wrapper is wired to it correctly (its
// own entry point, its own service name) rather than re-testing that logic.
function fakeChild() {
  const child = new EventEmitter();
  child.sent = [];
  child.postMessage = (message) => {
    child.sent.push(message);
    queueMicrotask(() => child.emit('message', { id: message.id, result: { curve: [0.4, 0.6] } }));
  };
  child.kill = () => child.emit('exit', 0);
  return child;
}

const spectrogram = { frames: 10, channels: 2, bins: 2049, framesPerSecond: 43.07, values: new Float32Array(1) };

test('the vocal-mask host forks its own process and round-trips a request', async () => {
  let forkedPath;
  const host = createVocalMaskHost({
    modelPath: '/vocal-model.onnx',
    fork: async () => {
      const child = fakeChild();
      return child;
    }
  });
  const result = await host.track(spectrogram);
  assert.deepEqual(result, { curve: [0.4, 0.6] });
  host.stop();
});

test('a crash resolves the pending request null rather than hanging', async () => {
  let child;
  const host = createVocalMaskHost({
    fork: async () => {
      child = new EventEmitter();
      child.postMessage = () => {};
      child.kill = () => child.emit('exit', 1);
      return child;
    }
  });
  const pending = host.track(spectrogram);
  await new Promise((resolve) => setImmediate(resolve));
  child.emit('exit', 1);
  assert.equal(await pending, null);
  host.stop();
});
