import { createRequire } from 'node:module';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

// Owns native-addon loading, analysis request de-duplication, and the persisted
// result cache. `stop()` removes every IPC handler and flushes pending cache data.

const require = createRequire(import.meta.url);
const { AUDIO_ANALYSIS } = IPC_CHANNELS;
const CACHE_VERSION = 5;
const MAX_CACHE_ITEMS = 600;

function errorDetails(error) {
  return {
    errorName: String(error?.name || 'Error'),
    errorMessage: String(error?.message || error || 'Unknown error').slice(0, 1000),
    errorCode: String(error?.code || '').slice(0, 100)
  };
}

function stdoutLogger(event, details = {}) {
  let suffix = '';
  try {
    const serialized = JSON.stringify(details);
    if (serialized && serialized !== '{}') suffix = ` ${serialized.slice(0, 4000)}`;
  } catch {
    suffix = ' {"logError":"Details were not serializable"}';
  }
  process.stdout.write(`[audio-analysis] ${event}${suffix}\n`);
}

function cleanTrackId(value) {
  return String(value || '').trim().slice(0, 256);
}

// IPC may deserialize an ArrayBuffer or another typed view. This creates only a
// view; the N-API binding performs the lifetime-safe whole-buffer copy.
function floatSamples(value) {
  if (value instanceof Float32Array) return value;
  if (value instanceof ArrayBuffer) return new Float32Array(value);
  if (ArrayBuffer.isView(value)) {
    if (value.byteLength % Float32Array.BYTES_PER_ELEMENT !== 0) return null;
    return new Float32Array(value.buffer, value.byteOffset, value.byteLength / Float32Array.BYTES_PER_ELEMENT);
  }
  return null;
}

/**
 * Registers the privileged native-analysis IPC service and persistent LRU cache.
 * @param {object} options
 * @param {string} options.cachePath Atomic JSON cache destination.
 * @param {Electron.IpcMain} options.ipcMain IPC registrar owned by Electron.
 * @param {string} options.nativeModulePath Development or asar-unpacked addon path.
 * @returns {{stop: Function}} Cleanup that removes handlers and flushes cache data.
 */
