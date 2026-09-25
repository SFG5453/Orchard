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

// Let Chromium renew YouTube cookies from the persistent Google session.
// Coalesce callers and throttle signed-out/offline retries.
export function createBrowserSessionRecovery({ BrowserWindow, partition, capturePageAuth,
  getAccountIndex = () => 0, now = Date.now, retryDelayMs = 60_000, timeoutMs = 15_000 }) {
  let pending;
  let lastAttempt = -Infinity;

  return function recoverBrowserSession() {
    if (pending) return pending;
    if (now() - lastAttempt < retryDelayMs) return Promise.resolve();
    lastAttempt = now();
    pending = (async () => {
      const window = new BrowserWindow({
        show: false,
        webPreferences: {
          partition, nodeIntegration: false, contextIsolation: true, sandbox: true,
          devTools: false
        }
      });
      window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
      let timer;
      try {
        await Promise.race([
          (async () => {
            const url = new URL('https://music.youtube.com/');
            const accountIndex = getAccountIndex();
            if (accountIndex) url.searchParams.set('authuser', String(accountIndex));
            await window.loadURL(url.href);
            if (!window.isDestroyed() && new URL(window.webContents.getURL()).hostname === 'music.youtube.com') {
              await capturePageAuth(window.webContents);
            }
          })(),
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('Browser session refresh timed out')), timeoutMs);
          })
        ]);
      } finally {
        clearTimeout(timer);
        if (!window.isDestroyed()) window.destroy();
      }
    })().finally(() => { pending = null; });
    return pending;
  };
}
