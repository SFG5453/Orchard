// Orchestrates whole-track offline analysis across a bounded preparation queue,
// the sandboxed preload, a dedicated renderer worker, and the native addon.
import {
  localAnalysisWithSource,
  safeAudioAnalysisDiagnostics
} from '../../../shared/audioAnalysis.js';
import {
  markAiAudioAnalysisUnavailable,
  mergeAiAudioAnalysis,
  SMART_CROSSFADE_HEAD_SECONDS,
  SMART_CROSSFADE_TAIL_SECONDS,
  withoutAiAudioAnalysis
} from '../../../shared/aiAudioAnalysis.js';

export const ANALYSIS_PREPARATION_CONCURRENCY = 4;
export const ANALYSIS_PRIORITIES = Object.freeze({ current: 0, next: 1, background: 2 });

const MAX_MEMORY_CACHE_ITEMS = 80;
const DEFAULT_RETRY_LIMIT = 2;
const DEFAULT_RETRY_BASE_MS = 1_000;
const DEFAULT_RETRY_MAX_MS = 15_000;

export function aiAnalysisPcmWindows(buffer) {
  const sampleRate = Number(buffer?.sampleRate) || 0;
  const duration = Number(buffer?.duration) || 0;
  const channelCount = Math.min(2, Number(buffer?.numberOfChannels) || 0);
  if (sampleRate <= 0 || channelCount <= 0) {
    throw new Error('Decoded audio is unavailable for Smart Crossfade AI analysis.');
  }
  const sourceChannels = Array.from({ length: channelCount }, (_, index) =>
    buffer.getChannelData(index)
  );
  const sampleCount = Math.min(...sourceChannels.map((channel) => channel.length));
  const headSamples = Math.min(
    sampleCount,
    Math.round(SMART_CROSSFADE_HEAD_SECONDS * sampleRate)
  );
  const tailSamples = Math.min(
    Math.max(0, sampleCount - headSamples),
    Math.round(SMART_CROSSFADE_TAIL_SECONDS * sampleRate)
  );
  const trimToEdges = sampleCount > headSamples + tailSamples && tailSamples > 0;

  if (!trimToEdges) {
    return {
      channels: sourceChannels.map((channel) =>
        new Float32Array(channel.subarray(0, sampleCount)).buffer
      ),
      duration,
      headDuration: 0,
      sampleRate,
      tailDuration: 0
    };
  }

  return {
    channels: sourceChannels.map((channel) => {
      const window = new Float32Array(headSamples + tailSamples);
      window.set(channel.subarray(0, headSamples));
      window.set(channel.subarray(sampleCount - tailSamples, sampleCount), headSamples);
      return window.buffer;
    }),
    duration,
    headDuration: headSamples / sampleRate,
    sampleRate,
    tailDuration: tailSamples / sampleRate
  };
}

function abortError() {
  return new DOMException('Smart Crossfade analysis was cancelled', 'AbortError');
}

function errorDetails(error) {
  return safeAudioAnalysisDiagnostics({
    errorName: String(error?.name || 'Error'),
    errorMessage: String(error?.message || error || 'Unknown error')
  });
}

function transientNetworkStatus(error) {
  const direct = Number(error?.status || error?.statusCode);
  if (direct === 403 || direct === 429 || (direct >= 500 && direct <= 599)) return direct;
  const match = String(error?.message || error || '').match(/(?:HTTP|status)\s*(403|429|5\d\d)\b/i);
  return Number(match?.[1]) || 0;
}

function cancellableWait(promise, signal) {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(abortError());
  return new Promise((resolve, reject) => {
    const onAbort = () => reject(abortError());
    signal.addEventListener('abort', onAbort, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort));
  });
}