export function setupAudioAnalysisService({
  cachePath,
  ipcMain,
  nativeModulePath,
  loadNativeAddon = require,
  logger = stdoutLogger
}) {
  const cache = new Map();
  const inFlight = new Map();
  let nativeAddon = null;
  let nativeLoadAttempts = 0;
  let saveTimer = null;
  let savePromise = Promise.resolve();

  function log(event, details = {}) {
    try {
      logger(event, details);
    } catch {
      // Diagnostics must never interrupt playback or analysis.
    }
  }

  function addon() {
    if (nativeAddon) return nativeAddon;
    nativeLoadAttempts += 1;
    try {
      const loaded = loadNativeAddon(nativeModulePath);
      if (loaded?.analysisVersion === CACHE_VERSION && typeof loaded?.analyze === 'function') {
        nativeAddon = loaded;
        log('native-load-ready', { attempt: nativeLoadAttempts, analysisVersion: loaded.analysisVersion });
      } else {
        log('native-load-invalid', {
          attempt: nativeLoadAttempts,
          expectedAnalysisVersion: CACHE_VERSION,
          actualAnalysisVersion: loaded?.analysisVersion ?? null,
          hasAnalyze: typeof loaded?.analyze === 'function'
        });
      }
    } catch (error) {
      nativeAddon = null;
      log('native-load-failed', { attempt: nativeLoadAttempts, ...errorDetails(error) });
    }
    return nativeAddon;
  }

  // Load before the renderer asks for analysis, while still allowing a later
  // request to recover if startup briefly raced the unpacked native module.
  addon();

  const cacheReady = readFile(cachePath, 'utf8')
    .then((contents) => JSON.parse(contents))
    .then((stored) => {
      if (stored?.version !== CACHE_VERSION || !Array.isArray(stored.items)) return;
      stored.items.slice(-MAX_CACHE_ITEMS).forEach((item) => {
        const trackId = cleanTrackId(item?.trackId);
        if (!trackId || item?.result?.analysisVersion !== CACHE_VERSION) return;
        cache.set(trackId, {
          lastUsed: Number(item.lastUsed) || 0,
          result: item.result
        });
      });
    })
    .catch(() => {});

  function cached(trackId) {
    const entry = cache.get(trackId);
    if (!entry) return null;
    cache.delete(trackId);
    cache.set(trackId, { ...entry, lastUsed: Date.now() });
    return entry.result;
  }

  function persist() {
    const items = Array.from(cache, ([trackId, entry]) => ({ trackId, ...entry }));
    const temporaryPath = `${cachePath}.tmp`;
    savePromise = savePromise
      .catch(() => {})
      .then(async () => {
        await mkdir(path.dirname(cachePath), { recursive: true });
        await writeFile(temporaryPath, JSON.stringify({ version: CACHE_VERSION, items }), 'utf8');
        await rename(temporaryPath, cachePath);
      });
    return savePromise;
  }

  function schedulePersist() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      saveTimer = null;
      void persist();
    }, 750);
  }

  ipcMain.handle(AUDIO_ANALYSIS.AVAILABLE, async () => {
    await cacheReady;
    const available = Boolean(addon());
    log('availability-result', { available, loadAttempts: nativeLoadAttempts });
    return available;
  });

  ipcMain.handle(AUDIO_ANALYSIS.GET, async (_event, value) => {
    await cacheReady;
    const trackId = cleanTrackId(value);
    const result = trackId ? cached(trackId) : null;
    log('cache-result', { trackId, hit: Boolean(result) });
    return result;
  });

  ipcMain.handle(AUDIO_ANALYSIS.DEBUG, async (_event, payload = {}) => {
    const event = String(payload?.event || 'unknown').replace(/[^a-z0-9:_-]/gi, '').slice(0, 100);
    const details = payload?.details && typeof payload.details === 'object'
      ? payload.details
      : {};
    log(`renderer:${event}`, details);
    return true;
  });

  ipcMain.handle(AUDIO_ANALYSIS.ANALYZE, async (_event, payload = {}) => {
    await cacheReady;
    const trackId = cleanTrackId(payload.trackId);
    if (!trackId) {
      log('native-request-invalid', { reason: 'missing-track-id' });
      throw new Error('A track ID is required for audio analysis.');
    }
    const existing = cached(trackId);
    if (existing) {
      log('native-cache-hit', { trackId });
      return existing;
    }
    if (inFlight.has(trackId)) {
      log('native-in-flight-reused', { trackId });
      return inFlight.get(trackId);
    }

    const native = addon();
    if (!native) {
      log('native-request-unavailable', { trackId, loadAttempts: nativeLoadAttempts });
      throw new Error('Native audio analysis is unavailable.');
    }
    const samples = floatSamples(payload.samples);
    const sampleRate = Number(payload.sampleRate);
    const duration = Number(payload.duration);
    // The boundary accepts mono Float32 PCM and caps storage at two hours. It
    // intentionally preserves the existing duration/sample-count assumption.
    if (!samples?.length || !Number.isFinite(sampleRate) || sampleRate < 1000 ||
        !Number.isFinite(duration) || duration <= 0 || samples.length > sampleRate * 60 * 60 * 2) {
      log('native-request-invalid', {
        trackId,
        sampleCount: samples?.length || 0,
        sampleRate,
        duration
      });
      throw new Error('Invalid PCM data for audio analysis.');
    }

    const startedAt = Date.now();
    log('native-analysis-start', { trackId, sampleCount: samples.length, sampleRate, duration });
    let nativeTask;
    try {
      nativeTask = Promise.resolve(native.analyze(samples, sampleRate, duration));
    } catch (error) {
      log('native-analysis-failed', { trackId, elapsedMs: Date.now() - startedAt, ...errorDetails(error) });
      throw error;
    }
    const task = nativeTask
      .then((result) => {
        cache.set(trackId, { lastUsed: Date.now(), result });
        while (cache.size > MAX_CACHE_ITEMS) cache.delete(cache.keys().next().value);
        schedulePersist();
        log('native-analysis-ready', {
          trackId,
          elapsedMs: Date.now() - startedAt,
          bpm: Number(result?.bpm) || 0,
          mixInTime: Number(result?.mixInTime) || 0,
          mixOutTime: Number(result?.mixOutTime) || 0,
          contentEndTime: Number(result?.contentEndTime) || 0
        });
        return result;
      })
      .catch((error) => {
        log('native-analysis-failed', { trackId, elapsedMs: Date.now() - startedAt, ...errorDetails(error) });
        throw error;
      })
      .finally(() => inFlight.delete(trackId));
    inFlight.set(trackId, task);
    return task;
  });

  return {
    async stop() {
      // Queued native AsyncWorkers cannot be cancelled. Removing ingress and
      // flushing the current cache is therefore best-effort process teardown.
      clearTimeout(saveTimer);
      saveTimer = null;
      ipcMain.removeHandler(AUDIO_ANALYSIS.AVAILABLE);
      ipcMain.removeHandler(AUDIO_ANALYSIS.GET);
      ipcMain.removeHandler(AUDIO_ANALYSIS.DEBUG);
      ipcMain.removeHandler(AUDIO_ANALYSIS.ANALYZE);
      if (cache.size) await persist().catch(() => {});
    }
  };
}
