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

import { registerAppHandlers } from '../electron/platform/appHandlers.js';
import { IPC_CHANNELS } from '../shared/ipcChannels.js';

test('finishing welcome transfers settings to the main renderer before showing it', async () => {
  const handlers = new Map();
  const values = new Map([
    ['orchard:setup-state', '{"appearanceReviewed":true}']
  ]);
  const localStorage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value))
  };
  const sent = [];
  let completed = false;
  let shown = false;
  const target = {
    isDestroyed: () => false,
    webContents: {
      async executeJavaScript(source) {
        Function('localStorage', source)(localStorage);
      },
      send: (channel) => sent.push(channel)
    }
  };

  registerAppHandlers({
    app: { getVersion: () => 'test', getName: () => 'Orchard' },
    clearDiscordPresence() {},
    completeWelcome: () => { completed = true; },
    getMainWindow: () => target,
    graphicsMode: { state: () => ({}), setMode: () => ({}) },
    ipcMain: { handle: (channel, handler) => handlers.set(channel, handler) },
    isDev: true,
    resolveDiscordSongLink() {},
    resolveDiscordSongLinkDetails() {},
    setDiscordPresence() {},
    shell: { openPath: async () => '' },
    showMainWindow: () => { shown = true; },
    showWelcomeWindow() {}
  });

  await handlers.get(IPC_CHANNELS.APP.FINISH_WELCOME)(null, {
    userPreferences: '{"autoplayEnabled":false}',
    audioEngine: '{"config":{"autoEqEnabled":true}}'
  });

  assert.equal(completed, true);
  assert.equal(shown, true);
  assert.equal(values.get('orchard:user-preferences'), '{"autoplayEnabled":false}');
  assert.equal(values.get('orchard:audio-engine'), '{"config":{"autoEqEnabled":true}}');
  assert.deepEqual(JSON.parse(values.get('orchard:setup-state')), {
    appearanceReviewed: true,
    completed: true,
    welcomeCompleted: true
  });
  assert.deepEqual(sent, [IPC_CHANNELS.APP.SYNC_SETTINGS]);
});
