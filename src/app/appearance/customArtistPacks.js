import { unzipSync } from 'fflate';

const DEFAULT_ARTIST_PACK_INDEX_URL = 'https://artist-packs.sfg545.dev/v1/index.json';
const ARTIST_INDEX_CACHE_MS = 15 * 60 * 1000;
const textDecoder = new TextDecoder();

let indexCache = null;
let indexCacheAt = 0;
let indexPromise = null;
const artistConfigCache = new Map();

function validHttpUrl(value) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) ? url.toString() : '';
  } catch {
    return '';
  }
}

function resolveUrl(value, baseUrl) {
  if (!value) return '';
  try {
    return new URL(value, baseUrl).toString();
  } catch {
    return '';
  }
}

async function artistPackIndexUrl() {
  try {
    const state = await window.orchardUpdates?.getState?.();
    return validHttpUrl(state?.content?.sourceUrl) || DEFAULT_ARTIST_PACK_INDEX_URL;
  } catch {
    return DEFAULT_ARTIST_PACK_INDEX_URL;
  }
}

async function installedArtistIndex() {
  try {
    const index = await window.orchardUpdates?.getUserArtistPacks?.();
    return normalizeLocalIndex(index);
  } catch {
    return { artists: {} };
  }
}

function normalizeIndex(data, sourceUrl) {
  const artists = data?.artists && typeof data.artists === 'object' ? data.artists : {};
  const normalizedArtists = {};

  for (const [artistId, entry] of Object.entries(artists)) {
    const configUrl = resolveUrl(entry?.config?.url || entry?.configUrl || '', sourceUrl);
    if (!configUrl) continue;

    normalizedArtists[artistId] = {
      ...entry,
      artistId,
      configUrl,
      assetBaseUrl: resolveUrl(entry?.assetBaseUrl || '', configUrl) || configUrl,
      profileArtwork: resolveUrl(entry?.profileArtwork || '', sourceUrl),
      styleUrls: (entry?.styles || [])
        .map((style) => resolveUrl(style?.url || style, configUrl))
        .filter(Boolean)
    };
  }

  return {
    ...data,
    sourceUrl,
    artists: normalizedArtists
  };
}

async function hostedArtistIndex() {
  const sourceUrl = await artistPackIndexUrl();
  const response = await fetch(sourceUrl, {
    headers: { accept: 'application/json' },
    cache: 'no-store'
  });
  if (!response.ok) throw new Error(`Artist pack index HTTP ${response.status}`);

  const data = await response.json();
  const hostedIndex = normalizeIndex(data, sourceUrl);
  if (Object.keys(hostedIndex.artists || {}).length) return hostedIndex;

  const archiveUrl = resolveUrl(data?.archive?.url || data?.official?.archive?.url || '', sourceUrl);
  if (!archiveUrl) return hostedIndex;

  return normalizeArchiveIndex(data, await fetchArchiveEntries(archiveUrl), sourceUrl);
}

async function fetchArchiveEntries(archiveUrl) {
  const mainEntries = await window.orchardUpdates?.readArtistPackArchive?.(archiveUrl).catch(() => null);
  if (mainEntries && typeof mainEntries === 'object') return mainEntries;

  const response = await fetch(archiveUrl, { cache: 'no-store' });
  if (!response.ok) throw new Error(`Artist pack archive HTTP ${response.status}`);
  return unzipSync(new Uint8Array(await response.arrayBuffer()));
}

function normalizeArchiveIndex(data, zipEntries, sourceUrl) {
  const artists = {};
  const entries = new Map(
    Object.entries(zipEntries)
      .map(([rawPath, content]) => [normalizePackPath(rawPath), content])
      .filter(([entryPath]) => entryPath)
  );
  const hostedArtists = data?.artists && typeof data.artists === 'object' ? data.artists : {};

  for (const [entryPath, content] of entries.entries()) {
    const match = entryPath.match(/^artists\/([^/]+)\/artist\.json$/);
    if (!match) continue;

    const artistId = match[1];
    try {
      const config = JSON.parse(textDecoder.decode(content));
      if (config.artistId !== artistId) continue;

      const localConfig = configWithEmbeddedArchiveAssets(artistId, config, entries);
      const entry = hostedArtists[artistId] || {};
      artists[artistId] = {
        ...entry,
        artistId,
        artistName: localConfig.artistName,
        displayName: localConfig.displayName,
        layout: localConfig.layout,
        search: entry.search || localConfig.search || {},
        profileArtwork: localConfig.assets?.profile || localConfig.assets?.thumbnail || '',
        localConfig,
        source: 'hosted-archive'
      };
    } catch {
      // Ignore malformed archive entries and keep any other artists usable.
    }
  }

  return {
    ...data,
    sourceUrl,
    artists
  };
}

