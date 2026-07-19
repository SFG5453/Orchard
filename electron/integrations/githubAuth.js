// Keeps support OAuth credentials encrypted in the main process and exposes bounded issue operations.
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const { GITHUB_AUTH } = IPC_CHANNELS;

const supportEndpoint = 'https://support.sfg545.dev';
const githubApi = 'https://api.github.com';
const githubLogin = 'https://github.com/login';
const tokenFilename = 'github-support-auth.json';

function formBody(values) {
  return new URLSearchParams(Object.entries(values).map(([key, value]) => [key, String(value)]));
}

export function setupGithubAuth({ app, ipcMain, net, safeStorage, shell }) {
  const tokenPath = path.join(app.getPath('userData'), tokenFilename);
  let record = null;
  let loaded = false;
  let pending = null;
  let connectionAttempt = 0;

  const fetchJson = async (url, options = {}) => {
    const response = await net.fetch(url, options);
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error_description || payload.error || `Request failed (${response.status}).`);
    return payload;
  };

  const canPersistSecurely = () => {
    if (!safeStorage.isEncryptionAvailable()) return false;
    return safeStorage.getSelectedStorageBackend?.() !== 'basic_text';
  };

  const loadRecord = async () => {
    if (loaded) return;
    loaded = true;
    if (!canPersistSecurely()) return;
    try {
      const stored = JSON.parse(await readFile(tokenPath, 'utf8'));
      const decrypted = safeStorage.decryptString(Buffer.from(stored.encrypted, 'base64'));
      record = JSON.parse(decrypted);
    } catch {
      record = null;
    }
  };

  const persistRecord = async () => {
    if (!record || !canPersistSecurely()) return;
    const encrypted = safeStorage.encryptString(JSON.stringify(record)).toString('base64');
    await writeFile(tokenPath, `${JSON.stringify({ encrypted })}\n`, { mode: 0o600 });
  };

  const forgetRecord = async () => {
    record = null;
    connectionAttempt += 1;
    pending = null;
    if (!canPersistSecurely()) return;
    await writeFile(tokenPath, '{}\n', { mode: 0o600 }).catch(() => {});
  };

  const publicState = () => {
    if (pending) return { status: 'pending', ...pending };
    if (record?.accessToken) {
      return {
        status: 'connected',
        login: record.login,
        avatarUrl: record.avatarUrl || '',
        secureStorage: canPersistSecurely()
      };
    }
    return { status: 'disconnected', secureStorage: canPersistSecurely() };
  };

  const githubHeaders = (accessToken) => ({
    accept: 'application/vnd.github+json',
    authorization: `Bearer ${accessToken}`,
    'user-agent': 'OrchardDesktop/1.0',
    'x-github-api-version': '2022-11-28'
  });

  const saveToken = async (payload, clientId) => {
    const profile = await fetchJson(`${githubApi}/user`, { headers: githubHeaders(payload.access_token) });
    record = {
      clientId,
      accessToken: payload.access_token,
      accessTokenExpiresAt: payload.expires_in ? Date.now() + Number(payload.expires_in) * 1000 : 0,
      refreshToken: payload.refresh_token || '',
      refreshTokenExpiresAt: payload.refresh_token_expires_in
        ? Date.now() + Number(payload.refresh_token_expires_in) * 1000
        : 0,
      login: String(profile.login || ''),
      avatarUrl: String(profile.avatar_url || '')
    };
    pending = null;
    await persistRecord().catch(() => {});
  };

  const pollForToken = async ({ clientId, deviceCode, interval, expiresAt, attempt }) => {
    let pollingInterval = Math.max(5, interval);
    while (attempt === connectionAttempt && Date.now() < expiresAt) {
      await new Promise((resolve) => setTimeout(resolve, pollingInterval * 1000));
      if (attempt !== connectionAttempt) return;
      const response = await net.fetch(`${githubLogin}/oauth/access_token`, {
        method: 'POST',
        headers: { accept: 'application/json', 'content-type': 'application/x-www-form-urlencoded' },
        body: formBody({
          client_id: clientId,
          device_code: deviceCode,
          grant_type: 'urn:ietf:params:oauth:grant-type:device_code'
        })
      });
      const payload = await response.json().catch(() => ({}));
      if (payload.access_token) {
        try {
          await saveToken(payload, clientId);
        } catch (error) {
          pending = { status: 'error', message: error.message || 'GitHub sign-in failed.' };
        }
        return;
      }
      if (payload.error === 'authorization_pending') continue;
      if (payload.error === 'slow_down') {
        pollingInterval += 5;
        continue;
      }
      pending = { status: 'error', message: payload.error_description || 'GitHub sign-in was not completed.' };
      return;
    }
    if (attempt === connectionAttempt) pending = { status: 'error', message: 'The GitHub sign-in code expired.' };
  };

  const refreshAccessToken = async () => {
    if (!record?.refreshToken || (record.refreshTokenExpiresAt && Date.now() >= record.refreshTokenExpiresAt)) {
      await forgetRecord();
      throw new Error('GitHub sign-in expired. Connect GitHub again.');
    }
    const payload = await fetchJson(`${githubLogin}/oauth/access_token`, {
      method: 'POST',
      headers: { accept: 'application/json', 'content-type': 'application/x-www-form-urlencoded' },
      body: formBody({
        client_id: record.clientId,
        grant_type: 'refresh_token',
        refresh_token: record.refreshToken
      })
    });
    await saveToken(payload, record.clientId);
  };

  const accessToken = async () => {
    await loadRecord();
    if (!record?.accessToken) throw new Error('Connect GitHub before posting an attributed issue.');
    if (record.accessTokenExpiresAt && Date.now() >= record.accessTokenExpiresAt - 60_000) await refreshAccessToken();
    return record.accessToken;
  };

  ipcMain.handle(GITHUB_AUTH.STATUS, async () => {
    await loadRecord();
    return publicState();
  });

  ipcMain.handle(GITHUB_AUTH.CONNECT, async () => {
    const config = await fetchJson(`${supportEndpoint}/v1/github/config`);
    if (!config.clientId) throw new Error('GitHub sign-in is not configured for Orchard Support.');
    connectionAttempt += 1;
    const attempt = connectionAttempt;
    const device = await fetchJson(`${githubLogin}/device/code`, {
      method: 'POST',
      headers: { accept: 'application/json', 'content-type': 'application/x-www-form-urlencoded' },
      body: formBody({ client_id: config.clientId })
    });
    const expiresAt = Date.now() + Number(device.expires_in || 900) * 1000;
    pending = {
      userCode: String(device.user_code || ''),
      verificationUri: String(device.verification_uri || 'https://github.com/login/device'),
      expiresAt
    };
    void shell.openExternal(pending.verificationUri);
    void pollForToken({
      clientId: config.clientId,
      deviceCode: device.device_code,
      interval: Number(device.interval || 5),
      expiresAt,
      attempt
    }).catch((error) => {
      if (attempt === connectionAttempt) {
        pending = { status: 'error', message: error.message || 'GitHub sign-in failed.' };
      }
    });
    return publicState();
  });

  ipcMain.handle(GITHUB_AUTH.DISCONNECT, async () => {
    await forgetRecord();
    return publicState();
  });

  ipcMain.handle(GITHUB_AUTH.CREATE_ISSUE, async (_event, input) => {
    const reportId = String(input?.reportId || '');
    const authorization = String(input?.authorization || '');
    if (!/^[a-f0-9-]{36}$/i.test(reportId) || !/^Bearer [^\s]{20,256}$/.test(authorization)) {
      throw new Error('The support report credentials are invalid.');
    }
    const response = await net.fetch(`${supportEndpoint}/v1/reports/${encodeURIComponent(reportId)}/github`, {
      method: 'POST',
      headers: {
        authorization,
        'x-github-user-token': await accessToken()
      }
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || 'The report was sent, but GitHub attribution failed.');
    return payload;
  });
}
