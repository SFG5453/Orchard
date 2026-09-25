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
import test from 'node:test';

import {
  bindSettingsStorageSync,
  welcomeSettingsSnapshot
} from '../src/app/core/settingsStorageSync.js';

function eventTarget() {
  const listeners = new Set();
  return {
    addEventListener(type, listener) {
      if (type === 'storage') listeners.add(listener);
    },
    removeEventListener(type, listener) {
      if (type === 'storage') listeners.delete(listener);
    },
    dispatch(key) {
      for (const listener of listeners) listener({ key });
    }
  };
}

test('settings written by another renderer update the active application state', () => {
  const target = eventTarget();
  let bridgeListener;
  const bridge = {
    onSettingsSync(listener) {
      bridgeListener = listener;
      return () => { bridgeListener = undefined; };
    }
  };
  const state = { autoplayEnabled: true, autoEqEnabled: false };
  let stored = { autoplayEnabled: false, autoEqEnabled: true };
  const ctx = {
    USER_PREFERENCES_STORAGE_KEY: 'orchard:user-preferences',
    applyImportedPreferences() {
      Object.assign(state, stored);
    }
  };
  const unbind = bindSettingsStorageSync(ctx, target, bridge);

  target.dispatch('unrelated:key');
  assert.deepEqual(state, { autoplayEnabled: true, autoEqEnabled: false });

  target.dispatch('orchard:user-preferences');
  assert.deepEqual(state, { autoplayEnabled: false, autoEqEnabled: true });

  stored = { autoplayEnabled: true, autoEqEnabled: false };
  target.dispatch('orchard:audio-engine');
  assert.deepEqual(state, { autoplayEnabled: true, autoEqEnabled: false });

  stored = { autoplayEnabled: false, autoEqEnabled: true };
  bridgeListener();
  assert.deepEqual(state, { autoplayEnabled: false, autoEqEnabled: true });

  unbind();
  stored = { autoplayEnabled: true, autoEqEnabled: false };
  target.dispatch('orchard:user-preferences');
  assert.equal(bridgeListener, undefined);
  assert.deepEqual(state, { autoplayEnabled: false, autoEqEnabled: true });
});

test('welcome completion snapshots both settings stores for the main renderer', () => {
  const values = new Map([
    ['orchard:user-preferences', '{"autoplayEnabled":false}'],
    ['orchard:audio-engine', '{"config":{"autoEqEnabled":true}}']
  ]);
  const target = {
    localStorage: { getItem: (key) => values.get(key) ?? null }
  };

  assert.deepEqual(welcomeSettingsSnapshot({
    USER_PREFERENCES_STORAGE_KEY: 'orchard:user-preferences'
  }, target), {
    userPreferences: '{"autoplayEnabled":false}',
    audioEngine: '{"config":{"autoEqEnabled":true}}'
  });
});
