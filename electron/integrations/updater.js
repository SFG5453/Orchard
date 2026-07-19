// Coordinates application and artist-pack updates while keeping updater lifecycle in the main process.
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';
import { createArtistPackService, readOfficialPackArchive } from './artistPackService.js';

const require = createRequire(import.meta.url);
const { app, BrowserWindow, dialog, ipcMain } = require('electron');
const { autoUpdater } = require('electron-updater');

const DEFAULT_UPDATE_URL = 'https://downloads.sfg545.dev/orchard/';
const DEFAULT_ARTIST_PACK_INDEX_URL = 'https://artist-packs.sfg545.dev/v1/index.json';
const { UPDATES } = IPC_CHANNELS;
const ARTIST_PACK_MAX_BYTES = 50 * 1024 * 1024;

function normalizeUpdateUrl(value) {
  const fallback = DEFAULT_UPDATE_URL;

  try {
    const url = new URL(value || fallback);
    if (!['http:', 'https:'].includes(url.protocol)) return fallback;
    if (!url.pathname.endsWith('/')) url.pathname = `${url.pathname}/`;
    return url.toString();
  } catch {
    return fallback;
  }
}

function normalizeContentIndexUrl(value) {
  try {
    const url = new URL(value || DEFAULT_ARTIST_PACK_INDEX_URL);
    if (!['http:', 'https:'].includes(url.protocol)) return DEFAULT_ARTIST_PACK_INDEX_URL;
    return url.toString();
  } catch {
    return DEFAULT_ARTIST_PACK_INDEX_URL;
  }
}

function updateErrorMessage(error) {
  if (!error) return 'Update check failed.';
  if (typeof error === 'string') return error;
  return error.message || String(error);
}

function cleanProgress(progress) {
  if (!progress) return null;

  return {
    percent: Number(progress.percent || 0),
    transferred: Number(progress.transferred || 0),
    total: Number(progress.total || 0),
    bytesPerSecond: Number(progress.bytesPerSecond || 0)
  };
}

function cleanReleaseNotes(releaseNotes) {
  const entries = Array.isArray(releaseNotes) ? releaseNotes : [releaseNotes];

  return entries
    .flatMap((entry) => typeof entry === 'string' ? entry.split(/\r?\n/) : [entry?.note])
    .map((entry) => String(entry || '').trim())
    .filter(Boolean);
}

function contentStorePaths() {
  const dir = path.join(app.getPath('userData'), 'custom-artist-packs', 'official');
  return {
    dir,
    statePath: path.join(dir, 'state.json'),
    archivePath: path.join(dir, 'orchard-official-artists.orchardpack.zst'),
    legacyArchivePath: path.join(dir, 'orchard-official-artists.orchardpack')
  };
}

async function readContentInstallState() {
  try {
    return JSON.parse(await readFile(contentStorePaths().statePath, 'utf8'));
  } catch {
    return {};
  }
}

function cleanPackIndex(data, sourceUrl) {
  if (!data || typeof data !== 'object') return null;
  const version = String(data.version || data.official?.version || '').trim();
  const archive = data.archive || data.official?.archive || {};
  const archiveUrl = resolvePackUrl(String(archive.url || '').trim(), sourceUrl);
  if (!version || !archiveUrl) return null;

  return {
    version,
    archiveUrl,
    sha256: String(archive.sha256 || '').trim().toLowerCase(),
    size: Number(archive.size || 0),
    notes: Array.isArray(data.notes) ? data.notes.map((note) => String(note || '').trim()).filter(Boolean) : []
  };
}

function resolvePackUrl(value, sourceUrl) {
  try {
    return new URL(value, sourceUrl).toString();
  } catch {
    return '';
  }
}

