import { ref } from 'vue';
import { createSmartCrossfadeAnalyzer } from '../../audio/crossfade/smartCrossfadeAnalysis.js';
import {
  createBpmMetadataClient,
  mergeBpmMetadata
} from '../../audio/crossfade/bpmMetadata.js';

function errorDetails(error) {
  return {
    errorName: String(error?.name || 'Error'),
    errorMessage: String(error?.message || error || 'Unknown error').slice(0, 1000)
  };
}

function emptyAnalysis(trackId = '', status = trackId ? 'loading' : 'idle') {
  return {
    trackId,
    status,
    bpm: 0,
    beatInterval: 0,
    beatConfidence: 0,
    beats: [],
    downbeats: [],
    phraseBoundaries: [],
    phrases: [],
    key: '',
    keyConfidence: 0,
    chroma: [],
    audibleStartTime: 0,
    pickupTime: 0,
    pickupConfidence: 0,
    mixInTime: 0,
    mixInConfidence: 0,
    introEndTime: 0,
    outroStartTime: 0,
    contentEndTime: 0,
    mixOutTime: 0,
    loudnessLufs: -70,
    peakDbfs: -70,
    dynamicRangeDb: 0,
    energyCurve: [],
    vocalProbability: 0,
    instrumentalProbability: 0
  };
}

function retryPause(signal, delay = 600) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Smart Crossfade analysis was cancelled', 'AbortError'));
      return;
    }
    const cleanup = () => signal?.removeEventListener('abort', onAbort);
    const onAbort = () => {
      window.clearTimeout(timer);
      cleanup();
      reject(new DOMException('Smart Crossfade analysis was cancelled', 'AbortError'));
    };
    const timer = window.setTimeout(() => {
      cleanup();
      resolve();
    }, delay);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

