// Validates and installs artist-pack archives without allowing paths outside managed data roots.
import { Buffer } from 'node:buffer';
import path from 'node:path';
import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { zstdDecompressSync } from 'node:zlib';
import { unzipSync } from 'fflate';

const ARTIST_PACK_MAX_BYTES = 50 * 1024 * 1024;
const ALLOWED_ROOT_FILES = new Set(['artist.json', 'manifest.json', 'style.css']);
const ALLOWED_ASSET_EXTENSIONS = new Set(['.png', '.webp', '.jpg', '.jpeg', '.mp3']);

export function createArtistPackService({ app, BrowserWindow, dialog, devOfficialPackContent }) {
  function userPacksDir() {
    return path.join(app.getPath('userData'), 'custom-artist-packs', 'user');
  }

  function officialPackDir() {
    return path.join(app.getPath('userData'), 'custom-artist-packs', 'official', 'pack');
  }

  async function importPack() {
    const result = await dialog.showOpenDialog(BrowserWindow.getFocusedWindow() || undefined, {
      title: 'Import Orchard artist page',
      properties: ['openFile'],
      filters: [
        { name: 'Orchard artist pages', extensions: ['orchardpack', 'zip', 'zst'] },
        { name: 'All files', extensions: ['*'] }
      ]
    });

    if (result.canceled || !result.filePaths?.[0]) {
      return { canceled: true, packs: await listPacks() };
    }

    const archivePath = result.filePaths[0];
    const bytes = await readFile(archivePath);
    if (bytes.byteLength > ARTIST_PACK_MAX_BYTES) {
      throw new Error('Artist pack archive is too large.');
    }

    const entries = readUserPackArchive(bytes);
    const configBytes = entries.get('artist.json');
    if (!configBytes) {
      throw new Error('User artist packs must contain artist.json at the archive root.');
    }

    const config = JSON.parse(Buffer.from(configBytes).toString('utf8'));
    const artistId = validArtistId(config.artistId);
    if (!artistId) throw new Error('artist.json must include a valid artistId.');

    const targetDir = path.join(userPacksDir(), artistId);
    await rm(targetDir, { recursive: true, force: true });
    await mkdir(targetDir, { recursive: true });

    for (const [relativePath, content] of entries.entries()) {
      const outputPath = path.join(targetDir, relativePath);
      await mkdir(path.dirname(outputPath), { recursive: true });
      await writeFile(outputPath, Buffer.from(content));
    }

    await writeFile(path.join(targetDir, 'install.json'), JSON.stringify({
      artistId,
      sourceName: path.basename(archivePath),
      importedAt: new Date().toISOString()
    }, null, 2));

    return { canceled: false, artistId, packs: await listPacks() };
  }

  async function installOfficialPack(bytes, install = {}) {
    if (bytes.byteLength > ARTIST_PACK_MAX_BYTES) {
      throw new Error('Artist pack archive is too large.');
    }

    const entries = readOfficialPackArchive(bytes);
    const targetDir = officialPackDir();
    await rm(targetDir, { recursive: true, force: true });
    await mkdir(targetDir, { recursive: true });

    for (const [relativePath, content] of entries.entries()) {
      const outputPath = path.join(targetDir, relativePath);
      await mkdir(path.dirname(outputPath), { recursive: true });
      await writeFile(outputPath, Buffer.from(content));
    }

    await writeFile(path.join(targetDir, 'install.json'), JSON.stringify({
      ...install,
      installedAt: install.installedAt || new Date().toISOString()
    }, null, 2));

    return { artistCount: await countArtistDirs(path.join(targetDir, 'artists')) };
  }

  async function hasOfficialPacks() {
    return await countArtistDirs(path.join(officialPackDir(), 'artists')) > 0;
  }

  async function listPacks() {
    const artists = {};
    await addPacksFromDir(artists, path.join(officialPackDir(), 'artists'), {
      source: 'official',
      install: await readInstallState(officialPackDir())
    });
    await addDevOfficialPacks(artists);
    await addPacksFromDir(artists, userPacksDir(), { source: 'user' });
    return { schema: 1, kind: 'orchard-installed-artist-packs', artists };
  }

  async function addDevOfficialPacks(artists) {
    if (typeof devOfficialPackContent !== 'function') return;

    try {
      const content = await devOfficialPackContent();
      if (content) await addPacksFromOfficialContent(artists, content);
    } catch {
      // Development artist packs are optional; fall back to installed or hosted content.
    }
  }

  return { importPack, installOfficialPack, hasOfficialPacks, listPacks };
}

