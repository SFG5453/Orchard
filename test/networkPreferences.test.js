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
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  DEFAULT_PROXY_MODE,
  normalizeProxyMode,
  proxyConfigForMode,
  registerNetworkPreferences
} from '../electron/platform/networkPreferences.js';
import { IPC_CHANNELS } from '../shared/ipcChannels.js';

function harness(userDataPath) {
  const handlers = new Map();
  const applied = [];
  return {
    applied,
    invoke: (channel, ...args) => handlers.get(channel)(null, ...args),
    deps: {
      app: { getPath: () => userDataPath },
      ipcMain: { handle: (channel, handler) => handlers.set(channel, handler) },
      session: {
        defaultSession: {
          setProxy: async (config) => { applied.push(config); }
        }
      }
    }
  };
}

test('an unknown or missing proxy mode follows the system', () => {
  assert.equal(normalizeProxyMode(undefined), 'system');
  assert.equal(normalizeProxyMode('socks5://somewhere'), 'system');
  assert.equal(normalizeProxyMode(''), 'system');
  assert.equal(DEFAULT_PROXY_MODE, 'system');
  assert.deepEqual(proxyConfigForMode('direct'), { mode: 'direct' });
  assert.deepEqual(proxyConfigForMode('system'), { mode: 'system' });
});

test('a fresh install leaves the system proxy in charge', async () => {
  const userData = await mkdtemp(path.join(tmpdir(), 'orchard-network-'));
  const { deps, applied } = harness(userData);

  const preferences = registerNetworkPreferences(deps);
  assert.equal(await preferences.restore(), 'system');
  assert.deepEqual(applied, [{ mode: 'system' }]);
});

test('a stored bypass is applied before anything asks for a window', async () => {
  const userData = await mkdtemp(path.join(tmpdir(), 'orchard-network-'));
  const first = harness(userData);
  const preferences = registerNetworkPreferences(first.deps);
  await preferences.restore();

  assert.equal(await first.invoke(IPC_CHANNELS.NETWORK.SET_PROXY_MODE, 'direct'), 'direct');
  assert.deepEqual(first.applied.at(-1), { mode: 'direct' });
  assert.equal(await first.invoke(IPC_CHANNELS.NETWORK.GET_PROXY_MODE), 'direct');

  const stored = JSON.parse(await readFile(path.join(userData, 'network-preferences.json'), 'utf8'));
  assert.deepEqual(stored, { proxyMode: 'direct' });

  // The next launch reads the same file, so artwork requested by the opening
  // window already skips the proxy.
  const second = harness(userData);
  assert.equal(await registerNetworkPreferences(second.deps).restore(), 'direct');
  assert.deepEqual(second.applied, [{ mode: 'direct' }]);
});

test('a junk proxy mode is never written back to disk', async () => {
  const userData = await mkdtemp(path.join(tmpdir(), 'orchard-network-'));
  const { deps, invoke, applied } = harness(userData);
  registerNetworkPreferences(deps);

  assert.equal(await invoke(IPC_CHANNELS.NETWORK.SET_PROXY_MODE, 'http://evil.example'), 'system');
  assert.deepEqual(applied.at(-1), { mode: 'system' });

  const stored = JSON.parse(await readFile(path.join(userData, 'network-preferences.json'), 'utf8'));
  assert.deepEqual(stored, { proxyMode: 'system' });
});
