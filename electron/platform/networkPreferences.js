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

/**
 * Chooses whether Orchard's Chromium traffic follows the system proxy.
 *
 * Only part of the app ever sees a proxy: album art loads as ordinary renderer
 * images and the update check runs through Electron's `net`, so both resolve the
 * system proxy, while playback is fetched on Node's stack, which does not. A
 * stale or unreachable proxy therefore breaks artwork and updates while music
 * keeps playing, which reads as a bug in Orchard rather than a machine setting.
 *
 * The preference is owned by the main process and read before the first window
 * loads: a renderer-side setting would arrive after the first images were
 * already requested. It defaults to following the system, so a proxy an
 * administrator configured is never bypassed unless the listener asks for it.
 */
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

export const PROXY_MODES = Object.freeze(['system', 'direct']);
export const DEFAULT_PROXY_MODE = 'system';

export function normalizeProxyMode(value) {
  return PROXY_MODES.includes(value) ? value : DEFAULT_PROXY_MODE;
}

/**
 * `direct` is Chromium's own name for "resolve nothing, connect straight out",
 * which is the behaviour playback already has.
 */
export function proxyConfigForMode(mode) {
  return normalizeProxyMode(mode) === 'direct' ? { mode: 'direct' } : { mode: 'system' };
}

export function registerNetworkPreferences({ app, ipcMain, session }) {
  const preferencesPath = () => path.join(app.getPath('userData'), 'network-preferences.json');
  let proxyMode = DEFAULT_PROXY_MODE;

  async function read() {
    try {
      const parsed = JSON.parse(await readFile(preferencesPath(), 'utf8'));
      return normalizeProxyMode(parsed?.proxyMode);
    } catch {
      return DEFAULT_PROXY_MODE;
    }
  }

  async function apply(mode) {
    proxyMode = normalizeProxyMode(mode);
    await session.defaultSession.setProxy(proxyConfigForMode(proxyMode));
    return proxyMode;
  }

  ipcMain.handle(IPC_CHANNELS.NETWORK.GET_PROXY_MODE, () => proxyMode);
  ipcMain.handle(IPC_CHANNELS.NETWORK.SET_PROXY_MODE, async (_event, mode) => {
    const applied = await apply(mode);
    // Written after the session accepted it, so a rejected mode is never the one
    // restored at the next launch.
    await writeFile(preferencesPath(), `${JSON.stringify({ proxyMode: applied }, null, 2)}\n`);
    return applied;
  });

  return {
    /** Applies the stored mode. Awaited before the first window loads anything. */
    async restore() {
      return apply(await read());
    },
    mode: () => proxyMode
  };
}