export function readOfficialPackArchive(bytes) {
  return cleanOfficialZipEntries(unzipSync(archiveZipBytes(bytes)));
}

function readUserPackArchive(bytes) {
  return cleanUserZipEntries(unzipSync(archiveZipBytes(bytes)));
}

function archiveZipBytes(bytes) {
  const archive = new Uint8Array(bytes);
  if (!isZstdFrame(archive)) return archive;

  if (typeof zstdDecompressSync !== 'function') {
    throw new Error('This Orchard build cannot read Zstandard artist packs.');
  }

  return zstdDecompressSync(archive, { maxOutputLength: ARTIST_PACK_MAX_BYTES });
}

function isZstdFrame(bytes) {
  return bytes[0] === 0x28 && bytes[1] === 0xb5 && bytes[2] === 0x2f && bytes[3] === 0xfd;
}

async function addPacksFromDir(artists, sourceDir, options = {}) {
  let dirs = [];
  try {
    dirs = await readdir(sourceDir, { withFileTypes: true });
  } catch {
    return;
  }

  for (const dirent of dirs) {
    if (!dirent.isDirectory()) continue;

    const dir = path.join(sourceDir, dirent.name);
    try {
      const config = JSON.parse(await readFile(path.join(dir, 'artist.json'), 'utf8'));
      const artistId = validArtistId(config.artistId);
      if (!artistId || artistId !== dirent.name) continue;

      const normalizedConfig = await configWithEmbeddedAssets(dir, config);
      const install = options.install || await readInstallState(dir);
      artists[artistId] = {
        artistId,
        artistName: normalizedConfig.artistName,
        displayName: normalizedConfig.displayName,
        layout: normalizedConfig.layout,
        search: normalizedConfig.search || {},
        profileArtwork: normalizedConfig.assets?.profile || normalizedConfig.assets?.thumbnail || '',
        localConfig: normalizedConfig,
        source: options.source || 'user',
        importedAt: install.importedAt || '',
        installedAt: install.installedAt || ''
      };
    } catch {
      // Ignore malformed packs and keep loading the rest.
    }
  }
}

async function addPacksFromOfficialContent(artists, content) {
  const contentRoot = content.contentRoot;
  const packArtists = Array.isArray(content.artists) ? content.artists : [];
  if (!contentRoot || !packArtists.length) return;

  for (const artist of packArtists) {
    try {
      const config = JSON.parse(await readFile(path.join(contentRoot, artist.config), 'utf8'));
      const artistId = validArtistId(config.artistId);
      if (!artistId || artistId !== artist.id) continue;

      const normalizedConfig = await configWithOfficialContentAssets(contentRoot, artist, config);
      artists[artistId] = {
        artistId,
        artistName: normalizedConfig.artistName,
        displayName: normalizedConfig.displayName,
        layout: normalizedConfig.layout,
        search: normalizedConfig.search || {},
        profileArtwork: normalizedConfig.assets?.profile || normalizedConfig.assets?.thumbnail || '',
        localConfig: normalizedConfig,
        source: 'official-dev',
        installedAt: content.version || 'development'
      };
    } catch {
      // Ignore broken local development entries and keep the rest available.
    }
  }
}

async function configWithOfficialContentAssets(contentRoot, artist, config) {
  const assets = {};
  const assetSources = new Map(Object.entries(artist.assets || {}));

  for (const [key, relativePath] of Object.entries(config.assets || {})) {
    const cleanPath = normalizeZipPath(relativePath);
    const sourcePath = assetSources.get(cleanPath);
    if (!sourcePath || !ALLOWED_ASSET_EXTENSIONS.has(path.extname(sourcePath).toLowerCase())) continue;

    try {
      assets[key] = dataUrlForAsset(sourcePath, await readFile(path.join(contentRoot, sourcePath)));
    } catch {
      assets[key] = '';
    }
  }

  const css = await Promise.all(
    (artist.styles || []).map((stylePath) => readFile(path.join(contentRoot, stylePath), 'utf8').catch(() => ''))
  );

  return {
    ...config,
    assets,
    styles: [],
    styleText: css.filter(Boolean).join('\n\n')
  };
}

function cleanUserZipEntries(zipEntries) {
  const entries = new Map();
  let totalSize = 0;

  for (const [rawPath, content] of Object.entries(zipEntries)) {
    const relativePath = normalizeZipPath(rawPath);
    if (!relativePath) continue;
    if (!isAllowedPackPath(relativePath)) {
      throw new Error(`Artist pack contains unsupported file: ${relativePath}`);
    }

    totalSize += content.byteLength;
    if (totalSize > ARTIST_PACK_MAX_BYTES) {
      throw new Error('Artist pack archive is too large.');
    }

    entries.set(relativePath, content);
  }

  return entries;
}