export async function fetchCustomArtistIndex({ force = false } = {}) {
  if (!force && indexCache && Date.now() - indexCacheAt < ARTIST_INDEX_CACHE_MS) {
    return indexCache;
  }

  if (!force && indexPromise) return indexPromise;

  indexPromise = Promise.all([
    hostedArtistIndex().catch(() => ({ artists: {} })),
    installedArtistIndex()
  ])
    .then(([hostedIndex, userIndex]) => {
      indexCache = {
        ...hostedIndex,
        artists: {
          ...(hostedIndex.artists || {}),
          ...(userIndex.artists || {})
        }
      };
      indexCacheAt = Date.now();
      return indexCache;
    })
    .finally(() => {
      indexPromise = null;
    });

  return indexPromise;
}

export function cachedCustomArtistIndex() {
  return indexCache;
}

export async function refreshCustomArtistIndex() {
  indexCache = null;
  indexCacheAt = 0;
  artistConfigCache.clear();
  return fetchCustomArtistIndex({ force: true });
}

function configWithEmbeddedArchiveAssets(artistId, config, entries) {
  const assets = {};
  for (const [key, relativePath] of Object.entries(config.assets || {})) {
    const cleanPath = normalizePackPath(relativePath);
    const bytes = cleanPath ? entries.get(`artists/${artistId}/${cleanPath}`) : null;
    assets[key] = bytes ? dataUrlForAsset(cleanPath, bytes) : '';
  }

  const styleBytes = entries.get(`artists/${artistId}/style.css`);

  return {
    ...config,
    assets,
    styles: [],
    styleText: styleBytes ? textDecoder.decode(styleBytes) : ''
  };
}

function normalizePackPath(value) {
  const normalized = String(value || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!normalized || normalized.endsWith('/')) return '';
  if (normalized.split('/').some((part) => part === '..' || part === '')) return '';
  return normalized;
}

function dataUrlForAsset(relativePath, bytes) {
  const extension = relativePath.split('.').pop()?.toLowerCase();
  const mime = extension === 'png'
    ? 'image/png'
    : extension === 'webp'
      ? 'image/webp'
      : extension === 'mp3'
        ? 'audio/mpeg'
        : 'image/jpeg';
  return `data:${mime};base64,${base64Bytes(bytes)}`;
}

function base64Bytes(bytes) {
  let binary = '';
  const chunkSize = 0x8000;
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize));
  }
  return btoa(binary);
}

function normalizeArtistConfig(config, entry) {
  const configUrl = entry.configUrl;
  const assets = {};
  for (const [key, value] of Object.entries(config.assets || {})) {
    assets[key] = resolveUrl(value, entry.assetBaseUrl || configUrl);
  }

  return {
    ...config,
    assets,
    styles: [
      ...entry.styleUrls,
      ...(config.styles || []).map((style) => resolveUrl(style?.url || style, configUrl)).filter(Boolean)
    ]
  };
}

function normalizeLocalIndex(data) {
  const artists = data?.artists && typeof data.artists === 'object' ? data.artists : {};
  const normalizedArtists = {};

  for (const [artistId, entry] of Object.entries(artists)) {
    if (!entry?.localConfig) continue;
    normalizedArtists[artistId] = {
      ...entry,
      artistId,
      search: entry.search || entry.localConfig.search || {},
      profileArtwork: entry.profileArtwork || entry.localConfig.assets?.profile || entry.localConfig.assets?.thumbnail || '',
      localConfig: entry.localConfig
    };
  }

  return { artists: normalizedArtists };
}

export async function fetchCustomArtistConfig(artistId) {
  if (artistConfigCache.has(artistId)) return artistConfigCache.get(artistId);

  const index = await fetchCustomArtistIndex();
  const entry = index.artists?.[artistId];
  if (entry?.localConfig) return entry.localConfig;
  if (!entry?.configUrl) return null;

  const response = await fetch(entry.configUrl, {
    headers: { accept: 'application/json' },
    cache: 'no-store'
  });
  if (!response.ok) throw new Error(`Artist config HTTP ${response.status}`);

  const config = normalizeArtistConfig(await response.json(), entry);
  artistConfigCache.set(artistId, config);
  return config;
}

export function customArtistAliasForQuery(query) {
  const normalizedQuery = normalizedAlias(query);
  if (!normalizedQuery) return null;

  const artists = cachedCustomArtistIndex()?.artists || {};
  for (const entry of Object.values(artists)) {
    const search = entry.search || {};
    for (const alias of search.aliases || []) {
      if (normalizedAlias(alias) === normalizedQuery) {
        return {
          browseId: entry.artistId,
          canonicalQuery: search.canonicalQuery || entry.artistName || entry.displayName || alias,
          aliases: search.aliases || []
        };
      }
    }
  }

  return null;
}

export function customArtistProfileArtworkForId(artistId) {
  const entry = cachedCustomArtistIndex()?.artists?.[artistId];
  return entry?.profileArtwork || entry?.assets?.profile || '';
}

function normalizedAlias(value = '') {
  return String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLowerCase();
}
