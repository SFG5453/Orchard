import { createRequire } from 'node:module';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';
import {
  AUDIO_ANALYSIS_VERSION,
  isValidLocalAnalysis,
  localAnalysisWithSource,
  safeAudioAnalysisDiagnostics
} from '../../shared/audioAnalysis.js';
import {
  SMART_CROSSFADE_HEAD_SECONDS,
  SMART_CROSSFADE_TAIL_SECONDS
} from '../../shared/aiAudioAnalysis.js';
import { createOnnxSmartCrossfadeAnalyzer } from './onnxSmartCrossfade.js';

// Owns native-addon loading, analysis request de-duplication, and the persisted
// result cache. `stop()` removes every IPC handler and flushes pending cache data.

const require = createRequire(import.meta.url);
const { AUDIO_ANALYSIS } = IPC_CHANNELS;
const CACHE_VERSION = AUDIO_ANALYSIS_VERSION;
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
  modelSearchPaths = [],
  loadNativeAddon = require,
  onnxAnalyzerFactory = createOnnxSmartCrossfadeAnalyzer,
  logger = stdoutLogger
}) {
  const cache = new Map();
  const inFlight = new Map();
  const aiInFlight = new Map();
  let nativeAddon = null;
  let nativeLoadAttempts = 0;
  let saveTimer = null;
  let savePromise = Promise.resolve();
  let cacheGeneration = 0;

  function log(event, details = {}) {
    try {
      logger(event, safeAudioAnalysisDiagnostics(details));
    } catch {
      // Diagnostics must never interrupt playback or analysis.
    }
  }

  const onnxAnalyzer = onnxAnalyzerFactory({
    modelSearchPaths,
    logger: (event, details) => log(event, details)
  });

  async function aiCapabilities() {
    try {
      return await onnxAnalyzer.status();
    } catch (error) {
      log('onnx-status-failed', errorDetails(error));
      return {
        available: false,
        pipeline: '',
        modelSignature: '',
        sampleRate: 0,
        channels: 0
      };
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
        if (!trackId || !isValidLocalAnalysis(item?.result)) return;
        cache.set(trackId, {
          lastUsed: Number(item.lastUsed) || 0,
          result: item.result
        });
      });
    })
    .catch(() => {});

  function cached(trackId, modelSignature = '') {
    const entry = cache.get(trackId);
    if (!entry) return null;
    if (!isValidLocalAnalysis(entry.result)) {
      cache.delete(trackId);
      return null;
    }
    if (modelSignature && (
      entry.result.aiModelSignature !== modelSignature ||
      entry.result.aiAnalysisStatus !== 'ready'
    )) {
      return null;
    }
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

  ipcMain.handle(AUDIO_ANALYSIS.CLEAR, async () => {
    await cacheReady;
    const cleared = cache.size;
    cacheGeneration += 1;
    clearTimeout(saveTimer);
    saveTimer = null;
    cache.clear();
    await persist();
    log('cache-cleared', { cleared });
    return { cleared };
  });

  ipcMain.handle(AUDIO_ANALYSIS.GET, async (_event, value) => {
    await cacheReady;
    const request = value && typeof value === 'object'
      ? value
      : { trackId: value, aiEnabled: false };
    const trackId = cleanTrackId(request.trackId);
    const aiEnabled = request.aiEnabled === true;
    const ai = aiEnabled ? await aiCapabilities() : { available: false, modelSignature: '' };
    const requiredModelSignature = ai.available ? ai.modelSignature : '';
    const result = trackId ? cached(trackId, requiredModelSignature) : null;
    log('cache-result', {
      trackId,
      hit: Boolean(result),
      aiEnabled,
      aiModelSignature: requiredModelSignature
    });
    return result;
  });

  ipcMain.handle(AUDIO_ANALYSIS.AI_CAPABILITIES, async () => {
    const capabilities = await aiCapabilities();
    log('onnx-capabilities', capabilities);
    return capabilities;
  });

  ipcMain.handle(AUDIO_ANALYSIS.AI_ANALYZE, async (_event, payload = {}) => {
    const trackId = cleanTrackId(payload.trackId);
    const capabilities = await aiCapabilities();
    if (!trackId) throw new Error('A track ID is required for ONNX audio analysis.');
    if (!capabilities.available) throw new Error('Smart Crossfade ONNX models are unavailable.');
    if (aiInFlight.has(trackId)) {
      log('onnx-in-flight-reused', { trackId });
      return aiInFlight.get(trackId);
    }
    const channels = Array.isArray(payload.channels)
      ? payload.channels.map(floatSamples)
      : [];
    const sampleRate = Number(payload.sampleRate);
    const duration = Number(payload.duration);
    const headDuration = Number(payload.headDuration) || 0;
    const tailDuration = Number(payload.tailDuration) || 0;
    const hasEdgeWindows = headDuration !== 0 || tailDuration !== 0;
    const maximumHeadDuration = SMART_CROSSFADE_HEAD_SECONDS;
    const maximumTailDuration = SMART_CROSSFADE_TAIL_SECONDS;
    const expectedWindowSamples = Math.round((headDuration + tailDuration) * sampleRate);
    const maximumSamples = sampleRate * 60 * 30;
    if (!channels.length || channels.length > 2 || channels.some((channel) => !channel?.length) ||
        channels.some((channel) => channel.length !== channels[0].length) ||
        !Number.isFinite(sampleRate) || sampleRate < 8_000 || sampleRate > 192_000 ||
        !Number.isFinite(duration) || duration <= 0 || duration > 60 * 30 ||
        !Number.isFinite(maximumSamples) || channels[0].length > maximumSamples ||
        (hasEdgeWindows && (
          headDuration <= 0 ||
          tailDuration <= 0 ||
          headDuration > maximumHeadDuration ||
          tailDuration > maximumTailDuration ||
          headDuration + tailDuration >= duration ||
          channels[0].length !== expectedWindowSamples
        ))) {
      log('onnx-request-invalid', {
        trackId,
        channels: channels.length,
        sampleCount: channels[0]?.length || 0,
        sampleRate,
        duration,
        headDuration,
        tailDuration
      });
      throw new Error('Invalid PCM data for Smart Crossfade ONNX analysis.');
    }

    const startedAt = Date.now();
    log('onnx-analysis-start', {
      trackId,
      channels: channels.length,
      sampleCount: channels[0].length,
      sampleRate,
      duration,
      headDuration,
      tailDuration,
      modelSignature: capabilities.modelSignature
    });
    const task = Promise.resolve()
      .then(() => onnxAnalyzer.analyze({
        channels,
        duration,
        headDuration,
        sampleRate,
        tailDuration
      }))
      .then((result) => {
        log('onnx-analysis-ready', {
          trackId,
          elapsedMs: Date.now() - startedAt,
          modelSignature: capabilities.modelSignature,
          structureConfidence: Number(result?.aiStructureConfidence) || 0,
          phraseCount: result?.phrases?.length || 0
        });
        return result;
      })
      .catch((error) => {
        log('onnx-analysis-failed', {
          trackId,
          elapsedMs: Date.now() - startedAt,
          ...errorDetails(error)
        });
        throw error;
      })
      .finally(() => aiInFlight.delete(trackId));
    aiInFlight.set(trackId, task);
    return task;
  });

  ipcMain.handle(AUDIO_ANALYSIS.DEBUG, async (_event, payload = {}) => {
    const event = String(payload?.event || 'unknown').replace(/[^a-z0-9:_-]/gi, '').slice(0, 100);
    const details = payload?.details && typeof payload.details === 'object'
      ? payload.details
      : {};
    log(`renderer:${event}`, safeAudioAnalysisDiagnostics(details));
    return true;
  });

  ipcMain.handle(AUDIO_ANALYSIS.STORE, async (_event, payload = {}) => {
    await cacheReady;
    const trackId = cleanTrackId(payload.trackId);
    const source = ['local-native', 'local-onnx'].includes(payload?.result?.analysisSource)
      ? payload.result.analysisSource
      : 'local-worker';
    const result = localAnalysisWithSource(payload.result, source);
    if (!trackId || !result) {
      log('cache-store-invalid', { trackId, bpm: Number(payload?.result?.bpm) || 0 });
      throw new Error('A complete local audio analysis is required for caching.');
    }
    cache.delete(trackId);
    cache.set(trackId, { lastUsed: Date.now(), result });
    while (cache.size > MAX_CACHE_ITEMS) cache.delete(cache.keys().next().value);
    schedulePersist();
    log('cache-store-ready', { trackId, bpm: result.bpm, analysisSource: result.analysisSource });
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
    const requestGeneration = cacheGeneration;
    log('native-analysis-start', { trackId, sampleCount: samples.length, sampleRate, duration });
    let nativeTask;
    try {
      nativeTask = Promise.resolve(native.analyze(samples, sampleRate, duration));
    } catch (error) {
      log('native-analysis-failed', { trackId, elapsedMs: Date.now() - startedAt, ...errorDetails(error) });
      throw error;
    }
    const task = nativeTask
      .then((rawResult) => {
        const result = localAnalysisWithSource(rawResult, 'local-native');
        if (!result) {
          log('native-analysis-invalid', { trackId, bpm: Number(rawResult?.bpm) || 0 });
          throw new Error('Native audio analysis returned an invalid BPM.');
        }
        if (requestGeneration === cacheGeneration) {
          cache.set(trackId, { lastUsed: Date.now(), result });
          while (cache.size > MAX_CACHE_ITEMS) cache.delete(cache.keys().next().value);
          schedulePersist();
        }
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
      ipcMain.removeHandler(AUDIO_ANALYSIS.AI_CAPABILITIES);
      ipcMain.removeHandler(AUDIO_ANALYSIS.AI_ANALYZE);
      ipcMain.removeHandler(AUDIO_ANALYSIS.CLEAR);
      ipcMain.removeHandler(AUDIO_ANALYSIS.GET);
      ipcMain.removeHandler(AUDIO_ANALYSIS.DEBUG);
      ipcMain.removeHandler(AUDIO_ANALYSIS.STORE);
      ipcMain.removeHandler(AUDIO_ANALYSIS.ANALYZE);
      if (cache.size) await persist().catch(() => {});
    }
  };
}
