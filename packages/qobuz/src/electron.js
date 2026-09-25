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

import { createQobuzAuth } from './auth.js';
import { createQobuz } from './index.js';
import { createQobuzBootstrapLoader } from './bootstrap.js';

export function setupQobuzElectron({
  app,
  applicationName = app?.getName?.() || 'your application',
  BrowserWindow,
  ipcChannels,
  ipcMain,
  logger = console,
  net,
  partition,
  recordPath,
  safeStorage,
  session,
  softwareVersion = `${applicationName}/${app?.getVersion?.() || 'unknown'}`
} = {}) {
  const fetchImpl = (...args) => net.fetch(...args);
  const bootstrap = createQobuzBootstrapLoader({ fetchImpl });
  const auth = createQobuzAuth({
    app,
    applicationName,
    BrowserWindow,
    electronSession: session,
    fetchImpl,
    logger,
    partition,
    recordPath,
    safeStorage,
    bootstrap
  });
  const qobuz = createQobuz({
    bootstrap,
    credentials: auth.credentials,
    fetchImpl,
    logger,
    softwareVersion
  });

  ipcMain.handle(ipcChannels.STATUS, () => auth.publicStatus());
  ipcMain.handle(ipcChannels.CONNECT, async () => {
    const status = await auth.connect();
    qobuz.client.reset();
    return status;
  });
  ipcMain.handle(ipcChannels.DISCONNECT, async () => {
    await qobuz.close();
    return auth.disconnect();
  });
  ipcMain.handle(ipcChannels.UPDATE, (_event, settings) =>
    auth.update(settings && typeof settings === 'object' ? settings : {}));

  return {
    id: 'qobuz',
    async enabled() {
      return (await auth.publicStatus()).enabled;
    },
    async quality() {
      return (await auth.publicStatus()).quality;
    },
    matchTrack: qobuz.matchTrack,
    resolveStream: qobuz.resolveStream,
    proxyStream: qobuz.proxyStream,
    playbackStarted: qobuz.playbackStarted,
    playbackEnded: qobuz.playbackEnded,
    noteError: auth.noteError,
    close: qobuz.close
  };
}

export { createQobuzAuth } from './auth.js';
export { QOBUZ_PARTITION } from './types.js';

// Short alias for hosts that prefer the original composition name.
export const setupQobuz = setupQobuzElectron;