function cleanOfficialZipEntries(zipEntries) {
  const entries = new Map();
  const configPaths = [];
  let totalSize = 0;

  for (const [rawPath, content] of Object.entries(zipEntries)) {
    const relativePath = normalizeZipPath(rawPath);
    if (!relativePath) continue;
    if (!isAllowedOfficialPackPath(relativePath)) {
      throw new Error(`Artist pack contains unsupported file: ${relativePath}`);
    }

    totalSize += content.byteLength;
    if (totalSize > ARTIST_PACK_MAX_BYTES) {
      throw new Error('Artist pack archive is too large.');
    }

    if (/^artists\/[^/]+\/artist\.json$/.test(relativePath)) configPaths.push(relativePath);
    entries.set(relativePath, content);
  }

  if (!entries.has('manifest.json')) {
    throw new Error('Official artist pack must contain manifest.json.');
  }

  if (!configPaths.length) {
    throw new Error('Official artist pack must contain at least one artist.');
  }

  for (const configPath of configPaths) {
    const [, artistId] = configPath.match(/^artists\/([^/]+)\/artist\.json$/) || [];
    const config = JSON.parse(Buffer.from(entries.get(configPath)).toString('utf8'));
    if (!validArtistId(config.artistId) || config.artistId !== artistId) {
      throw new Error(`Official artist pack has an invalid artist config: ${configPath}`);
    }
  }

  return entries;
}

function normalizeZipPath(value) {
  const normalized = String(value || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!normalized || normalized.endsWith('/')) return '';
  if (normalized.split('/').some((part) => part === '..' || part === '')) return '';
  return normalized;
}

function isAllowedPackPath(relativePath) {
  if (ALLOWED_ROOT_FILES.has(relativePath)) return true;
  if (!relativePath.startsWith('assets/')) return false;
  return ALLOWED_ASSET_EXTENSIONS.has(path.extname(relativePath).toLowerCase());
}

function isAllowedOfficialPackPath(relativePath) {
  if (relativePath === 'manifest.json') return true;
  const match = relativePath.match(/^artists\/([^/]+)\/(.+)$/);
  if (!match || !validArtistId(match[1])) return false;
  const artistPath = match[2];
  if (artistPath === 'artist.json' || artistPath === 'style.css') return true;
  if (!artistPath.startsWith('assets/')) return false;
  return ALLOWED_ASSET_EXTENSIONS.has(path.extname(artistPath).toLowerCase());
}

function validArtistId(value) {
  const artistId = String(value || '').trim();
  return /^[A-Za-z0-9_-]{8,80}$/.test(artistId) ? artistId : '';
}

async function configWithEmbeddedAssets(dir, config) {
  const assets = {};

  for (const [key, relativePath] of Object.entries(config.assets || {})) {
    const cleanPath = normalizeZipPath(relativePath);
    if (!cleanPath || !cleanPath.startsWith('assets/')) continue;
    if (!ALLOWED_ASSET_EXTENSIONS.has(path.extname(cleanPath).toLowerCase())) continue;

    try {
      assets[key] = dataUrlForAsset(cleanPath, await readFile(path.join(dir, cleanPath)));
    } catch {
      assets[key] = '';
    }
  }

  let styleText = '';
  try {
    styleText = await readFile(path.join(dir, 'style.css'), 'utf8');
  } catch {
    styleText = '';
  }

  return {
    ...config,
    assets,
    styles: [],
    styleText
  };
}

function dataUrlForAsset(relativePath, bytes) {
  const extension = path.extname(relativePath).toLowerCase();
  const mime = extension === '.png'
    ? 'image/png'
    : extension === '.webp'
      ? 'image/webp'
      : extension === '.mp3'
        ? 'audio/mpeg'
        : 'image/jpeg';
  return `data:${mime};base64,${Buffer.from(bytes).toString('base64')}`;
}

async function readInstallState(dir) {
  try {
    return JSON.parse(await readFile(path.join(dir, 'install.json'), 'utf8'));
  } catch {
    return {};
  }
}

async function countArtistDirs(dir) {
  try {
    const entries = await readdir(dir, { withFileTypes: true });
    return entries.filter((entry) => entry.isDirectory() && validArtistId(entry.name)).length;
  } catch {
    return 0;
  }
}
