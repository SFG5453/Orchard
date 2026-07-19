import { AudioContext as StandardizedAudioContext } from 'standardized-audio-context';
import { createCrossfadeMixer } from '../crossfade/crossfadeMixer.js';

function clamp01(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(1, number));
}

/**
 * Creates the renderer's live Web Audio graph, meter, and whole-file decoder.
 * This is separate from offline native musical analysis. Each media element
 * gets one cached MediaElementSource; `destroy()` closes the owning context.
 * @param {object} [options] Analyzer, history, and optional processor settings.
 * @returns {object} Audio graph controls whose resources end with `destroy()`.
 */
export function createAudioAnalyzer(options = {}) {
  const config = {
    fftSize: options.fftSize || 1024,
    smoothingTimeConstant: options.smoothingTimeConstant ?? 0.82,
    historySize: options.historySize || 96,
    outroRecentSize: options.outroRecentSize || 10,
    outroSustainFrames: options.outroSustainFrames || 3,
    outroCliffHoldFrames: options.outroCliffHoldFrames || 18,
    outroCliffRatio: options.outroCliffRatio || 0.42,
    outroPreviousRatio: options.outroPreviousRatio || 0.68
  };
  const nodes = new WeakMap();
  const contentEndCache = new Map();
  let context = null;

  function audioContext() {
    if (context) return context;

    try {
      context = new StandardizedAudioContext();
    } catch {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) return null;
      context = new AudioContextClass();
    }
    return context;
  }

  function connectElement(element) {
    if (!element) return null;
    const existing = nodes.get(element);
    if (existing) return existing;

    const ctx = audioContext();
    if (!ctx) return null;

    const source = ctx.createMediaElementSource(element);
    const analyser = ctx.createAnalyser();
    const normalizer = ctx.createDynamicsCompressor();
    const directGain = ctx.createGain();
    const normalizedGain = ctx.createGain();
    const gain = ctx.createGain();
    const lowPass = ctx.createBiquadFilter();
    const highPass = ctx.createBiquadFilter();
    lowPass.type = 'lowpass';
    lowPass.frequency.value = Math.min(20000, ctx.sampleRate * 0.45);
    lowPass.Q.value = 0.707;
    highPass.type = 'highpass';
    highPass.frequency.value = 20;
    highPass.Q.value = 0.707;
    analyser.fftSize = config.fftSize;
    analyser.smoothingTimeConstant = config.smoothingTimeConstant;
    normalizer.threshold.value = -24;
    normalizer.knee.value = 18;
    normalizer.ratio.value = 4;
    normalizer.attack.value = 0.02;
    normalizer.release.value = 0.3;
    directGain.gain.value = 1;
    normalizedGain.gain.value = 0;
    gain.gain.value = clamp01(element.volume);

    const processor = options.createProcessor?.({ context: ctx, source, element });
    if (processor?.output) processor.output.connect(analyser);
    else source.connect(analyser);
    analyser.connect(directGain);
    analyser.connect(normalizer);
    directGain.connect(gain);
    normalizer.connect(normalizedGain);
    normalizedGain.connect(gain);
    gain.connect(lowPass);
    lowPass.connect(highPass);
    highPass.connect(ctx.destination);
    element.volume = 1;

    const node = {
      analyser,
      directGain,
      gain,
      highPass,
      lowPass,
      normalizedGain,
      normalizer,
      data: new Uint8Array(analyser.frequencyBinCount),
      timeData: new Uint8Array(analyser.fftSize),
      previousData: null,
      previousWaveRms: 0,
      lowHistory: [],
      midHistory: [],
      highHistory: [],
      rmsHistory: [],
      fluxHistory: [],
      outroReleaseFrames: 0,
      outroCliffFrames: 0,
      totalHistory: []
    };
    nodes.set(element, node);
    return node;
  }

  function setNormalization(element, enabled) {
    const node = connectElement(element);
    if (!node) return;

    const now = currentTime();
    const directTarget = enabled ? 0 : 1;
    const normalizedTarget = enabled ? 1 : 0;
    node.directGain.gain.cancelScheduledValues(now);
    node.normalizedGain.gain.cancelScheduledValues(now);
    node.directGain.gain.setTargetAtTime(directTarget, now, 0.015);
    node.normalizedGain.gain.setTargetAtTime(normalizedTarget, now, 0.015);
  }

  async function resume() {
    const ctx = audioContext();
    if (ctx?.state === 'suspended') await ctx.resume();
  }

  function currentTime() {
    const ctx = audioContext();
    return ctx?.currentTime || 0;
  }

  function setVolume(element, value) {
    const node = connectElement(element);
    if (!node) {
      if (element) element.volume = clamp01(value);
      return;
    }

    const now = currentTime();
    node.gain.gain.cancelScheduledValues(now);
    node.gain.gain.setValueAtTime(clamp01(value), now);
  }

  async function decodeAudio(url, signal) {
    // Offline analysis intentionally buffers and duplicates the encoded file before decoding.
    const ctx = audioContext();
    if (!ctx) return null;

    const response = await fetch(url, { signal });
    if (!response.ok) throw new Error(`Audio analysis fetch failed with HTTP ${response.status}`);
    const data = await response.arrayBuffer();
    return ctx.decodeAudioData(data.slice(0));
  }

  function average(values) {
    if (!values.length) return 0;
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  }

  function recentAverage(values, size = config.outroRecentSize) {
    return average(values.slice(Math.max(0, values.length - size)));
  }

  function windowAverage(values, start, size) {
    return average(values.slice(start, start + size));
  }

  function percentile(values, ratio) {
    if (!values.length) return 0;
    const sorted = [...values].sort((a, b) => a - b);
    const index = Math.max(0, Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * ratio)));
    return sorted[index];
  }

  function pushHistory(node, values) {
    Object.entries(values).forEach(([key, value]) => {
      node[key].push(value);
      if (node[key].length > config.historySize) node[key].shift();
    });
  }

  async function analyzeContentEnd(url, options = {}) {
    const cacheKey = [
      url,
      options.duration || 0,
      options.analysisWindowSeconds || 45,
      options.windowSeconds || 0.5,
      options.historySeconds || 24
    ].join(':');
    const cached = contentEndCache.get(cacheKey);
    if (cached) return cached;

    const buffer = await decodeAudio(url, options.signal);
    if (!buffer) return null;

    const duration = Number(buffer.duration) || Number(options.duration) || 0;
    const channelCount = Math.max(1, buffer.numberOfChannels);
    const sampleRate = buffer.sampleRate;
    const windowSeconds = options.windowSeconds || 0.5;
    const windowSize = Math.max(1, Math.floor(sampleRate * windowSeconds));
    const historyWindows = Math.max(8, Math.floor((options.historySeconds || 24) / windowSeconds));
    const analysisStart = Math.max(0, duration - (options.analysisWindowSeconds || 45));
    const sustainWindows = Math.max(2, Math.ceil((options.sustainSeconds || 2) / windowSeconds));
    const tailWindows = Math.max(sustainWindows * 3, Math.ceil((options.tailSeconds || 5) / windowSeconds));
    const rmsValues = [];
    const times = [];

    for (let start = 0; start + windowSize <= buffer.length; start += windowSize) {
      let sum = 0;

      for (let channel = 0; channel < channelCount; channel += 1) {
        const data = buffer.getChannelData(channel);
        for (let index = start; index < start + windowSize; index += 1) {
          const value = data[index] || 0;
          sum += value * value;
        }
      }

      rmsValues.push(Math.sqrt(sum / (windowSize * channelCount)));
      times.push(start / sampleRate);
    }

    for (let index = 1; index < rmsValues.length; index += 1) {
      const time = times[index];
      if (time < analysisStart) continue;

      const history = rmsValues.slice(Math.max(0, index - historyWindows), index);
      const reference = percentile(history, 0.75);
      const previous = rmsValues[index - 1];
      const current = rmsValues[index];
      const previousAverage = windowAverage(
        rmsValues,
        Math.max(0, index - sustainWindows),
        Math.min(sustainWindows, index)
      );
      const nextAverage = windowAverage(rmsValues, index, sustainWindows);
      const nextPeak = Math.max(...rmsValues.slice(index, index + sustainWindows), 0);
      const tail = rmsValues.slice(index, index + tailWindows);
      const tailAverage = average(tail);
      const tailPeak = Math.max(...tail, 0);
      const tailLoudCount = tail.filter((value) => value > reference * 0.72).length;
      const tailQuietShare = tail.length ? 1 - (tailLoudCount / tail.length) : 0;
      const significantReference = reference > 0.025;
      const hardCliff = (
        significantReference &&
        previous > reference * config.outroPreviousRatio &&
        current < reference * config.outroCliffRatio
      );
      const sustainedCliff = (
        significantReference &&
        previousAverage > reference * 0.62 &&
        current < reference * 0.62 &&
        nextAverage < reference * 0.58 &&
        nextPeak < reference * 0.82
      );
      const tailCliff = (
        tail.length >= tailWindows &&
        significantReference &&
        previousAverage > reference * 0.7 &&
        current < reference * 0.5 &&
        (
          (tailAverage < reference * 0.48 && tailPeak < reference * 0.72) ||
          (tailAverage < reference * 0.58 && tailQuietShare >= 0.72)
        )
      );

      if (hardCliff || sustainedCliff || tailCliff) {
        const analysis = {
          contentEndTime: time,
          duration,
          confidence: tailCliff
            ? 'waveform-tail-cliff'
            : (hardCliff ? 'waveform-cliff' : 'waveform-sustained-cliff'),
          rmsReference: reference,
          rmsBefore: previous,
          rmsAfter: current,
          rmsBeforeAverage: previousAverage,
          rmsAfterAverage: tailCliff ? tailAverage : nextAverage
        };
        contentEndCache.set(cacheKey, analysis);
        if (contentEndCache.size > 80) {
          const firstKey = contentEndCache.keys().next().value;
          contentEndCache.delete(firstKey);
        }
        return analysis;
      }
    }

    const analysis = {
      contentEndTime: duration,
      duration,
      confidence: 'duration'
    };
    contentEndCache.set(cacheKey, analysis);
    if (contentEndCache.size > 80) {
      const firstKey = contentEndCache.keys().next().value;
      contentEndCache.delete(firstKey);
    }
    return analysis;
  }

  function measure(element) {
    const node = connectElement(element);
    if (!node || !context) {
      return {
        totalEnergy: 1,
        lowEnergy: 1,
        midEnergy: 1,
        highEnergy: 1,
        waveRms: 1,
        wavePeak: 1,
        rmsReference: 1,
        beatStrength: 1,
        beatReleased: false,
        outroCliff: false,
        outroEnded: false
      };
    }

    // Browser-smoothed FFT bytes are scaled by 255; histories count UI samples, not wall time.
    node.analyser.getByteFrequencyData(node.data);
    node.analyser.getByteTimeDomainData(node.timeData);

    const hzPerBin = context.sampleRate / node.analyser.fftSize;
    let lowTotal = 0;
    let lowCount = 0;
    let midTotal = 0;
    let midCount = 0;
    let highTotal = 0;
    let highCount = 0;
    let total = 0;
    let positiveFlux = 0;
    let waveTotal = 0;
    let wavePeak = 0;

    for (let index = 0; index < node.timeData.length; index += 1) {
      const centered = (node.timeData[index] - 128) / 128;
      waveTotal += centered * centered;
      wavePeak = Math.max(wavePeak, Math.abs(centered));
    }

    for (let index = 0; index < node.data.length; index += 1) {
      const energy = node.data[index] / 255;
      const hz = index * hzPerBin;
      total += energy;
      if (node.previousData) {
        positiveFlux += Math.max(0, energy - node.previousData[index] / 255);
      }

      if (hz >= 45 && hz <= 180) {
        lowTotal += energy;
        lowCount += 1;
      } else if (hz > 180 && hz <= 2200) {
        midTotal += energy;
        midCount += 1;
      } else if (hz >= 4000 && hz <= 10000) {
        highTotal += energy;
        highCount += 1;
      }
    }

    const totalEnergy = total / Math.max(1, node.data.length);
    const waveRms = Math.sqrt(waveTotal / Math.max(1, node.timeData.length));
    const lowEnergy = lowTotal / Math.max(1, lowCount);
    const midEnergy = midTotal / Math.max(1, midCount);
    const highEnergy = highTotal / Math.max(1, highCount);
    const spectralFlux = positiveFlux / Math.max(1, node.data.length);
    const averageLow = node.lowHistory.length
      ? average(node.lowHistory)
      : lowEnergy;
    const averageMid = node.midHistory.length ? average(node.midHistory) : midEnergy;
    const averageTotal = node.totalHistory.length
      ? average(node.totalHistory)
      : totalEnergy;
    const averageFlux = node.fluxHistory.length ? average(node.fluxHistory) : spectralFlux;
    const recentLow = node.lowHistory.length ? recentAverage(node.lowHistory) : lowEnergy;
    const recentMid = node.midHistory.length ? recentAverage(node.midHistory) : midEnergy;
    const recentTotal = node.totalHistory.length ? recentAverage(node.totalHistory) : totalEnergy;
    const recentFlux = node.fluxHistory.length ? recentAverage(node.fluxHistory) : spectralFlux;
    const rmsReference = node.rmsHistory.length ? percentile(node.rmsHistory, 0.75) : waveRms;
    const recentLowPeak = node.lowHistory.length ? Math.max(...node.lowHistory) : lowEnergy;
    const recentMidPeak = node.midHistory.length ? Math.max(...node.midHistory) : midEnergy;
    const recentTotalPeak = node.totalHistory.length ? Math.max(...node.totalHistory) : totalEnergy;
    const outroCliff = (
      rmsReference > 0.04 &&
      node.previousWaveRms > rmsReference * config.outroPreviousRatio &&
      waveRms < rmsReference * config.outroCliffRatio
    );
    node.outroCliffFrames = outroCliff
      ? config.outroCliffHoldFrames
      : Math.max(0, node.outroCliffFrames - 1);
    const outroCliffHeld = node.outroCliffFrames > 0;
    const beatStrength = lowEnergy / Math.max(averageLow, 0.001);
    const beatReleased = (
      (recentLow < 0.048 && recentTotal < 0.13) ||
      (
        recentLow < Math.max(0.052, averageLow * 0.64) &&
        recentFlux < Math.max(0.004, averageFlux * 0.78) &&
        recentTotal < averageTotal * 0.86
      )
    );
    const outroReleased = (
      outroCliffHeld ||
      recentTotal < 0.082 ||
      (
        recentLow < Math.max(0.042, averageLow * 0.6) &&
        recentLow < Math.max(0.042, recentLowPeak * 0.48) &&
        recentMid < Math.max(0.055, averageMid * 0.68) &&
        recentMid < Math.max(0.055, recentMidPeak * 0.58) &&
        recentTotal < Math.max(0.095, averageTotal * 0.76) &&
        recentTotal < Math.max(0.095, recentTotalPeak * 0.64) &&
        recentFlux < Math.max(0.004, averageFlux * 0.72)
      )
    );
    node.outroReleaseFrames = outroReleased ? node.outroReleaseFrames + 1 : 0;
    const outroEnded = node.outroReleaseFrames >= config.outroSustainFrames;

    pushHistory(node, {
      lowHistory: lowEnergy,
      midHistory: midEnergy,
      highHistory: highEnergy,
      rmsHistory: waveRms,
      fluxHistory: spectralFlux,
      totalHistory: totalEnergy
    });
    node.previousData = new Uint8Array(node.data);
    node.previousWaveRms = waveRms;

    return {
      totalEnergy: clamp01(totalEnergy),
      waveRms: clamp01(waveRms),
      wavePeak: clamp01(wavePeak),
      rmsReference: clamp01(rmsReference),
      lowEnergy: clamp01(lowEnergy),
      midEnergy: clamp01(midEnergy),
      highEnergy: clamp01(highEnergy),
      spectralFlux: clamp01(spectralFlux),
      beatStrength,
      beatReleased,
      outroCliff: outroCliffHeld,
      outroEnded
    };
  }

  function spectrum(element, size = 32) {
    const node = connectElement(element);
    if (!node || !context) return Array.from({ length: size }, () => 0);
    node.analyser.getByteFrequencyData(node.data);
    const limit = Math.max(1, Math.floor(node.data.length * 0.72));
    return Array.from({ length: size }, (_, index) => {
      const start = Math.floor((index / size) ** 1.8 * limit);
      const end = Math.max(start + 1, Math.floor(((index + 1) / size) ** 1.8 * limit));
      let peak = 0;
      for (let cursor = start; cursor < end; cursor += 1) peak = Math.max(peak, node.data[cursor] || 0);
      return peak / 255;
    });
  }

  function samples(element) {
    const node = connectElement(element);
    if (!node || !context) return null;
    const values = new Float32Array(node.analyser.fftSize);
    node.analyser.getFloatTimeDomainData(values);
    return { samples: values, sampleRate: context.sampleRate };
  }

  function destroy() {
    if (!context) return;
    context.close().catch(() => {});
    context = null;
  }

  const mixer = createCrossfadeMixer({ connectElement, currentTime });

  return {
    connectElement,
    analyzeContentEnd,
    currentTime,
    decodeAudio,
    destroy,
    measure,
    resume,
    samples,
    resetMixElement: mixer.resetElement,
    scheduleCrossfade: mixer.scheduleCrossfade,
    setNormalization,
    setVolume,
    spectrum
  };
}
