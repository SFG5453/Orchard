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
import { access, mkdir, open, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';
import { createArtistPackService, readOfficialPackArchive } from './artistPackService.js';
import {
  cleanManagedUpdateManifest,
  detectManagedUpdatePackage,
  latestBetaManifestUrl,
  managedUpdateAvailable,
  selectManagedUpdateAsset
} from './managedUpdatePackages.js';
import { resolveBetaUpdateCheckFallback, updateErrorMessage } from './updateErrors.js';

const require = createRequire(import.meta.url);
const { app, BrowserWindow, dialog, ipcMain, net, shell } = require('electron');
const { autoUpdater } = require('electron-updater');

const DEFAULT_UPDATE_URL = 'https://downloads.sfg545.dev/orchard/';
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

function cleanReleaseNotes(releaseNotes) {
  const entries = Array.isArray(releaseNotes) ? releaseNotes : [releaseNotes];

  return entries
    .flatMap((entry) => typeof entry === 'string' ? entry.split(/\r?\n/) : [entry?.note])
    .map((entry) => String(entry || '').trim())
    .filter(Boolean);
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

function applyUpdateChannel(channel, updateUrl) {
  autoUpdater.allowPrerelease = channel === 'beta';
  autoUpdater.setFeedURL(
    channel === 'beta'
      ? { provider: 'github', owner: GITHUB_OWNER, repo: GITHUB_REPO }
      : { provider: 'generic', url: updateUrl }
  );
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
  const updateUrl = normalizeUpdateUrl(process.env.ORCHARD_UPDATE_URL);
  const artistPackIndexUrl = normalizeContentIndexUrl(process.env.ORCHARD_ARTIST_PACK_INDEX_URL);
  const managedPackage = detectManagedUpdatePackage();
  const sourceBuild = Boolean(isDev) || !app.isPackaged;
  const selfUpdateEnabled = !managedPackage && !isDev && app.isPackaged;
  const managedUpdatesEnabled = Boolean(managedPackage) && !isDev && app.isPackaged;
  const updateChecksEnabled = selfUpdateEnabled || managedUpdatesEnabled;
  const artistPackService = createArtistPackService({
    app,
    BrowserWindow,
    dialog,
    devOfficialPackContent: sourceBuild ? loadDevOfficialPackContent : null
  });
  const disabledMessage = 'Updates are disabled for development builds.';
  let checkPromise = null;
  let externalDownloadPromise = null;
  let managedUpdateAsset = null;
  let downloadedManagedPath = '';
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
    external: Boolean(managedPackage),
    packageType: managedPackage?.type || '',
    packageLabel: managedPackage?.label || '',
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

  function setUpdateInfo(status, info, message) {
    return publish({
      status,
      message,
      availableVersion: info?.version || '',
      releaseDate: info?.releaseDate || '',
      releaseNotes: cleanReleaseNotes(info?.releaseNotes),
      progress: null,
      error: '',
      external: false,
      downloadAvailable: false,
      downloadedFile: ''
    });
  }

  function handleUpdateError(error) {
    if (state.channel === 'beta') {
      const fallback = resolveBetaUpdateCheckFallback(error, state.version);
      if (fallback) {
        return publish({
          status: 'current',
          message: fallback.message,
          availableVersion: fallback.availableVersion,
          error: '',
          progress: null
        });
      }
    }

    return publish({
      status: 'error',
      message: 'Update check failed.',
      error: updateErrorMessage(error),
      progress: null
    });
  }

  async function managedManifestUrl() {
    if (state.channel === 'stable') return new URL('latest-desktop.json', updateUrl).toString();

    const releases = await fetchJson(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases?per_page=20`, {
      headers: {
        accept: 'application/vnd.github+json',
        'user-agent': `OrchardDesktop/${state.version}`
      }
    }, fetchWithNet);
    const manifestUrl = latestBetaManifestUrl(releases);
    if (!manifestUrl) throw new Error('No Orchard desktop beta release is currently available.');
    return manifestUrl;
  }

  async function checkManagedPackageUpdate() {
    publish({
      status: 'checking',
      message: 'Checking for updates...',
      progress: null,
      error: '',
      downloadedFile: ''
    });

    const manifest = cleanManagedUpdateManifest(await fetchJson(await managedManifestUrl(), {}, fetchWithNet));
    if (manifest.channel !== state.channel) {
      throw new Error(`The update server returned ${manifest.channel} metadata for the ${state.channel} channel.`);
    }

    managedUpdateAsset = selectManagedUpdateAsset(manifest, managedPackage.type, managedPackage.arch);
    downloadedManagedPath = '';
    if (!managedUpdateAvailable(state.version, manifest.version)) {
      return publish({
        status: 'current',
        message: 'Orchard is up to date.',
        availableVersion: manifest.version,
        releaseDate: manifest.releaseDate,
        releaseNotes: manifest.releaseNotes,
        progress: null,
        error: '',
        downloadAvailable: false,
        downloadedFile: ''
      });
    }

    const packageMessage = managedUpdateAsset
      ? `Orchard ${manifest.version} is available for your ${managedPackage.label}.`
      : `Orchard ${manifest.version} is available, but no ${managedPackage.arch} ${managedPackage.label} download was published.`;
    return publish({
      status: 'external-available',
      message: packageMessage,
      availableVersion: manifest.version,
      releaseDate: manifest.releaseDate,
      releaseNotes: manifest.releaseNotes,
      progress: null,
      error: '',
      downloadAvailable: Boolean(managedUpdateAsset),
      downloadedFile: ''
    });
  }

  let channelReady = updateChecksEnabled
    ? readUpdateChannel().then((channel) => {
      state = { ...state, channel };
      if (selfUpdateEnabled) applyUpdateChannel(channel, updateUrl);
      return channel;
    })
    : Promise.resolve(state.channel);

  function runCheckForUpdates() {
    if (!updateChecksEnabled) return state;
    if (checkPromise) return checkPromise;

    checkPromise = channelReady
      .then(() => managedUpdatesEnabled ? checkManagedPackageUpdate() : autoUpdater.checkForUpdates())
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
    if (selfUpdateEnabled) applyUpdateChannel(channel, updateUrl);
    managedUpdateAsset = null;
    downloadedManagedPath = '';
    publish({ channel, downloadAvailable: false, downloadedFile: '' });
    return runCheckForUpdates();
  }

  async function unusedDownloadPath(directory, fileName) {
    async function exists(filePath) {
      try {
        await access(filePath);
        return true;
      } catch (error) {
        if (error?.code === 'ENOENT') return false;
        throw error;
      }
    }

    const parsed = path.parse(path.basename(fileName));
    for (let suffix = 0; suffix < 1000; suffix += 1) {
      const candidateName = suffix ? `${parsed.name} (${suffix})${parsed.ext}` : parsed.base;
      const candidate = path.join(directory, candidateName);
      if (!await exists(candidate) && !await exists(`${candidate}.part`)) return candidate;
    }
    throw new Error('Could not choose an unused file name in Downloads.');
  }

  async function saveManagedUpdate(asset, destinationPath) {
    const temporaryPath = `${destinationPath}.part`;
    const response = await net.fetch(asset.url, { redirect: 'follow' });
    if (!response.ok || !response.body) throw new Error(`Package download failed with HTTP ${response.status}.`);

    const total = Number(response.headers.get('content-length') || asset.size || 0);
    const hash = createHash('sha256');
    const output = await open(temporaryPath, 'wx');
    let transferred = 0;

    try {
      for await (const chunk of response.body) {
        const bytes = Buffer.from(chunk);
        let offset = 0;
        while (offset < bytes.byteLength) {
          const { bytesWritten } = await output.write(bytes, offset, bytes.byteLength - offset);
          if (!bytesWritten) throw new Error('The package download stopped before it was complete.');
          offset += bytesWritten;
        }
        hash.update(bytes);
        transferred += bytes.byteLength;
        if (transferred > asset.size) {
          throw new Error('The downloaded package was larger than the release manifest declared.');
        }
        publish({
          status: 'external-downloading',
          message: total > 0
            ? `Downloading update ${Math.min(100, Math.round((transferred / total) * 100))}%`
            : 'Downloading update...',
          progress: {
            percent: total > 0 ? Math.min(100, (transferred / total) * 100) : 0,
            transferred,
            total,
            bytesPerSecond: 0
          },
          error: ''
        });
      }
      await output.close();

      if (transferred !== asset.size) {
        throw new Error('The downloaded package size did not match the release manifest.');
      }
      if (hash.digest('hex') !== asset.sha256) {
        throw new Error('The downloaded package checksum did not match the release manifest.');
      }
      await rename(temporaryPath, destinationPath);
      return destinationPath;
    } catch (error) {
      await output.close().catch(() => {});
      await unlink(temporaryPath).catch(() => {});
      throw error;
    }
  }

  async function downloadManagedUpdate() {
    if (!managedUpdatesEnabled || !state.downloadAvailable || !managedUpdateAsset) return state;
    if (externalDownloadPromise) return externalDownloadPromise;

    const asset = managedUpdateAsset;
    const availableVersion = state.availableVersion;
    externalDownloadPromise = (async () => {
      try {
        const parent = BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0];
        const confirmationOptions = {
          type: 'question',
          buttons: ['Download', 'Cancel'],
          defaultId: 0,
          cancelId: 1,
          title: 'Download Orchard update',
          message: `Download Orchard ${availableVersion}?`,
          detail: `${asset.name} will be saved to your Downloads folder. ${managedPackage.installHint}`
        };
        const confirmation = parent
          ? await dialog.showMessageBox(parent, confirmationOptions)
          : await dialog.showMessageBox(confirmationOptions);
        if (confirmation.response !== 0) return state;

        const downloadsDirectory = app.getPath('downloads');
        await mkdir(downloadsDirectory, { recursive: true });
        const destination = await unusedDownloadPath(downloadsDirectory, asset.name);
        publish({
          status: 'external-downloading',
          message: `Downloading ${asset.name}...`,
          progress: null,
          error: ''
        });
        downloadedManagedPath = await saveManagedUpdate(asset, destination);
        return publish({
          status: 'external-downloaded',
          message: `${path.basename(destination)} was saved to Downloads.`,
          progress: null,
          error: '',
          downloadedFile: path.basename(destination),
          downloadAvailable: false
        });
      } catch (error) {
        return publish({
          status: 'error',
          message: 'Package download failed.',
          progress: null,
          error: updateErrorMessage(error),
          downloadAvailable: Boolean(asset)
        });
      } finally {
        externalDownloadPromise = null;
      }
    })();

    return externalDownloadPromise;
  }

  function revealManagedUpdate() {
    if (downloadedManagedPath) shell.showItemInFolder(downloadedManagedPath);
    return state;
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

  ipcMain.handle(UPDATES.DOWNLOAD_EXTERNAL, () => downloadManagedUpdate());

  ipcMain.handle(UPDATES.REVEAL_EXTERNAL, () => revealManagedUpdate());

  ipcMain.handle(UPDATES.SET_CHANNEL, (_event, channel) => setUpdateChannel(channel));

  if (!selfUpdateEnabled) {
    return {
      checkForUpdates: () => runCheckForUpdates(),
      checkForContentUpdates: runCheckForContentUpdates,
      importArtistPack: importUserArtistPack,
      getState: () => state
    };
  }

  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

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
    handleUpdateError(error);
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
