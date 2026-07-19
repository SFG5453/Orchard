// Orchestrates whole-track offline analysis across a dedicated renderer worker,
// the sandboxed preload, and the native main-process addon. Audio is never
// processed from an AudioWorklet or other real-time render callback here.

function abortError() {
  return new DOMException('Smart Crossfade analysis was cancelled', 'AbortError');
}

function errorDetails(error) {
  return {
    errorName: String(error?.name || 'Error'),
    errorMessage: String(error?.message || error || 'Unknown error').slice(0, 1000)
  };
}

/**
 * Creates the cached Smart Crossfade analysis pipeline.
 * @param {object} [options]
 * @param {Function} [options.decodeAudio] Whole-file decoder returning AudioBuffer.
 * @param {object} [options.nativeBridge] Narrow preload API for cache/native work.
 * @returns {{analyze: Function, destroy: Function, report: Function}} Owned worker API.
 */
export function createSmartCrossfadeAnalyzer({ decodeAudio, nativeBridge = globalThis.orchardAudioAnalysis } = {}) {
  const cache = new Map();
  const pending = new Map();
  let nextRequestId = 0;
  let worker = null;
  let nativeAvailability = null;

  function report(event, details = {}) {
    if (typeof nativeBridge?.debug !== 'function') return;
    void Promise.resolve()
      .then(() => nativeBridge.debug(event, details))
      .catch(() => {});
  }

  function analysisWorker() {
    if (worker) return worker;
    report('worker-create');
    // Module-relative URL lets Vite emit and relocate the worker for packaged file: loads.
    worker = new Worker(new URL('./smartCrossfadeWorker.js', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => {
      const request = pending.get(data.id);
      if (!request) return;
      pending.delete(data.id);
      request.cleanup?.();
      if (data.error) request.reject(new Error(data.error));
      else request.resolve(data);
    };
    worker.onerror = (event) => {
      const error = new Error(event.message || 'Smart Crossfade worker failed');
      report('worker-crashed', errorDetails(error));
      pending.forEach((request) => {
        request.cleanup?.();
        request.reject(error);
      });
      pending.clear();
      worker.terminate();
      worker = null;
    };
    return worker;
  }

  function nativeAvailable() {
    if (!nativeBridge?.available || !nativeBridge?.get || !nativeBridge?.analyze) {
      report('availability-missing-bridge');
      return Promise.resolve(false);
    }
    if (nativeAvailability) return nativeAvailability;
    report('availability-check-start');
    const availabilityCheck = Promise.resolve()
      .then(() => nativeBridge.available())
      .then((available) => {
        const result = Boolean(available);
        report('availability-check-ready', { available: result });
        return result;
      })
      .catch((error) => {
        report('availability-check-failed', errorDetails(error));
        return false;
      });
    nativeAvailability = availabilityCheck;
    void availabilityCheck.then((available) => {
      if (!available && nativeAvailability === availabilityCheck) nativeAvailability = null;
    });
    return nativeAvailability;
  }

  function workerRequest({ channels, duration, prepareOnly, sampleRate, signal }) {
    if (signal?.aborted) return Promise.reject(abortError());
    const id = ++nextRequestId;
    return new Promise((resolve, reject) => {
      const onAbort = () => {
        if (!pending.delete(id)) return;
        reject(abortError());
      };
      const cleanup = () => signal?.removeEventListener('abort', onAbort);
      pending.set(id, { cleanup, reject, resolve });
      signal?.addEventListener('abort', onAbort, { once: true });
      // Transfer detaches every channel buffer. Abort rejects/forgets the request,
      // but synchronous DSP already running inside the worker cannot be interrupted.
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

  function abortable(promise, signal) {
    // Native AsyncWorker cancellation is not available; abort only stops this caller waiting.
    if (!signal) return promise;
    if (signal.aborted) return Promise.reject(abortError());
    return new Promise((resolve, reject) => {
      const onAbort = () => reject(abortError());
      signal.addEventListener('abort', onAbort, { once: true });
      promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort));
    });
  }

  async function runAnalysis(trackId, url, options = {}) {
    const key = trackId || url;
    if (!key || !url) return null;
    if (cache.has(key)) {
      report('memory-cache-hit', { trackId: key });
      return cache.get(key);
    }
    if (options.signal?.aborted) throw abortError();

    const useNative = await nativeAvailable();
    if (useNative) {
      let stored = null;
      try {
        stored = await nativeBridge.get(key);
      } catch (error) {
        report('disk-cache-read-failed', { trackId: key, ...errorDetails(error) });
      }
      if (stored) {
        report('disk-cache-hit', { trackId: key });
        cache.set(key, stored);
        return stored;
      }
    }

    const decodeStartedAt = Date.now();
    report('decode-start', { trackId: key, nativeAvailable: useNative });
    let buffer;
    try {
      buffer = await decodeAudio?.(url, options.signal);
    } catch (error) {
      report('decode-failed', {
        trackId: key,
        elapsedMs: Date.now() - decodeStartedAt,
        ...errorDetails(error)
      });
      throw error;
    }
    if (!buffer) {
      report('decode-empty', { trackId: key, elapsedMs: Date.now() - decodeStartedAt });
      return null;
    }
    report('decode-ready', {
      trackId: key,
      elapsedMs: Date.now() - decodeStartedAt,
      duration: Number(buffer.duration) || Number(options.duration) || 0,
      sampleRate: Number(buffer.sampleRate) || 0,
      channels: Number(buffer.numberOfChannels) || 0
    });
    if (options.signal?.aborted) throw abortError();

    // Web Audio exposes planar Float32 data owned by AudioBuffer. Copy each plane
    // before transfer so ownership can move safely to the preparation worker.
    const channels = Array.from({ length: buffer.numberOfChannels }, (_, index) =>
      new Float32Array(buffer.getChannelData(index)).buffer
    );
    const duration = Number(buffer.duration) || Number(options.duration) || 0;
    let analysis;
    if (useNative) {
      const prepareStartedAt = Date.now();
      report('native-prepare-start', { trackId: key });
      let prepared;
      try {
        ({ prepared } = await workerRequest({
          channels,
          duration,
          prepareOnly: true,
          sampleRate: buffer.sampleRate,
          signal: options.signal
        }));
      } catch (error) {
        report('native-prepare-failed', {
          trackId: key,
          elapsedMs: Date.now() - prepareStartedAt,
          ...errorDetails(error)
        });
        throw error;
      }
      report('native-prepare-ready', {
        trackId: key,
        elapsedMs: Date.now() - prepareStartedAt,
        sampleRate: Number(prepared.sampleRate) || 0,
        sampleCount: prepared.samples?.byteLength / Float32Array.BYTES_PER_ELEMENT || 0
      });
      try {
        const nativeStartedAt = Date.now();
        report('native-call-start', { trackId: key });
        // Electron IPC structured-clones the prepared mono buffer; the addon then
        // snapshots it once more before libuv worker-pool analysis.
        analysis = await abortable(nativeBridge.analyze(
          key,
          prepared.samples,
          prepared.sampleRate,
          prepared.duration
        ), options.signal);
        report('native-call-ready', { trackId: key, elapsedMs: Date.now() - nativeStartedAt });
      } catch (error) {
        if (error?.name === 'AbortError') throw error;
        report('native-call-failed', { trackId: key, ...errorDetails(error) });
        const fallbackStartedAt = Date.now();
        report('fallback-start', { trackId: key, reason: 'native-call-failed' });
        try {
          const fallback = await workerRequest({
            channels: [prepared.samples],
            duration,
            prepareOnly: false,
            sampleRate: prepared.sampleRate,
            signal: options.signal
          });
          analysis = fallback.result;
        } catch (fallbackError) {
          report('fallback-failed', {
            trackId: key,
            elapsedMs: Date.now() - fallbackStartedAt,
            ...errorDetails(fallbackError)
          });
          throw fallbackError;
        }
        report('fallback-ready', { trackId: key, elapsedMs: Date.now() - fallbackStartedAt });
      }
    } else {
      const fallbackStartedAt = Date.now();
      report('fallback-start', { trackId: key, reason: 'native-unavailable' });
      try {
        const fallback = await workerRequest({
          channels,
          duration,
          prepareOnly: false,
          sampleRate: buffer.sampleRate,
          signal: options.signal
        });
        analysis = fallback.result;
      } catch (error) {
        report('fallback-failed', {
          trackId: key,
          elapsedMs: Date.now() - fallbackStartedAt,
          ...errorDetails(error)
        });
        throw error;
      }
      report('fallback-ready', { trackId: key, elapsedMs: Date.now() - fallbackStartedAt });
    }
    cache.set(key, analysis);
    if (cache.size > 80) cache.delete(cache.keys().next().value);
    return analysis;
  }

  async function analyze(trackId, url, options = {}) {
    const key = trackId || url;
    const startedAt = Date.now();
    if (key && url) report('analysis-start', { trackId: key });
    try {
      const analysis = await runAnalysis(trackId, url, options);
      if (key && url) {
        report(analysis ? 'analysis-ready' : 'analysis-empty', {
          trackId: key,
          elapsedMs: Date.now() - startedAt,
          bpm: Number(analysis?.bpm) || 0,
          mixOutTime: Number(analysis?.mixOutTime) || 0,
          contentEndTime: Number(analysis?.contentEndTime) || 0
        });
      }
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

  function destroy() {
    // Termination releases the worker and transferred buffers. Native jobs that
    // crossed IPC already remain owned by the main process until they finish.
    worker?.terminate();
    worker = null;
    pending.forEach((request) => {
      request.cleanup?.();
      request.reject(abortError());
    });
    pending.clear();
    cache.clear();
  }

  return { analyze, destroy, report };
}
