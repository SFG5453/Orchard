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

import {
  setWelcomeCompleted,
  WELCOME_COMPLETED_KEY,
  welcomeRequiredAtLaunch
} from '../electron/platform/welcomeState.js';

function memoryStore(initial = null) {
  let value = initial;
  let flushes = 0;
  return {
    get: (key) => key === WELCOME_COMPLETED_KEY ? value : null,
    set: (key, next) => {
      if (key !== WELCOME_COMPLETED_KEY) return false;
      value = next;
      return true;
    },
    flush: () => { flushes += 1; },
    snapshot: () => ({ value, flushes })
  };
}

function rendererWindow(result) {
  let executions = 0;
  return {
    isDestroyed: () => false,
    webContents: {
      executeJavaScript: async () => {
        executions += 1;
        if (result instanceof Error) throw result;
        return result;
      }
    },
    executions: () => executions
  };
}

test('durable completion skips welcome and repairs renderer setup state', async () => {
  const store = memoryStore(true);
  const window = rendererWindow(true);

  assert.equal(await welcomeRequiredAtLaunch(window, store), false);
  assert.equal(window.executions(), 1);
});

test('an explicit durable reset requires welcome without trusting stale renderer state', async () => {
  const store = memoryStore(false);
  const window = rendererWindow(true);

  assert.equal(await welcomeRequiredAtLaunch(window, store), true);
  assert.equal(window.executions(), 0);
});

test('legacy renderer completion migrates to the main-process store', async () => {
  const store = memoryStore();
  const window = rendererWindow(true);

  assert.equal(await welcomeRequiredAtLaunch(window, store), false);
  assert.deepEqual(store.snapshot(), { value: true, flushes: 1 });
});

test('missing or unreadable completion state keeps the welcome window available', async () => {
  assert.equal(await welcomeRequiredAtLaunch(rendererWindow(false), memoryStore()), true);
  assert.equal(await welcomeRequiredAtLaunch(rendererWindow(new Error('renderer gone')), memoryStore()), true);
  assert.equal(await welcomeRequiredAtLaunch(null, memoryStore()), true);
});

test('completion updates are boolean and flushed immediately', () => {
  const store = memoryStore();
  assert.equal(setWelcomeCompleted(store, 1), true);
  assert.deepEqual(store.snapshot(), { value: true, flushes: 1 });
  assert.equal(setWelcomeCompleted(store, 0), true);
  assert.deepEqual(store.snapshot(), { value: false, flushes: 2 });
});
