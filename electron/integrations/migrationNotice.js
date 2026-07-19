// Fetches and validates migration metadata before handing download URLs to Electron's shell.
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const { MIGRATION } = IPC_CHANNELS;

const DEFAULT_MANIFEST_URL = 'https://downloads.sfg545.dev/orchard-tauri/latest.json';
const FETCH_TIMEOUT_MS = 12_000;

function platformName(platform) {
  if (platform === 'win32') return 'windows';
  if (platform === 'linux') return 'linux';
  if (platform === 'darwin') return 'darwin';
  return String(platform || '').trim().toLowerCase();
}

function architectureName(arch) {
  if (arch === 'x64') return 'x86_64';
  if (arch === 'arm64') return 'aarch64';
  return String(arch || '').trim().toLowerCase();
}

function preferredPlatformKeys(platform, arch) {
  const platformPart = platformName(platform);
  const archPart = architectureName(arch);
  const base = `${platformPart}-${archPart}`;

  if (platformPart === 'linux' && archPart === 'x86_64') {
    return [`${base}-appimage`, base];
  }

  if (platformPart === 'windows' && archPart === 'x86_64') {
    return [`${base}-nsis`, base];
  }

  return [base];
}

function validatedDownloadUrl(value) {
  const url = new URL(String(value || '').trim());
  if (url.protocol !== 'https:') throw new Error('The release download URL is not HTTPS.');
  return url.toString();
}

export function selectMigrationPlatform(platforms, platform = process.platform, arch = process.arch) {
  if (!platforms || typeof platforms !== 'object' || Array.isArray(platforms)) {
    throw new Error('The release manifest does not contain platform downloads.');
  }

  const candidates = preferredPlatformKeys(platform, arch);
  let platformKey = candidates.find((key) => platforms[key]?.url);

  if (!platformKey) {
    const prefix = `${platformName(platform)}-${architectureName(arch)}`;
    platformKey = Object.keys(platforms).find((key) => key === prefix || key.startsWith(`${prefix}-`));
  }

  if (!platformKey || !platforms[platformKey]?.url) {
    throw new Error(`No download is available for ${platformName(platform)} ${architectureName(arch)}.`);
  }

  return {
    platformKey,
    downloadUrl: validatedDownloadUrl(platforms[platformKey].url)
  };
}

export function parseMigrationManifest(manifest, platform = process.platform, arch = process.arch) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new Error('The release manifest is invalid.');
  }

  const version = String(manifest.version || '').trim();
  if (!version) throw new Error('The release manifest does not contain a version.');

  const selected = selectMigrationPlatform(manifest.platforms, platform, arch);
  return {
    status: 'ready',
    version,
    notes: String(manifest.notes || ''),
    pubDate: String(manifest.pub_date || ''),
    platformKey: selected.platformKey,
    downloadUrl: selected.downloadUrl,
    error: ''
  };
}

export function setupMigrationNotice({
  ipcMain,
  shell,
  fetchImpl = globalThis.fetch,
  manifestUrl = DEFAULT_MANIFEST_URL,
  platform = process.platform,
  arch = process.arch
}) {
  let state = {
    status: 'loading',
    version: '',
    notes: '',
    pubDate: '',
    platformKey: '',
    downloadUrl: '',
    error: ''
  };
  let currentRequest = null;

  async function fetchState({ force = false } = {}) {
    if (!force && state.status === 'ready') return state;
    if (currentRequest) return currentRequest;

    state = { ...state, status: 'loading', error: '' };
    currentRequest = (async () => {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);

      try {
        const response = await fetchImpl(manifestUrl, {
          cache: 'no-store',
          headers: { accept: 'application/json' },
          signal: controller.signal
        });
        if (!response.ok) throw new Error(`Release server returned HTTP ${response.status}.`);

        state = parseMigrationManifest(await response.json(), platform, arch);
      } catch (error) {
        const message = error?.name === 'AbortError'
          ? 'The release check timed out.'
          : (error?.message || String(error));
        state = {
          ...state,
          status: 'error',
          downloadUrl: '',
          platformKey: '',
          error: message
        };
      } finally {
        clearTimeout(timeout);
        currentRequest = null;
      }

      return state;
    })();

    return currentRequest;
  }

  ipcMain.handle(MIGRATION.GET_STATE, () => fetchState());
  ipcMain.handle(MIGRATION.REFRESH, () => fetchState({ force: true }));
  ipcMain.handle(MIGRATION.DOWNLOAD, async () => {
    const latest = await fetchState({ force: true });
    if (latest.status !== 'ready' || !latest.downloadUrl) return latest;

    await shell.openExternal(latest.downloadUrl);
    return latest;
  });

  return { getState: fetchState };
}