function pause(delay, signal) {
  if (signal?.aborted) return Promise.reject(abortError());
  return new Promise((resolve, reject) => {
    const cleanup = () => signal?.removeEventListener('abort', onAbort);
    const timer = setTimeout(() => {
      cleanup();
      resolve();
    }, Math.max(0, delay));
    const onAbort = () => {
      clearTimeout(timer);
      cleanup();
      reject(abortError());
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * Creates the cached Smart Crossfade analysis pipeline.
 * Cached reads bypass the queue. Uncached jobs are deduplicated by track ID,
 * and caller cancellation only stops that caller from awaiting the shared job.
 */
export function createSmartCrossfadeAnalyzer({
  decodeAudio,
  nativeBridge = globalThis.orchardAudioAnalysis,
  workerFactory = () => new Worker(new URL('./smartCrossfadeWorker.js', import.meta.url), { type: 'module' }),
  maxActiveJobs = ANALYSIS_PREPARATION_CONCURRENCY,
  retryLimit = DEFAULT_RETRY_LIMIT,
  retryBaseMs = DEFAULT_RETRY_BASE_MS,
  retryMaxMs = DEFAULT_RETRY_MAX_MS,
  random = Math.random,
  aiEnabled = false
} = {}) {
  const cache = new Map();
  const cacheLookups = new Map();
  const jobs = new Map();
  const queued = [];
  const activeControllers = new Set();
  const workerRequests = new Map();
  let nextRequestId = 0;
  let nextSequence = 0;
  let activeJobs = 0;
  let worker = null;
  let nativeAvailability = null;
  let aiCapabilitiesPromise = null;
  let aiEnabledState = aiEnabled === true;
  let analysisModeGeneration = 0;
  let destroyed = false;
  let pumpTimer = 0;
  let networkFailureStreak = 0;
  let networkFailureGeneration = 0;
  let networkBackoffUntil = 0;

  const concurrency = Math.max(1, Math.min(ANALYSIS_PREPARATION_CONCURRENCY, Number(maxActiveJobs) || 1));

  function report(event, details = {}) {
    if (typeof nativeBridge?.debug !== 'function') return;
    const safeDetails = safeAudioAnalysisDiagnostics(details);
    void Promise.resolve()
      .then(() => nativeBridge.debug(event, safeDetails))
      .catch(() => {});
  }

  function analysisWorker() {
    if (worker) return worker;
    report('worker-create');
    worker = workerFactory();
    worker.onmessage = ({ data }) => {
      const request = workerRequests.get(data.id);
      if (!request) return;
      workerRequests.delete(data.id);
      request.cleanup();
      if (data.error) request.reject(new Error(data.error));
      else request.resolve(data);
    };
    worker.onerror = (event) => {
      const error = new Error(event.message || 'Smart Crossfade worker failed');
      report('worker-crashed', errorDetails(error));
      workerRequests.forEach((request) => {
        request.cleanup();
        request.reject(error);
      });
      workerRequests.clear();
      worker.terminate();
      worker = null;
    };
    return worker;
  }

  function nativeAvailable() {
    if (!nativeBridge?.available || !nativeBridge?.analyze) {
      report('availability-missing-bridge');
      return Promise.resolve(false);
    }
    if (nativeAvailability) return nativeAvailability;
    report('availability-check-start');
    const check = Promise.resolve()
      .then(() => nativeBridge.available())
      .then((available) => {
        report('availability-check-ready', { available: Boolean(available) });
        return Boolean(available);
      })
      .catch((error) => {
        report('availability-check-failed', errorDetails(error));
        return false;
      });
    nativeAvailability = check;
    void check.then((available) => {
      if (!available && nativeAvailability === check) nativeAvailability = null;
    });
    return check;
  }

  function aiCapabilities() {
    if (typeof nativeBridge?.aiCapabilities !== 'function' ||
        typeof nativeBridge?.analyzeAi !== 'function') {
      return Promise.resolve({ available: false });
    }
    if (aiCapabilitiesPromise) return aiCapabilitiesPromise;
    aiCapabilitiesPromise = Promise.resolve()
      .then(() => nativeBridge.aiCapabilities())
      .then((capabilities) => ({
        ...capabilities,
        available: Boolean(capabilities?.available),
        modelSignature: String(capabilities?.modelSignature || '')
      }))
      .catch((error) => {
        report('onnx-capabilities-failed', errorDetails(error));
        return { available: false };
      });
    return aiCapabilitiesPromise;
  }

  function workerRequest({ channels, duration, prepareOnly, sampleRate, signal }) {
    if (signal?.aborted) return Promise.reject(abortError());
    const id = ++nextRequestId;
    return new Promise((resolve, reject) => {
      const onAbort = () => {
        if (!workerRequests.delete(id)) return;
        reject(abortError());
      };
      const cleanup = () => signal?.removeEventListener('abort', onAbort);
      workerRequests.set(id, { cleanup, reject, resolve });
      signal?.addEventListener('abort', onAbort, { once: true });
      analysisWorker().postMessage({
        id,
        channels,
        sampleRate,
        duration,
        prepareOnly,
        targetSampleRate: 11025
      }, channels);
    });
  }

  async function readCached(key) {
    if (cache.has(key)) {
      const stored = cache.get(key);
      if (!aiEnabledState || stored.aiAnalysisStatus !== 'unavailable') {
        report('memory-cache-hit', { trackId: key });
        return stored;
      }
      cache.delete(key);
      report('memory-cache-ai-retry', { trackId: key });
    }
    if (typeof nativeBridge?.get !== 'function') return null;
    const generation = analysisModeGeneration;
    const aiEnabledForLookup = aiEnabledState;
    const lookupKey = `${generation}:${key}`;
    if (cacheLookups.has(lookupKey)) return cacheLookups.get(lookupKey);
    const lookup = Promise.resolve()
      .then(() => nativeBridge.get(key, aiEnabledForLookup))
      .then(async (stored) => {
        if (!stored) return null;
        let valid = localAnalysisWithSource(stored, 'cache');
        if (!valid) {
          report('disk-cache-invalid', { trackId: key, bpm: Number(stored?.bpm) || 0 });
          return null;
        }
        if (!aiEnabledForLookup) {
          valid = withoutAiAudioAnalysis(valid);
        } else {
          const capabilities = await aiCapabilities();
          if (!capabilities.available) {
            valid = withoutAiAudioAnalysis(valid);
          } else if (
            valid.aiModelSignature !== capabilities.modelSignature ||
            valid.aiAnalysisStatus !== 'ready'
          ) {
            report(valid.aiModelSignature !== capabilities.modelSignature
              ? 'disk-cache-ai-mismatch'
              : 'disk-cache-ai-incomplete', {
              trackId: key,
              cachedModelSignature: valid.aiModelSignature || '',
              modelSignature: capabilities.modelSignature
            });
            return null;
          }
        }
        if (generation !== analysisModeGeneration) return null;
        report('disk-cache-hit', {
          trackId: key,
          cachedBpmSource: valid.cachedBpmSource || valid.analysisSource,
          aiEnabled: aiEnabledForLookup
        });
        cache.set(key, valid);
        return valid;
      })
      .catch((error) => {
        report('disk-cache-read-failed', { trackId: key, ...errorDetails(error) });
        return null;
      })
      .finally(() => cacheLookups.delete(lookupKey));
    cacheLookups.set(lookupKey, lookup);
    return lookup;
  }

  function remember(key, analysis) {
    cache.delete(key);
    cache.set(key, analysis);
    while (cache.size > MAX_MEMORY_CACHE_ITEMS) cache.delete(cache.keys().next().value);
  }

  function noteNetworkFailure(status) {
    networkFailureStreak += 1;
    networkFailureGeneration += 1;
    const exponential = Math.min(retryMaxMs, retryBaseMs * (2 ** Math.min(6, networkFailureStreak - 1)));
    const delay = Math.max(1, Math.round(exponential * (0.75 + Math.max(0, Math.min(1, random())) * 0.5)));
    if (networkFailureStreak >= 2) networkBackoffUntil = Math.max(networkBackoffUntil, Date.now() + delay);
    report('network-backoff', { status, failureStreak: networkFailureStreak, delayMs: delay });
    return delay;
  }

  async function decodeWithBackoff(key, url, signal) {
    for (let attempt = 0; attempt <= retryLimit; attempt += 1) {
      const waitMs = Math.max(0, networkBackoffUntil - Date.now());
      if (waitMs) await pause(waitMs, signal);
      const generationAtStart = networkFailureGeneration;
      const startedAt = Date.now();
      report('decode-start', { trackId: key, attempt: attempt + 1 });
      try {
        const buffer = await decodeAudio?.(url, signal);
        if (!buffer) throw new Error('Audio decoding returned no buffer');
        if (generationAtStart === networkFailureGeneration) {
          networkFailureStreak = 0;
          networkBackoffUntil = 0;
        }
        report('decode-ready', {
          trackId: key,
          attempt: attempt + 1,
          elapsedMs: Date.now() - startedAt,
          duration: Number(buffer.duration) || 0,
          sampleRate: Number(buffer.sampleRate) || 0,
          channels: Number(buffer.numberOfChannels) || 0
        });
        return buffer;
      } catch (error) {
        if (error?.name === 'AbortError') throw error;
        const status = transientNetworkStatus(error);
        report('decode-failed', {
          trackId: key,
          attempt: attempt + 1,
          elapsedMs: Date.now() - startedAt,
          status: status || 0,
          ...errorDetails(error)
        });
        if (!status || attempt >= retryLimit) throw error;
        await pause(noteNetworkFailure(status), signal);
      }
    }
    return null;
  }

  async function fallbackAnalysis(key, channels, sampleRate, duration, signal, reason) {
    const startedAt = Date.now();
    report('fallback-start', { trackId: key, reason });
    try {
      const fallback = await workerRequest({
        channels,
        duration,
        prepareOnly: false,
        sampleRate,
        signal
      });
      const analysis = localAnalysisWithSource(fallback.result, 'local-worker');
      if (!analysis) {
        report('fallback-invalid', { trackId: key, bpm: Number(fallback.result?.bpm) || 0 });
        throw new Error('Worker audio analysis returned an invalid BPM');
      }
      if (typeof nativeBridge?.store === 'function') {
        try {
          await nativeBridge.store(key, analysis);
          report('worker-cache-stored', { trackId: key });
        } catch (error) {
          report('worker-cache-store-failed', { trackId: key, ...errorDetails(error) });
        }
      }
      report('fallback-ready', { trackId: key, elapsedMs: Date.now() - startedAt, bpm: analysis.bpm });
      return analysis;
    } catch (error) {
      report('fallback-failed', { trackId: key, elapsedMs: Date.now() - startedAt, ...errorDetails(error) });
      throw error;
    }
  }

  async function enrichWithAi(key, buffer, baseAnalysis, signal) {
    if (!aiEnabledState) return withoutAiAudioAnalysis(baseAnalysis);
    const capabilities = await aiCapabilities();
    if (!capabilities.available || signal.aborted || !aiEnabledState) {
      if (signal.aborted) throw abortError();
      return withoutAiAudioAnalysis(baseAnalysis);
    }
    const startedAt = Date.now();
    report('onnx-call-start', {
      trackId: key,
      modelSignature: capabilities.modelSignature
    });
    try {
      const pcm = aiAnalysisPcmWindows(buffer);
      const neural = await cancellableWait(nativeBridge.analyzeAi(
        key,
        pcm.channels,
        pcm.sampleRate,
        pcm.duration || Number(baseAnalysis.duration) || 0,
        {
          headDuration: pcm.headDuration,
          tailDuration: pcm.tailDuration
        }
      ), signal);
      const merged = mergeAiAudioAnalysis(baseAnalysis, neural, capabilities.modelSignature);
      if (typeof nativeBridge?.store === 'function') {
        try {
          await nativeBridge.store(key, merged);
        } catch (error) {
          report('onnx-cache-store-failed', { trackId: key, ...errorDetails(error) });
        }
      }
      report('onnx-call-ready', {
        trackId: key,
        elapsedMs: Date.now() - startedAt,
        modelSignature: capabilities.modelSignature,
        structureConfidence: Number(merged.aiStructureConfidence) || 0,
        phraseCount: merged.phrases?.length || 0
      });
      return aiEnabledState ? merged : withoutAiAudioAnalysis(merged);
    } catch (error) {
      if (error?.name === 'AbortError') throw error;
      const fallback = markAiAudioAnalysisUnavailable(baseAnalysis, capabilities.modelSignature);
      report('onnx-call-failed', {
        trackId: key,
        elapsedMs: Date.now() - startedAt,
        modelSignature: capabilities.modelSignature,
        ...errorDetails(error)
      });
      return aiEnabledState ? fallback : withoutAiAudioAnalysis(fallback);
    }
  }

  async function analyzeBuffer(key, buffer, durationHint, signal) {
    if (signal.aborted) throw abortError();
    const channels = Array.from({ length: buffer.numberOfChannels }, (_, index) =>
      new Float32Array(buffer.getChannelData(index)).buffer
    );
    const duration = Number(buffer.duration) || Number(durationHint) || 0;
    const useNative = await nativeAvailable();
    let baseAnalysis;
    if (!useNative) {
      baseAnalysis = await fallbackAnalysis(
        key,
        channels,
        buffer.sampleRate,
        duration,
        signal,
        'native-unavailable'
      );
    } else {
      const prepareStartedAt = Date.now();
      report('native-prepare-start', { trackId: key });
      const { prepared } = await workerRequest({
        channels,
        duration,
        prepareOnly: true,
        sampleRate: buffer.sampleRate,
        signal
      });
      report('native-prepare-ready', {
        trackId: key,
        elapsedMs: Date.now() - prepareStartedAt,
        sampleRate: Number(prepared.sampleRate) || 0,
        sampleCount: prepared.samples?.byteLength / Float32Array.BYTES_PER_ELEMENT || 0
      });

      try {
        const startedAt = Date.now();
        report('native-call-start', { trackId: key });
        const raw = await cancellableWait(nativeBridge.analyze(
          key,
          prepared.samples,
          prepared.sampleRate,
          prepared.duration
        ), signal);
        const analysis = localAnalysisWithSource(raw, 'local-native');
        if (!analysis) {
          report('native-call-invalid', { trackId: key, bpm: Number(raw?.bpm) || 0 });
          throw new Error('Native audio analysis returned an invalid BPM');
        }
        report('native-call-ready', { trackId: key, elapsedMs: Date.now() - startedAt, bpm: analysis.bpm });
        baseAnalysis = analysis;
      } catch (error) {
        if (error?.name === 'AbortError') throw error;
        report('native-call-failed', { trackId: key, ...errorDetails(error) });
        baseAnalysis = await fallbackAnalysis(
          key,
          [prepared.samples],
          prepared.sampleRate,
          duration,
          signal,
          'native-call-failed'
        );
      }
    }
    return enrichWithAi(key, buffer, baseAnalysis, signal);
  }

  async function execute(job) {
    const controller = new AbortController();
    activeControllers.add(controller);
    try {
      let url = job.urlSource;
      if (typeof url === 'function') {
        report('stream-resolve-start', { trackId: job.key });
        url = await url({ signal: controller.signal });
        report('stream-resolve-ready', { trackId: job.key, hasStream: Boolean(url) });
      }
      if (!url) throw new Error('No authenticated audio stream was resolved for analysis');
      const buffer = await decodeWithBackoff(job.key, url, controller.signal);
      const analysis = await analyzeBuffer(job.key, buffer, job.duration, controller.signal);
      if (job.generation === analysisModeGeneration &&
          (!aiEnabledState || analysis.aiAnalysisStatus !== 'unavailable')) {
        remember(job.key, analysis);
      }
      return analysis;
    } finally {
      activeControllers.delete(controller);
    }
  }

  function schedulePump(delay) {
    if (pumpTimer || destroyed) return;
    pumpTimer = setTimeout(() => {
      pumpTimer = 0;
      pump();
    }, Math.max(1, delay));
  }

  function pump() {
    if (destroyed) return;
    const backoff = networkBackoffUntil - Date.now();
    if (backoff > 0) {
      schedulePump(backoff);
      return;
    }
    queued.sort((left, right) => left.priority - right.priority || left.sequence - right.sequence);
    while (activeJobs < concurrency && queued.length) {
      const job = queued.shift();
      job.state = 'active';
      activeJobs += 1;
      report('queue-start', {
        trackId: job.key,
        priority: job.priority,
        activeJobs,
        queuedJobs: queued.length,
        maximumActiveJobs: concurrency
      });
      void execute(job)
        .then(job.resolve, job.reject)
        .finally(() => {
          activeJobs -= 1;
          if (jobs.get(job.key) === job) jobs.delete(job.key);
          pump();
        });
    }
  }

  function enqueue(key, urlSource, options) {
    const priority = Math.max(ANALYSIS_PRIORITIES.current, Number(options.priority) || 0);
    const existing = jobs.get(key);
    if (existing) {
      if (priority < existing.priority) {
        existing.priority = priority;
        report('queue-promoted', { trackId: key, priority });
        if (existing.state === 'queued') pump();
      }
      report('queue-deduplicated', { trackId: key, state: existing.state });
      return existing.promise;
    }

    let resolve;
    let reject;
    const promise = new Promise((resolvePromise, rejectPromise) => {
      resolve = resolvePromise;
      reject = rejectPromise;
    });
    const job = {
      key,
      urlSource,
      duration: options.duration,
      priority,
      sequence: ++nextSequence,
      generation: analysisModeGeneration,
      state: 'queued',
      promise,
      resolve,
      reject
    };
    jobs.set(key, job);
    queued.push(job);
    report('queue-add', { trackId: key, priority, queuedJobs: queued.length });
    pump();
    return promise;
  }

  async function prepare(trackId, urlSource, options) {
    const key = String(trackId || '').trim();
    if (!key) return null;
    const stored = await readCached(key);
    if (stored) return stored;
    if (destroyed) throw abortError();
    return enqueue(key, urlSource, options);
  }

  async function analyze(trackId, urlSource, options = {}) {
    const key = String(trackId || '').trim();
    const startedAt = Date.now();
    if (key) report('analysis-start', { trackId: key, priority: Number(options.priority) || 0 });
    try {
      const analysis = await cancellableWait(prepare(key, urlSource, options), options.signal);
      if (key) report(analysis ? 'analysis-ready' : 'analysis-empty', {
        trackId: key,
        elapsedMs: Date.now() - startedAt,
        bpm: Number(analysis?.bpm) || 0,
        bpmSource: analysis?.bpmSource || '',
        mixOutTime: Number(analysis?.mixOutTime) || 0
      });
      return analysis;
    } catch (error) {
      report(error?.name === 'AbortError' ? 'analysis-aborted' : 'analysis-failed', {
        trackId: key,
        elapsedMs: Date.now() - startedAt,
        ...errorDetails(error)
      });
      throw error;
    }
  }

  async function clear() {
    analysisModeGeneration += 1;
    cache.clear();
    cacheLookups.clear();
    queued.splice(0).forEach((job) => {
      job.reject(abortError());
    });
    jobs.clear();
    activeControllers.forEach((controller) => controller.abort());
    const result = typeof nativeBridge?.clear === 'function'
      ? await nativeBridge.clear()
      : { cleared: 0 };
    report('analysis-cache-cleared', { cleared: Number(result?.cleared) || 0 });
    return result;
  }

  function destroy() {
    destroyed = true;
    clearTimeout(pumpTimer);
    pumpTimer = 0;
    queued.splice(0).forEach((job) => {
      jobs.delete(job.key);
      job.reject(abortError());
    });
    activeControllers.forEach((controller) => controller.abort());
    worker?.terminate();
    worker = null;
    workerRequests.forEach((request) => {
      request.cleanup();
      request.reject(abortError());
    });
    workerRequests.clear();
    cache.clear();
  }

  function setAiEnabled(value) {
    const enabled = value === true;
    if (enabled === aiEnabledState) return;
    aiEnabledState = enabled;
    analysisModeGeneration += 1;
    cache.clear();
    cacheLookups.clear();
    report('onnx-setting-changed', { enabled });
  }

  return { analyze, clear, destroy, report, setAiEnabled };
}
