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

// Coordinates application and artist-pack updates while keeping updater lifecycle in the main process.
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';
import { createArtistPackService, readOfficialPackArchive } from './artistPackService.js';
import {
  formatUpdateBytes,
  launchPreparedPackageServiceUpdate,
  preparePackageServiceUpdate
} from './packageServiceInstaller.js';
import {
  DEFAULT_PACKAGE_SERVICE_URL,
  latestBetaPackageManifest,
  packageServiceTarget,
  resolveUpdateChannel,
  selectPackageServiceRelease
} from './packageServiceUpdates.js';
import { updateErrorMessage } from './updateErrors.js';

const require = createRequire(import.meta.url);
const { app, BrowserWindow, dialog, ipcMain, net } = require('electron');
const DEFAULT_UPDATE_URL = DEFAULT_PACKAGE_SERVICE_URL;
const DEFAULT_ARTIST_PACK_INDEX_URL = 'https://artist-packs.sfg545.dev/v1/index.json';
const { UPDATES } = IPC_CHANNELS;
const ARTIST_PACK_MAX_BYTES = 50 * 1024 * 1024;
const GITHUB_OWNER = 'sfg5453';
const GITHUB_REPO = 'orchard';
const UPDATE_CHANNELS = ['stable', 'beta'];
const DEFAULT_UPDATE_CHANNEL = 'stable';

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


function cleanProgress(progress) {
  if (!progress) return null;

  return {
    percent: Number(progress.percent || 0),
    transferred: Number(progress.transferred || 0),
    total: Number(progress.total || 0),
    bytesPerSecond: Number(progress.bytesPerSecond || 0)
  };
}

function updatePreferencesPath() {
  return path.join(app.getPath('userData'), 'update-preferences.json');
}

async function readUpdateChannel() {
  try {
    const parsed = JSON.parse(await readFile(updatePreferencesPath(), 'utf8'));
    return UPDATE_CHANNELS.includes(parsed?.channel) ? parsed.channel : DEFAULT_UPDATE_CHANNEL;
  } catch {
    return DEFAULT_UPDATE_CHANNEL;
  }
}