export function installSmartCrossfadeActions(ctx) {
  ctx.crossfadeAnalysisByTrack = new Map();
  ctx.crossfadeAnalysis = ref(emptyAnalysis());
  ctx.nextCrossfadeAnalysis = ref(emptyAnalysis());
  ctx.crossfadeAnalysisRequest = 0;
  ctx.nextCrossfadeAnalysisRequest = 0;
  ctx.crossfadeAnalysisAbort = null;
  ctx.nextCrossfadeAnalysisAbort = null;
  ctx.smartCrossfadeAnalyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: ctx.audioAnalyzer.decodeAudio
  });
  ctx.bpmMetadata = createBpmMetadataClient({
    report: (event, details) => ctx.smartCrossfadeAnalyzer.report(`bpm-${event}`, details)
  });

  ctx.resetCrossfadeAnalysis = function resetCrossfadeAnalysis(trackId = '') {
    ctx.crossfadeAnalysisRequest += 1;
    ctx.crossfadeAnalysisAbort?.abort();
    ctx.crossfadeAnalysisAbort = null;
    ctx.crossfadeAnalysis.value = emptyAnalysis(trackId);
  };

  ctx.resetNextCrossfadeAnalysis = function resetNextCrossfadeAnalysis(trackId = '') {
    ctx.nextCrossfadeAnalysisRequest += 1;
    ctx.nextCrossfadeAnalysisAbort?.abort();
    ctx.nextCrossfadeAnalysisAbort = null;
    ctx.nextCrossfadeAnalysis.value = emptyAnalysis(trackId);
  };

  // Each target owns one AbortController. Request counters are the final stale-
  // result guard because worker/native cancellation cannot interrupt queued DSP.
  async function analyzeInto(target, requestKey, abortKey, track, streamUrl, fallbackDuration) {
    if (!track?.id || !streamUrl) {
      target.value = emptyAnalysis();
      return;
    }

    const requestId = ctx[requestKey] + 1;
    ctx[requestKey] = requestId;
    ctx[abortKey]?.abort();
    const controller = new AbortController();
    ctx[abortKey] = controller;
    target.value = emptyAnalysis(track.id);
    const targetName = requestKey === 'crossfadeAnalysisRequest' ? 'current' : 'next';
    ctx.smartCrossfadeAnalyzer.report('track-request', { trackId: track.id, target: targetName });
    const bpmMetadataPromise = ctx.bpmMetadata.lookup(track);

    try {
      let analysis;
      for (let attempt = 0; attempt < 2; attempt += 1) {
        try {
          analysis = await ctx.smartCrossfadeAnalyzer.analyze(track.id, streamUrl, {
            duration: fallbackDuration,
            signal: controller.signal
          });
          break;
        } catch (error) {
          if (error?.name === 'AbortError' || attempt === 1) throw error;
          ctx.smartCrossfadeAnalyzer.report('track-retry', {
            trackId: track.id,
            target: targetName,
            attempt: attempt + 2,
            ...errorDetails(error)
          });
          await retryPause(controller.signal);
        }
      }
      if (controller.signal.aborted || requestId !== ctx[requestKey]) return;
      const bpmMetadata = await bpmMetadataPromise;
      if (controller.signal.aborted || requestId !== ctx[requestKey]) return;
      target.value = {
        ...emptyAnalysis(track.id, 'ready'),
        ...mergeBpmMetadata(analysis, bpmMetadata),
        trackId: track.id,
        status: 'ready'
      };
      ctx.crossfadeAnalysisByTrack.set(track.id, target.value);
      if (ctx.crossfadeAnalysisByTrack.size > 120) {
        ctx.crossfadeAnalysisByTrack.delete(ctx.crossfadeAnalysisByTrack.keys().next().value);
      }
      ctx.smartCrossfadeAnalyzer.report('track-ready', {
        trackId: track.id,
        target: targetName,
        mixInTime: Number(target.value.mixInTime) || 0,
        mixOutTime: Number(target.value.mixOutTime) || 0,
        contentEndTime: Number(target.value.contentEndTime) || 0
      });
    } catch (error) {
      if (error?.name === 'AbortError' || requestId !== ctx[requestKey]) {
        ctx.smartCrossfadeAnalyzer.report('track-cancelled', {
          trackId: track.id,
          target: targetName,
          staleRequest: requestId !== ctx[requestKey]
        });
        return;
      }
      const bpmMetadata = await bpmMetadataPromise;
      if (controller.signal.aborted || requestId !== ctx[requestKey]) return;
      if (bpmMetadata) {
        target.value = {
          ...emptyAnalysis(track.id, 'ready'),
          ...mergeBpmMetadata({}, bpmMetadata),
          trackId: track.id,
          status: 'ready'
        };
        ctx.crossfadeAnalysisByTrack.set(track.id, target.value);
        ctx.smartCrossfadeAnalyzer.report('track-ready-from-bpm', {
          trackId: track.id,
          target: targetName,
          bpm: Number(target.value.bpm) || 0,
          key: target.value.key || ''
        });
        return;
      }
      target.value = emptyAnalysis(track.id, 'unavailable');
      ctx.smartCrossfadeAnalyzer.report('track-unavailable', {
        trackId: track.id,
        target: targetName,
        ...errorDetails(error)
      });
    }
  }

  ctx.analyzeCurrentCrossfadeTrack = function analyzeCurrentCrossfadeTrack(track, streamUrl, duration = 0) {
    return analyzeInto(
      ctx.crossfadeAnalysis,
      'crossfadeAnalysisRequest',
      'crossfadeAnalysisAbort',
      track,
      streamUrl,
      duration
    );
  };

  ctx.analyzeNextCrossfadeTrack = function analyzeNextCrossfadeTrack(track, streamUrl, duration = 0) {
    return analyzeInto(
      ctx.nextCrossfadeAnalysis,
      'nextCrossfadeAnalysisRequest',
      'nextCrossfadeAnalysisAbort',
      track,
      streamUrl,
      duration
    );
  };

  ctx.promoteCrossfadeAnalysis = function promoteCrossfadeAnalysis(trackId) {
    ctx.crossfadeAnalysisRequest += 1;
    ctx.crossfadeAnalysisAbort?.abort();
    const prepared = ctx.nextCrossfadeAnalysis.value;
    ctx.crossfadeAnalysis.value = prepared.trackId === trackId
      ? { ...prepared, trackId }
      : emptyAnalysis(trackId);
    ctx.resetNextCrossfadeAnalysis();
  };
}
