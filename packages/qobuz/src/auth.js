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

import { createServer } from 'node:http';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createQobuzAuthorizationUrl, exchangeQobuzAuthorizationCode } from './oauth.js';
import { QOBUZ_PARTITION, QOBUZ_USER_AGENT, normalizeQobuzQuality } from './types.js';

const LOGIN_TIMEOUT_MS = 5 * 60_000;

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[character]);
}

function callbackHtml(success, applicationName) {
  const safeApplicationName = escapeHtml(applicationName);
  const title = success ? 'Qobuz connected' : 'Qobuz login failed';
  const detail = success
    ? `You can close this window and return to ${safeApplicationName}.`
    : `No authorization code was received. Return to ${safeApplicationName} and try again.`;
  return `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title></head><body style="font:16px system-ui;background:#101512;color:#f4f7f4;padding:48px"><h1>${title}</h1><p>${detail}</p></body></html>`;
}

export function createQobuzAuth({
  app,
  applicationName = app?.getName?.() || 'your application',
  BrowserWindow,
  electronSession,
  fetchImpl = fetch,
  logger = console,
  partition = QOBUZ_PARTITION,
  recordPath,
  safeStorage,
  bootstrap
} = {}) {
  const storagePath = recordPath || path.join(app.getPath('userData'), 'qobuz-session.json');
  let token = '';
  let userId = null;
  let enabled = false;
  let quality = 'auto';
  let lastError = '';
  let loaded = false;
  let loadPromise = null;
  let connectPromise = null;

  function canPersistSecurely() {
    if (!safeStorage?.isEncryptionAvailable?.()) return false;
    try {
      return safeStorage.getSelectedStorageBackend?.() !== 'basic_text';
    } catch {
      return true;
    }
  }

  async function load() {
    if (loaded) return;
    if (loadPromise) return loadPromise;
    loadPromise = (async () => {
      try {
        const record = JSON.parse(await readFile(storagePath, 'utf8'));
        enabled = record.enabled === true;
        quality = normalizeQobuzQuality(record.quality);
        userId = record.userId ?? null;
        if (record.encryptedToken && canPersistSecurely()) {
          token = safeStorage.decryptString(Buffer.from(record.encryptedToken, 'base64'));
        }
      } catch (error) {
        if (error?.code !== 'ENOENT') logger.warn?.(`Could not restore Qobuz session: ${error.message}`);
      } finally {
        if (!token) enabled = false;
        loaded = true;
      }
    })();
    return loadPromise;
  }

  async function save() {
    const record = {
      enabled: Boolean(enabled && token),
      quality: normalizeQobuzQuality(quality),
      userId,
      ...(token && canPersistSecurely()
        ? { encryptedToken: safeStorage.encryptString(token).toString('base64') }
        : {})
    };
    await mkdir(path.dirname(storagePath), { recursive: true });
    const temporaryPath = `${storagePath}.tmp`;
    await writeFile(temporaryPath, JSON.stringify(record), { encoding: 'utf8', mode: 0o600 });
    await rename(temporaryPath, storagePath);
  }

  async function publicStatus() {
    await load();
    return {
      status: token ? 'connected' : 'disconnected',
      enabled: Boolean(enabled && token),
      quality,
      secureStorage: canPersistSecurely(),
      persistent: Boolean(token && canPersistSecurely()),
      lastError
    };
  }

  async function credentials() {
    await load();
    return token ? { token, userId } : null;
  }

  async function obtainAuthorizationCode(web) {
    let finishCode;
    let settled = false;
    const codePromise = new Promise((resolve) => {
      finishCode = (value) => {
        if (settled) return;
        settled = true;
        resolve(value || '');
      };
    });
    const callbackServer = createServer((request, response) => {
      const requestUrl = new URL(request.url, 'http://127.0.0.1');
      const code = requestUrl.searchParams.get('code_autorisation') || '';
      response.writeHead(code ? 200 : 400, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      response.end(callbackHtml(Boolean(code), applicationName));
      if (code) finishCode(code);
    });
    await new Promise((resolve, reject) => {
      callbackServer.once('error', reject);
      callbackServer.listen(0, '127.0.0.1', resolve);
    });
    const callbackUrl = `http://127.0.0.1:${callbackServer.address().port}/qobuz-callback`;
    const oauthUrl = createQobuzAuthorizationUrl({ appId: web.appId, redirectUrl: callbackUrl });

    const win = new BrowserWindow({
      width: 650,
      height: 780,
      title: `Connect Qobuz to ${applicationName}`,
      autoHideMenuBar: true,
      webPreferences: {
        partition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true
      }
    });
    win.webContents.userAgent = QOBUZ_USER_AGENT;
    const inspectUrl = (_event, value) => {
      try {
        const code = new URL(value).searchParams.get('code_autorisation');
        if (code) finishCode(code);
      } catch {}
    };
    win.webContents.on('will-redirect', inspectUrl);
    win.webContents.on('did-navigate', inspectUrl);
    win.webContents.on('did-navigate-in-page', inspectUrl);
    win.on('closed', () => finishCode(''));
    const timer = setTimeout(() => finishCode(''), LOGIN_TIMEOUT_MS);
    void win.loadURL(oauthUrl.toString(), { userAgent: QOBUZ_USER_AGENT }).catch((error) => {
      // The final redirect intentionally leaves qobuz.com for our loopback
      // callback, which Chromium can surface as an aborted original load.
      if (!settled && error?.code !== 'ERR_ABORTED') finishCode('');
    });
    const code = await codePromise;
    clearTimeout(timer);
    await new Promise((resolve) => callbackServer.close(resolve));
    if (!win.isDestroyed()) win.destroy();
    return code;
  }

  async function connect() {
    await load();
    if (connectPromise) return connectPromise;
    connectPromise = (async () => {
      lastError = '';
      const web = await bootstrap.get({ refresh: true });
      const code = await obtainAuthorizationCode(web);
      if (!code) return publicStatus();
      const account = await exchangeQobuzAuthorizationCode({ code, fetchImpl, web });
      token = account.token;
      userId = account.userId;
      enabled = true;
      await save();
      return publicStatus();
    })().catch(async (error) => {
      lastError = error.message;
      throw error;
    }).finally(() => {
      connectPromise = null;
    });
    return connectPromise;
  }

  async function disconnect() {
    await load();
    token = '';
    userId = null;
    enabled = false;
    lastError = '';
    await save();
    try {
      await electronSession.fromPartition(partition).clearStorageData({
        storages: ['cookies', 'localstorage', 'indexdb', 'serviceworkers', 'cachestorage']
      });
    } catch (error) {
      logger.warn?.(`Could not clear Qobuz login storage: ${error.message}`);
    }
    return publicStatus();
  }

  async function update(settings = {}) {
    await load();
    if (Object.hasOwn(settings, 'enabled')) enabled = settings.enabled === true && Boolean(token);
    if (Object.hasOwn(settings, 'quality')) quality = normalizeQobuzQuality(settings.quality);
    await save();
    return publicStatus();
  }

  function noteError(error) {
    lastError = String(error?.message || error || 'Qobuz playback failed').slice(0, 300);
  }

  return { connect, credentials, disconnect, load, noteError, publicStatus, update };
}