async function writeUpdateChannel(channel) {
  await writeFile(updatePreferencesPath(), JSON.stringify({ channel }, null, 2));
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

async function fetchJson(url, options = {}, fetchImpl = fetch) {
  const response = await fetchImpl(url, {
    ...options,
    headers: { accept: 'application/json', ...(options.headers || {}) },
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
  const updateUrl = normalizeUpdateUrl(process.env.ORCHARD_PACKAGE_URL);
  const artistPackIndexUrl = normalizeContentIndexUrl(process.env.ORCHARD_ARTIST_PACK_INDEX_URL);
  const sourceBuild = Boolean(isDev) || !app.isPackaged;
  const updateChecksEnabled = !sourceBuild;
  const artistPackService = createArtistPackService({
    app,
    BrowserWindow,
    dialog,
    devOfficialPackContent: sourceBuild ? loadDevOfficialPackContent : null
  });
  const disabledMessage = 'Updates are disabled for development builds.';
  let checkPromise = null;
  let availablePackageRelease = null;
  let availablePackageBaseURL = updateUrl;
  let preparedPackageUpdate = null;
  let packageUpdatePromise = null;
  const fetchWithNet = (url, options) => net.fetch(url, options);
  let state = {
    status: updateChecksEnabled ? 'idle' : 'disabled',
    message: updateChecksEnabled ? 'Updates are ready.' : disabledMessage,
    version: app.getVersion(),
    updateUrl,
    channel: DEFAULT_UPDATE_CHANNEL,
    availableVersion: '',
    releaseDate: '',
    releaseNotes: [],
    progress: null,
    error: '',
    dev: sourceBuild,
    external: false,
    packageType: '',
    packageLabel: 'Orchard package service',
    downloadedFile: '',
    downloadAvailable: false,
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

  function handleUpdateError(error) {
    return publish({
      status: 'error',
      message: 'Update check failed.',
      error: updateErrorMessage(error),
      progress: null
    });
  }

  async function checkPackageServiceUpdate() {
    publish({ status: 'checking', message: 'Checking for updates...', progress: null, error: '' });
    let manifestUrl = new URL('manifest.json', updateUrl).toString();
    availablePackageBaseURL = updateUrl;
    if (state.channel === 'beta') {
      const releases = await fetchJson(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases?per_page=20`, {
        headers: {
          accept: 'application/vnd.github+json',
          'user-agent': `OrchardDesktop/${state.version}`
        }
      }, fetchWithNet);
      const beta = latestBetaPackageManifest(releases);
      if (!beta) throw new Error('No Orchard package beta is currently available.');
      manifestUrl = beta.manifestUrl;
      availablePackageBaseURL = beta.baseURL;
    }
    const manifest = await fetchJson(manifestUrl, {}, fetchWithNet);
    const { latest, updateAvailable } = selectPackageServiceRelease(manifest, {
      channel: state.channel,
      currentVersion: state.version,
      target: packageServiceTarget()
    });
    if (!latest) {
      throw new Error(`No ${state.channel} Orchard package is available for ${packageServiceTarget()}.`);
    }
    availablePackageRelease = latest;
    preparedPackageUpdate = null;
    if (!updateAvailable) {
      return publish({
        status: 'current',
        message: 'Orchard is up to date.',
        availableVersion: latest.version,
        releaseDate: '',
        releaseNotes: [],
        progress: null,
        error: '',
        downloadAvailable: false
      });
    }
    const native = latest.native[packageServiceTarget()];
    const totalSize = Number(latest.shared?.size || 0) + Number(native?.size || 0);
    return publish({
      status: 'available',
      message: `Orchard ${latest.version} is ready to download${totalSize > 0 ? ` (${formatUpdateBytes(totalSize)})` : ''}.`,
      availableVersion: latest.version,
      releaseDate: '',
      releaseNotes: [],
      progress: null,
      error: '',
      downloadAvailable: true
    });
  }

  async function installPackageServiceUpdate(options = {}) {
    if (state.status === 'downloaded' && preparedPackageUpdate) {
      publish({ status: 'installing', message: 'Restarting to install the update…', progress: null, error: '' });
      await launchPreparedPackageServiceUpdate(preparedPackageUpdate, {
        keepOldVersions: options?.keepOldVersions === true
      });
      setTimeout(() => app.quit(), 100);
      return state;
    }
    if (state.status !== 'available' || !availablePackageRelease) return state;
    if (packageUpdatePromise) return packageUpdatePromise;

    const release = availablePackageRelease;
    const target = packageServiceTarget();
    packageUpdatePromise = (async () => {
      try {
        publish({
          status: 'downloading',
          message: `Downloading Orchard ${release.version}…`,
          progress: { percent: 0, transferred: 0, total: 0, bytesPerSecond: 0 },
          error: ''
        });
        preparedPackageUpdate = await preparePackageServiceUpdate({
          appDataDirectory: app.getPath('appData'),
          baseURL: availablePackageBaseURL,
          cacheDirectory: app.getPath('cache'),
          fetchImpl: fetchWithNet,
          release,
          target,
          onProgress: ({ phase, label, transferred, total }) => {
            const ratio = total > 0 ? Math.min(1, transferred / total) : 0;
            const percent = phase === 'electron' ? 75 + ratio * 20 : ratio * 75;
            publish({
              status: 'downloading',
              message: `Downloading ${label}… ${formatUpdateBytes(transferred)}${total > 0 ? ` of ${formatUpdateBytes(total)}` : ''}`,
              progress: { percent, transferred, total, bytesPerSecond: 0 },
              error: ''
            });
          }
        });
        return publish({
          status: 'downloaded',
          message: `Orchard ${release.version} is ready to install.`,
          progress: null,
          error: '',
          downloadAvailable: false
        });
      } catch (error) {
        preparedPackageUpdate = null;
        return publish({
          status: 'error',
          message: 'Update download failed.',
          progress: null,
          error: updateErrorMessage(error),
          downloadAvailable: true
        });
      } finally {
        packageUpdatePromise = null;
      }
    })();
    return packageUpdatePromise;
  }

  let channelReady = updateChecksEnabled
    ? readUpdateChannel().then((stored) => {
      const channel = resolveUpdateChannel(stored, state.version);
      state = { ...state, channel };
      return channel;
    })
    : Promise.resolve(state.channel);

  function runCheckForUpdates() {
    if (!updateChecksEnabled) return state;
    if (checkPromise) return checkPromise;

    checkPromise = channelReady
      .then(() => checkPackageServiceUpdate())
      .then(() => state)
      .catch((error) => handleUpdateError(error))
      .finally(() => {
        checkPromise = null;
      });

    return checkPromise;
  }

  async function setUpdateChannel(channel) {
    if (!updateChecksEnabled || !UPDATE_CHANNELS.includes(channel)) return state;

    await channelReady;
    await writeUpdateChannel(channel);
    channel = resolveUpdateChannel(channel, state.version);
    availablePackageRelease = null;
    preparedPackageUpdate = null;
    publish({ channel, downloadAvailable: false, downloadedFile: '' });
    return runCheckForUpdates();
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

  ipcMain.handle(UPDATES.INSTALL, (_event, options) => installPackageServiceUpdate(options));

  ipcMain.handle(UPDATES.DOWNLOAD_EXTERNAL, () => state);

  ipcMain.handle(UPDATES.REVEAL_EXTERNAL, () => state);

  ipcMain.handle(UPDATES.SET_CHANNEL, (_event, channel) => setUpdateChannel(channel));

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
