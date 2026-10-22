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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import { it } from 'node:test';
import { inferSong } from '../src/translation_inference.js';

it('bounds a stalled binding even if it ignores cancellation', async () => {
  let signal;
  const ai = { run: (_, __, options) => {
    assert.equal(options.rejectIfBusy, true);
    signal = options.signal;
    return new Promise(() => {});
  } };
  await assert.rejects(inferSong(ai, ['Hola'], 'eng', 10), error => error.status === 504 && error.code === 'translation_timeout');
  assert.equal(signal.aborted, true);
});
it('does not start more batches after a timed-out inference eventually resolves', async () => {
  let finish;
  let calls = 0;
  const ai = { run: () => {
    calls++;
    return new Promise(resolve => { finish = resolve; });
  } };
  await assert.rejects(inferSong(ai, Array(49).fill('Hola'), 'eng', 10), error => error.code === 'translation_timeout');
  finish({ choices: [{ message: { content: JSON.stringify(Array(24).fill('Hello')) } }] });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls, 1);
});
it('clears the deadline and rejects malformed model output', async () => {
  const ai = { run: async () => ({ choices: [{ message: { content: '["Hello"]' } }] }) };
  assert.deepEqual(await inferSong(ai, ['Hola'], 'eng', 100), ['Hello']);
  await assert.rejects(inferSong(ai, ['Hola', 'Bonjour'], 'eng', 100), error => error.code === 'translation_invalid_result');
});