async function fetchJson(url) {
  const response = await fetch(url, {
    headers: { accept: 'application/json' },
    cache: 'no-store'
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function installOfficialArchive(bytes, pack, artistPackService) {
  const installedAt = new Date().toISOString();
  await artistPackService.installOfficialPack(bytes, {
    version: pack.version,
    sha256: pack.sha256 || '',
    sourceUrl: pack.archiveUrl,
    installedAt
  });
  return installedAt;
}

async function downloadOfficialPack(pack, artistPackService) {
  const bytes = await fetchOfficialPackBytes(pack.archiveUrl, pack.size);

  const sha256 = createHash('sha256').update(bytes).digest('hex');
  if (pack.sha256 && pack.sha256 !== sha256) {
    throw new Error('Artist pack checksum did not match.');
  }

  const { dir, archivePath, statePath } = contentStorePaths();
  await mkdir(dir, { recursive: true });
  await writeFile(archivePath, bytes);
  const installedAt = await installOfficialArchive(bytes, pack, artistPackService);
  await writeFile(statePath, JSON.stringify({
    version: pack.version,
    sha256,
    size: bytes.byteLength,
    archivePath,
    installedAt,
    notes: pack.notes
  }, null, 2));

  return { sha256, size: bytes.byteLength, archivePath, installedAt };
}

async function unpackSavedOfficialPack(pack, artistPackService) {
  const { archivePath, legacyArchivePath } = contentStorePaths();
  const bytes = await readSavedPackBytes(archivePath, legacyArchivePath);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  if (pack.sha256 && pack.sha256 !== sha256) {
    throw new Error('Saved artist pack checksum did not match.');
  }

  const installedAt = await installOfficialArchive(bytes, pack, artistPackService);
  return { sha256, size: bytes.byteLength, archivePath, installedAt };
}

async function readSavedPackBytes(archivePath, legacyArchivePath) {
  try {
    return await readFile(archivePath);
  } catch {
    return readFile(legacyArchivePath);
  }
}

async function fetchOfficialPackBytes(archiveUrl, expectedSize = 0) {
  const url = new URL(archiveUrl);
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('Artist pack archive must use HTTP or HTTPS.');
  }

  const response = await fetch(url, { cache: 'no-store' });
  if (!response.ok) throw new Error(`Archive HTTP ${response.status}`);

  const size = Number(response.headers.get('content-length') || expectedSize || 0);
  if (size > ARTIST_PACK_MAX_BYTES) throw new Error('Artist pack archive is too large.');

  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.byteLength > ARTIST_PACK_MAX_BYTES) throw new Error('Artist pack archive is too large.');
  return bytes;
}

async function fetchOfficialPackEntries(archiveUrl) {
  const bytes = await fetchOfficialPackBytes(archiveUrl);
  const entries = readOfficialPackArchive(bytes);
  return Object.fromEntries(
    [...entries.entries()].map(([entryPath, content]) => [entryPath, Buffer.from(content)])
  );
}

export function setupOrchardUpdates({ isDev }) {
  const updateUrl = normalizeUpdateUrl(process.env.ORCHARD_UPDATE_URL);
  const artistPackIndexUrl = normalizeContentIndexUrl(process.env.ORCHARD_ARTIST_PACK_INDEX_URL);
  const distributionPackage = String(process.env.ORCHARD_DISTRIBUTION_PACKAGE || '').trim();
  const sourceBuild = Boolean(isDev) || !app.isPackaged;
  const enabled = !distributionPackage && !isDev && app.isPackaged;
  const artistPackService = createArtistPackService({
    app,
    BrowserWindow,
    dialog,
    devOfficialPackContent: sourceBuild ? loadDevOfficialPackContent : null
  });
  const disabledMessage = distributionPackage
    ? `Updates are managed by the ${distributionPackage} package.`
    : 'Updates are disabled for development builds.';
  let checkPromise = null;
  let state = {
    status: enabled ? 'idle' : 'disabled',
    message: enabled ? 'Updates are ready.' : disabledMessage,
    version: app.getVersion(),
    updateUrl,
    availableVersion: '',
    releaseDate: '',
    releaseNotes: [],
    progress: null,
    error: '',
    dev: sourceBuild,
    content: {
      status: 'idle',
      message: 'Artist page updates are ready.',
      sourceUrl: artistPackIndexUrl,
      installedVersion: '',
      availableVersion: '',
      updatedAt: '',
      error: '',
      notes: []
    }
  };

  function publish(nextState) {
    state = { ...state, ...nextState };

    for (const window of BrowserWindow.getAllWindows()) {
      window.webContents.send(UPDATES.STATE, state);
    }

    return state;
  }

  function setUpdateInfo(status, info, message) {
    return publish({
      status,
      message,
      availableVersion: info?.version || '',
      releaseDate: info?.releaseDate || '',
      releaseNotes: cleanReleaseNotes(info?.releaseNotes),
      progress: null,
      error: ''
    });
  }

  function runCheckForUpdates() {
    if (!enabled) return state;
    if (checkPromise) return checkPromise;

    checkPromise = autoUpdater.checkForUpdates()
      .then(() => state)
      .catch((error) => publish({
        status: 'error',
        message: 'Update check failed.',
        error: updateErrorMessage(error),
        progress: null
      }))
      .finally(() => {
        checkPromise = null;
      });

    return checkPromise;
  }

  async function importUserArtistPack() {
    try {
      const result = await artistPackService.importPack();
      if (result.canceled) return state;

      return publish({
        content: {
          ...state.content,
          status: 'current',
          message: 'Artist page pack imported.',
          error: '',
          userPackCount: Object.keys(result.packs?.artists || {}).length,
          updatedAt: new Date().toISOString()
        }
      });
    } catch (error) {
      return publish({
        content: {
          ...state.content,
          status: 'error',
          message: 'Artist page import failed.',
          error: updateErrorMessage(error)
        }
      });
    }
  }

  async function runCheckForContentUpdates(options = {}) {
    const force = Boolean(options.force);

    publish({
      content: {
        ...state.content,
        status: 'checking',
        message: 'Checking artist page updates...',
        error: ''
      }
    });

    try {
      const installed = await readContentInstallState();
      const pack = cleanPackIndex(await fetchJson(artistPackIndexUrl), artistPackIndexUrl);
      if (!pack) throw new Error('Artist pack index was not valid.');

      if (!force && installed.version === pack.version && await artistPackService.hasOfficialPacks()) {
        return publish({
          content: {
            ...state.content,
            status: 'current',
            message: 'Artist pages are up to date.',
            installedVersion: installed.version || '',
            availableVersion: pack.version,
            updatedAt: installed.installedAt || '',
            error: '',
            notes: pack.notes
          }
        });
      }

      if (!force && installed.version === pack.version) {
        try {
          const saved = await unpackSavedOfficialPack(pack, artistPackService);
          return publish({
            content: {
              ...state.content,
              status: 'current',
              message: 'Artist pages are up to date.',
              installedVersion: installed.version || '',
              availableVersion: pack.version,
              updatedAt: saved.installedAt,
              error: '',
              notes: pack.notes,
              size: saved.size
            }
          });
        } catch {
          // Fall through to redownload when an older install has no unpacked content.
        }
      }

      publish({
        content: {
          ...state.content,
          status: 'downloading',
          message: `Downloading artist pages ${pack.version}...`,
          installedVersion: installed.version || '',
          availableVersion: pack.version,
          error: '',
          notes: pack.notes
        }
      });

      const saved = await downloadOfficialPack(pack, artistPackService);
      return publish({
        content: {
          ...state.content,
          status: 'current',
          message: `Artist pages ${pack.version} are installed.`,
          installedVersion: pack.version,
          availableVersion: pack.version,
          updatedAt: new Date().toISOString(),
          error: '',
          notes: pack.notes,
          size: saved.size
        }
      });
    } catch (error) {
      return publish({
        content: {
          ...state.content,
          status: 'error',
          message: 'Artist page update failed.',
          error: updateErrorMessage(error)
        }
      });
    }
  }

  ipcMain.handle(UPDATES.GET_STATE, () => state);

  ipcMain.handle(UPDATES.CHECK, () => runCheckForUpdates());

  ipcMain.handle(UPDATES.CHECK_CONTENT, (_event, options) => runCheckForContentUpdates(options));

  ipcMain.handle(UPDATES.IMPORT_ARTIST_PACK, () => importUserArtistPack());

  ipcMain.handle(UPDATES.GET_USER_ARTIST_PACKS, () => artistPackService.listPacks());

  ipcMain.handle(UPDATES.READ_ARTIST_PACK_ARCHIVE, (_event, archiveUrl) => fetchOfficialPackEntries(archiveUrl));

  ipcMain.handle(UPDATES.INSTALL, () => {
    if (state.status !== 'downloaded') return state;

    autoUpdater.quitAndInstall(false, true);
    return state;
  });

  if (!enabled) {
    return {
      checkForUpdates: () => state,
      checkForContentUpdates: runCheckForContentUpdates,
      importArtistPack: importUserArtistPack,
      getState: () => state
    };
  }

  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.setFeedURL({ provider: 'generic', url: updateUrl });

  autoUpdater.on('checking-for-update', () => {
    publish({
      status: 'checking',
      message: 'Checking for updates...',
      progress: null,
      error: ''
    });
  });

  autoUpdater.on('update-available', (info) => {
    setUpdateInfo('available', info, `Downloading Orchard ${info?.version || 'update'}...`);
  });

  autoUpdater.on('update-not-available', (info) => {
    setUpdateInfo('current', info, 'Orchard is up to date.');
  });

  autoUpdater.on('download-progress', (progress) => {
    const percent = Math.round(Number(progress?.percent || 0));
    publish({
      status: 'downloading',
      message: `Downloading update ${percent}%`,
      progress: cleanProgress(progress),
      error: ''
    });
  });

  autoUpdater.on('update-downloaded', (info) => {
    setUpdateInfo('downloaded', info, `Orchard ${info?.version || 'update'} is ready to install.`);
  });

  autoUpdater.on('error', (error) => {
    publish({
      status: 'error',
      message: 'Update check failed.',
      error: updateErrorMessage(error),
      progress: null
    });
  });

  return {
    checkForUpdates: () => runCheckForUpdates(),
    getState: () => state
  };
}

async function loadDevOfficialPackContent() {
  const appRoot = app.getAppPath();
  const manifestUrl = pathToFileURL(path.join(appRoot, 'scripts', 'official-artist-pack-content.mjs')).toString();
  const manifest = await import(manifestUrl);
  return {
    contentRoot: path.join(appRoot, 'workers', 'artist-packs', 'content'),
    version: manifest.officialArtistPackVersion || 'development',
    artists: manifest.officialArtistPackArtists || []
  };
}
