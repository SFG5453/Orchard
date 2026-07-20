/*
 * Due to InnerTube's rate limits, we must use a separate service
 * for BPM metadata to avoid shadow-realming users.
 */
import { createBpmMetadataStorage } from './bpmMetadataStore.js';

export const DEFAULT_BPM_ENDPOINT = 'https://bpm.sfg545.dev/bpm';

const REQUEST_TIMEOUT_MS = 4_500;
const MAX_CACHE_ENTRIES = 500;
const CACHE_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1_000;
const MUSICAL_KEY = /^([A-Ga-g])([#♯b♭]?)(m|min|minor|maj|major)?$/i;

function cleanText(value, maximum = 300) {
  return String(value ?? '').trim().replace(/\s+/g, ' ').slice(0, maximum);
}

function artistName(value) {
  return cleanText(value?.name || value);
}

function normalizedLookup(value) {
  return cleanText(value)
    .normalize('NFKD')
    .replace(/\p{Mark}/gu, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

export function bpmCacheKey(query) {
  return `${normalizedLookup(query.title)}\n${normalizedLookup(query.artist)}`;
}

function configuredEndpoint() {
  const configured = cleanText(import.meta.env?.VITE_ORCHARD_BPM_URL, 2_000);
  return configured || DEFAULT_BPM_ENDPOINT;
}

export function bpmTrackQuery(track = {}) {
  const typeText = `${track.type || ''} ${track.musicVideoType || ''} ${track.queueOrigin?.kind || ''}`
    .replace(/[_-]+/g, ' ');
  if (track.isLive || /\b(podcast|episode|audiobook|live|concert|performance)\b/i.test(typeText)) {
    return null;
  }

  const title = cleanText(track.title);
  if (!title) return null;
  const listedArtists = Array.isArray(track.artists)
    ? track.artists.map(artistName).filter(Boolean)
    : [];
  const subtitle = cleanText(track.subtitle);
  const artist = artistName(track.artist) || listedArtists[0] || (
    subtitle && !/^\d{1,2}:\d{2}(?::\d{2})?$/.test(subtitle) ? subtitle : ''
  );
  return { title, artist };
}

export function normalizeMusicalKey(value) {
  const compact = cleanText(value, 32).replace(/\s+/g, '');
  const match = compact.match(MUSICAL_KEY);
  if (!match) return '';
  const accidental = match[2] === '#' ? '♯' : match[2] === 'b' ? '♭' : match[2];
  const suffix = match[3]?.toLowerCase() || '';
  const mode = ['m', 'min', 'minor'].includes(suffix) ? 'minor' : 'major';
  return `${match[1].toUpperCase()}${accidental} ${mode}`;
}

export function normalizeBpmMetadata(value) {
  const bpm = Number(value?.bpm);
  const title = cleanText(value?.title);
  if (!Number.isFinite(bpm) || bpm < 20 || bpm > 400 || !title) return null;
  return {
    bpm,
    beatInterval: 60 / bpm,
    beatConfidence: 0.82,
    key: normalizeMusicalKey(value?.key),
    keyConfidence: value?.key ? 0.82 : 0,
    title,
    artist: cleanText(value?.artist),
    songUrl: cleanText(value?.songUrl, 2_000),
    source: 'GetSongBPM'
  };
}

function calibratedBpm(catalogBpm, analyzedBpm) {
  if (!(analyzedBpm > 0)) return catalogBpm;
  const candidates = [catalogBpm / 2, catalogBpm, catalogBpm * 2]
    .filter((value) => value >= 40 && value <= 240);
  return candidates.reduce((closest, value) =>
    Math.abs(value - analyzedBpm) < Math.abs(closest - analyzedBpm) ? value : closest
  , candidates[0] || catalogBpm);
}

export function mergeBpmMetadata(analysis = {}, metadata = null) {
  if (!metadata) return analysis;
  const analyzedBpm = Number(analysis?.bpm) || 0;
  const bpm = calibratedBpm(Number(metadata.bpm), analyzedBpm);
  const catalogKey = normalizeMusicalKey(metadata.key);
  return {
    ...analysis,
    ...(analyzedBpm && analyzedBpm !== bpm ? { analyzedBpm } : {}),
    ...(analysis?.key && catalogKey && analysis.key !== catalogKey ? { analyzedKey: analysis.key } : {}),
    bpm,
    beatInterval: 60 / bpm,
    beatConfidence: Math.max(Number(analysis?.beatConfidence) || 0, Number(metadata.beatConfidence) || 0.82),
    key: catalogKey || analysis?.key || '',
    keyConfidence: catalogKey
      ? Math.max(Number(analysis?.keyConfidence) || 0, Number(metadata.keyConfidence) || 0.82)
      : Number(analysis?.keyConfidence) || 0,
    bpmSource: metadata.source || 'GetSongBPM',
    catalogBpm: Number(metadata.bpm) || 0,
    catalogSongUrl: metadata.songUrl || ''
  };
}

export function createBpmMetadataClient({
  endpoint = configuredEndpoint(),
  fetcher = globalThis.fetch,
  requestTimeoutMs = REQUEST_TIMEOUT_MS,
  report = () => {},
  storage = createBpmMetadataStorage()
} = {}) {
  const cache = new Map();
  const cacheTimes = new Map();
  const pending = new Map();
  let unavailableUntil = 0;
  let persistTimer = 0;
  const storageReady = Promise.resolve(storage?.load?.())
    .then((records = []) => {
      const freshAfter = Date.now() - CACHE_MAX_AGE_MS;
      records.slice(-MAX_CACHE_ENTRIES).forEach((record) => {
        const metadata = normalizeBpmMetadata(record?.metadata);
        if (!record?.key || !metadata || Number(record.cachedAt) < freshAfter) return;
        cache.set(record.key, metadata);
        cacheTimes.set(record.key, Number(record.cachedAt));
      });
    })
    .catch(() => {});

  function schedulePersist() {
    if (!storage?.save) return;
    clearTimeout(persistTimer);
    persistTimer = setTimeout(() => {
      persistTimer = 0;
      const records = Array.from(cache, ([key, metadata]) => ({
        key,
        metadata,
        cachedAt: cacheTimes.get(key) || Date.now()
      }))
        .filter((record) => record.metadata)
        .slice(-MAX_CACHE_ENTRIES);
      void Promise.resolve(storage.save(records)).catch(() => {});
    }, 250);
    persistTimer.unref?.();
  }

  async function requestMetadata(query) {
    if (typeof fetcher !== 'function') return null;
    if (Date.now() < unavailableUntil) return null;
    const url = new URL(endpoint);
    url.searchParams.set('title', query.title);
    if (query.artist) url.searchParams.set('artist', query.artist);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), requestTimeoutMs);
    try {
      const response = await fetcher(url, {
        headers: { accept: 'application/json' },
        signal: controller.signal
      });
      if (!response.ok) {
        if (response.status === 429 || response.status >= 500) {
          unavailableUntil = Date.now() + 30_000;
        }
        report('request-miss', { status: response.status });
        return null;
      }
      const metadata = normalizeBpmMetadata(await response.json());
      unavailableUntil = 0;
      report(metadata ? 'request-ready' : 'request-invalid', {
        bpm: Number(metadata?.bpm) || 0,
        hasKey: Boolean(metadata?.key)
      });
      return metadata;
    } catch (error) {
      unavailableUntil = Date.now() + 30_000;
      report('request-failed', {
        errorName: String(error?.name || 'Error'),
        errorMessage: String(error?.message || error || 'Unknown error').slice(0, 300)
      });
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  async function lookup(track) {
    const query = bpmTrackQuery(track);
    if (!query) return null;
    await storageReady;
    const key = bpmCacheKey(query);
    if (cache.has(key)) return cache.get(key);
    if (pending.has(key)) return pending.get(key);

    const request = requestMetadata(query)
      .then((metadata) => {
        if (!metadata) return null;
        cache.set(key, metadata);
        cacheTimes.set(key, Date.now());
        if (cache.size > MAX_CACHE_ENTRIES) {
          const oldestKey = cache.keys().next().value;
          cache.delete(oldestKey);
          cacheTimes.delete(oldestKey);
        }
        schedulePersist();
        return metadata;
      })
      .finally(() => pending.delete(key));
    pending.set(key, request);
    return request;
  }

  async function lookupMany(tracks, { concurrency = 6 } = {}) {
    const unique = Array.from(new Map(
      tracks.filter((track) => track?.id).map((track) => [track.id, track])
    ).values());
    const results = new Map();
    let cursor = 0;

    async function worker() {
      while (cursor < unique.length) {
        const track = unique[cursor];
        cursor += 1;
        const metadata = await lookup(track);
        if (metadata) results.set(track.id, metadata);
      }
    }

    await Promise.all(Array.from(
      { length: Math.min(Math.max(1, concurrency), unique.length) },
      () => worker()
    ));
    return results;
  }

  return { lookup, lookupMany };
}
