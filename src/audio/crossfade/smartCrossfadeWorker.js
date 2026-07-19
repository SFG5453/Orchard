// Dedicated offline preparation and JavaScript-fallback worker. Messages own
// transferred planar Float32 channel buffers; output is newly allocated mono
// PCM. Heap allocation is expected here because this is not a real-time thread.
import Meyda from 'meyda';
import MusicTempo from 'music-tempo';

// Krumhansl-Schmuckler pitch-class profiles; confidence is the top-two score gap.
const KEY_NAMES = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];
const MAJOR_PROFILE = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR_PROFILE = [6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

function correlation(chroma, profile, rotation) {
  let score = 0;
  for (let index = 0; index < 12; index += 1) {
    score += chroma[index] * profile[(index - rotation + 12) % 12];
  }
  return score;
}

function estimateKey(chroma) {
  if (!chroma.some((value) => value > 0)) return { key: '', keyConfidence: 0 };
  const candidates = [];
  for (let root = 0; root < 12; root += 1) {
    candidates.push({ key: `${KEY_NAMES[root]} major`, score: correlation(chroma, MAJOR_PROFILE, root) });
    candidates.push({ key: `${KEY_NAMES[root]} minor`, score: correlation(chroma, MINOR_PROFILE, root) });
  }
  candidates.sort((left, right) => right.score - left.score);
  const best = candidates[0];
  const next = candidates[1];
  return {
    key: best?.key || '',
    keyConfidence: best?.score > 0 ? Math.max(0, Math.min(1, (best.score - next.score) / best.score)) : 0
  };
}

function monoFrame(samples, start, size) {
  const frame = new Float32Array(size);
  frame.set(samples.subarray(start, Math.min(samples.length, start + size)));
  return frame;
}

/**
 * Equal-weights planar channels and box-averages samples into contiguous mono.
 * Channel planes are expected to have the same AudioBuffer-derived length.
 * The output rate is min(sourceRate, max(4000, targetRate)), so this path never
 * upsamples. Input ArrayBuffers arrived by transfer and are worker-owned.
 * @returns {{pcm: Float32Array, sampleRate: number}} Newly owned mono storage.
 */
function downmixAndResample(channelBuffers, sourceRate, targetRate) {
  const channels = channelBuffers.map((buffer) => new Float32Array(buffer));
  const inputLength = channels[0]?.length || 0;
  if (!channels.length || !inputLength) {
    return { pcm: new Float32Array(), sampleRate: Number(targetRate) || sourceRate };
  }
  const rate = Math.min(sourceRate, Math.max(4000, Number(targetRate) || sourceRate));
  const ratio = sourceRate / rate;
  const output = new Float32Array(Math.max(1, Math.floor(inputLength / ratio)));

  for (let outputIndex = 0; outputIndex < output.length; outputIndex += 1) {
    const start = Math.floor(outputIndex * ratio);
    const end = Math.max(start + 1, Math.min(inputLength, Math.floor((outputIndex + 1) * ratio)));
    let sum = 0;
    for (const channel of channels) {
      for (let inputIndex = start; inputIndex < end; inputIndex += 1) sum += channel[inputIndex] || 0;
    }
    output[outputIndex] = sum / ((end - start) * channels.length);
  }
  return { pcm: output, sampleRate: rate };
}

function analyzeKey(samples, sampleRate) {
  // Sparse 4096-sample frames weight chroma by RMS and skip near-silent frames.
  const frameSize = 4096;
  const hopSeconds = 0.5;
  const hopSize = Math.max(frameSize, Math.round(sampleRate * hopSeconds));
  const chroma = Array.from({ length: 12 }, () => 0);
  let chromaWeight = 0;

  Meyda.bufferSize = frameSize;
  Meyda.sampleRate = sampleRate;

  for (let start = 0; start < samples.length; start += hopSize) {
    const frame = monoFrame(samples, start, frameSize);
    let features;
    try {
      features = Meyda.extract(['rms', 'chroma'], frame);
    } catch {
      continue;
    }
    const rms = Number(features?.rms) || 0;
    if (rms > 0.003 && Array.isArray(features?.chroma)) {
      features.chroma.forEach((value, index) => {
        chroma[index] += (Number(value) || 0) * rms;
      });
      chromaWeight += rms;
    }
  }

  if (chromaWeight) {
    for (let index = 0; index < chroma.length; index += 1) chroma[index] /= chromaWeight;
  }

  return {
    ...estimateKey(chroma),
    chroma
  };
}

function analyzePickup(samples, sampleRate, duration) {
  // Sustained 200 ms RMS windows adapt onset gates to p20 noise and p85 reference.
  const windowSeconds = 0.2;
  const windowSize = Math.max(1, Math.floor(sampleRate * windowSeconds));
  const limit = Math.min(samples.length, Math.floor(sampleRate * Math.min(duration, 45)));
  const levels = [];

  for (let start = 0; start + windowSize <= limit; start += windowSize) {
    let sum = 0;
    for (let index = start; index < start + windowSize; index += 1) {
      const value = samples[index] || 0;
      sum += value * value;
    }
    levels.push(Math.sqrt(sum / windowSize));
  }

  if (!levels.length) {
    return { audibleStartTime: 0, pickupTime: 0, pickupConfidence: 0 };
  }
  const sorted = [...levels].sort((left, right) => left - right);
  const noiseFloor = sorted[Math.floor((sorted.length - 1) * 0.2)] || 0;
  const reference = sorted[Math.floor((sorted.length - 1) * 0.85)] || 0;
  const threshold = Math.max(0.0035, noiseFloor * 2.8, reference * 0.16);
  const strongThreshold = Math.max(threshold * 1.7, reference * 0.35);
  const sustainWindows = 8;

  for (let index = 0; index <= levels.length - sustainWindows; index += 1) {
    const window = levels.slice(index, index + sustainWindows);
    const activeWindows = window.filter((value) => value >= threshold).length;
    const strongWindows = window.filter((value) => value >= strongThreshold).length;
    const sustained = window.reduce((sum, value) => sum + value, 0) / sustainWindows;
    if (activeWindows < 4 || strongWindows < 1 || sustained < threshold * 1.08) continue;
    const firstActiveOffset = window.findIndex((value) => value >= threshold);
    const audibleStartTime = Math.max(0, (index + Math.max(0, firstActiveOffset)) * windowSeconds - 0.12);
    return {
      audibleStartTime,
      pickupTime: audibleStartTime,
      pickupConfidence: reference > noiseFloor
        ? Math.min(1, (sustained - noiseFloor) / (reference - noiseFloor))
        : 0
    };
  }

  return { audibleStartTime: 0, pickupTime: 0, pickupConfidence: 0 };
}

function analyzeContentEnd(samples, sampleRate, duration) {
  // A 250 ms tail scan finds content end; 50 ms windows find late internal cliffs.
  const windowSeconds = 0.25;
  const windowSize = Math.max(1, Math.floor(sampleRate * windowSeconds));
  const levels = [];
  for (let start = 0; start < samples.length; start += windowSize) {
    const end = Math.min(samples.length, start + windowSize);
    let sum = 0;
    for (let index = start; index < end; index += 1) sum += (samples[index] || 0) ** 2;
    levels.push(Math.sqrt(sum / Math.max(1, end - start)));
  }
  if (!levels.length) return { contentEndTime: duration };

  const sorted = [...levels].sort((left, right) => left - right);
  const percentile = (ratio) => sorted[Math.floor((sorted.length - 1) * ratio)] || 0;
  const noiseFloor = percentile(0.05);
  const reference = percentile(0.85);
  const threshold = Math.max(
    0.0025,
    Math.min(noiseFloor * 2.6, reference * 0.28),
    reference * 0.1
  );
  const silenceThreshold = Math.max(0.0015, Math.min(threshold * 0.25, reference * 0.04));
  let quietStart = levels.length;
  while (quietStart > 0 && levels[quietStart - 1] < silenceThreshold) quietStart -= 1;
  const contentEndTime = quietStart * windowSeconds;
  const detectedContentEnd = duration - contentEndTime >= 0.35
    ? Math.max(0, contentEndTime)
    : duration;
  const cliffWindowSeconds = 0.05;
  const cliffWindowSize = Math.max(1, Math.floor(sampleRate * cliffWindowSeconds));
  const cliffLevels = [];
  for (let start = 0; start < samples.length; start += cliffWindowSize) {
    const end = Math.min(samples.length, start + cliffWindowSize);
    let sum = 0;
    for (let index = start; index < end; index += 1) sum += (samples[index] || 0) ** 2;
    cliffLevels.push(Math.sqrt(sum / Math.max(1, end - start)));
  }
  let bestIndex = 0;
  let bestDuration = 0;
  const firstWindow = Math.floor(duration * 0.55 / cliffWindowSeconds);
  const contextWindows = Math.floor(2 / cliffWindowSeconds);
  for (let index = firstWindow; index < cliffLevels.length;) {
    if (cliffLevels[index] >= silenceThreshold) {
      index += 1;
      continue;
    }
    let end = index + 1;
    while (end < cliffLevels.length && cliffLevels[end] < silenceThreshold) end += 1;
    const silenceDuration = (end - index) * cliffWindowSeconds;
    const beforePeak = Math.max(...cliffLevels.slice(Math.max(0, index - contextWindows), index), 0);
    const afterPeak = Math.max(...cliffLevels.slice(end, end + contextWindows), 0);
    if (silenceDuration >= 0.3 && end * cliffWindowSeconds <= duration - 4 &&
        beforePeak >= silenceThreshold * 2 && afterPeak >= silenceThreshold * 2 &&
        silenceDuration > bestDuration) {
      bestIndex = index;
      bestDuration = silenceDuration;
    }
    index = end;
  }
  const cliffThreshold = Math.max(silenceThreshold * 2, reference * 0.65);
  const maximumBacktrack = Math.floor(4 / cliffWindowSeconds);
  let cliffStart = bestIndex;
  while (cliffStart > firstWindow && bestIndex - cliffStart < maximumBacktrack &&
         cliffLevels[cliffStart - 1] < cliffThreshold) {
    cliffStart -= 1;
  }
  return {
    contentEndTime: detectedContentEnd,
    mixOutTime: bestIndex ? cliffStart * cliffWindowSeconds : detectedContentEnd
  };
}

function analyzeTempo(samples, sampleRate, duration) {
  // MusicTempo inspects at most 150 seconds, then extrapolates its beat interval.
  const maximumSeconds = Math.min(duration, 150);
  const tempoSamples = samples.subarray(0, Math.floor(maximumSeconds * sampleRate));
  const hopSize = Math.max(1, Math.round(sampleRate * 0.01));

  try {
    const tempo = new MusicTempo(tempoSamples, {
      expiryTime: 20,
      hopSize,
      timeStep: hopSize / sampleRate,
      maxBeatInterval: 1.25,
      minBeatInterval: 0.285
    });
    const bpm = Number(tempo.tempo);
    const interval = bpm > 0 ? 60 / bpm : 0;
    const firstBeat = Number(tempo.beats?.[0]) || 0;
    const beats = [];
    if (interval > 0) {
      for (let time = firstBeat; time <= duration; time += interval) beats.push(time);
    }
    return {
      bpm,
      beatInterval: interval,
      firstBeat,
      beats,
      beatConfidence: Math.max(0, Math.min(1, (tempo.beats?.length || 0) / 48))
    };
  } catch {
    return { bpm: 0, beatInterval: 0, firstBeat: 0, beats: [], beatConfidence: 0 };
  }
}

function buildDjStructure(analysis, duration) {
  // Fallback structure assumes 4/4 downbeats and eight-bar (32-beat) phrases.
  const beatInterval = Number(analysis.beatInterval) || 0;
  const contentEndTime = Number(analysis.contentEndTime) || duration;
  const audibleStartTime = Math.max(0, Number(analysis.audibleStartTime) || 0);
  if (!beatInterval) {
    return {
      downbeats: [],
      phraseBoundaries: [audibleStartTime, contentEndTime],
      introEndTime: audibleStartTime,
      outroStartTime: contentEndTime,
      mixInTime: audibleStartTime,
      mixInConfidence: 0
    };
  }

  const phraseStart = Math.max(audibleStartTime, Number(analysis.firstBeat) || 0);
  const downbeats = [];
  for (let time = phraseStart; time <= contentEndTime; time += beatInterval * 4) downbeats.push(time);
  const phraseBoundaries = [];
  for (let time = phraseStart; time <= contentEndTime; time += beatInterval * 32) {
    phraseBoundaries.push(time);
  }
  const introEndTime = Math.min(contentEndTime, phraseStart + beatInterval * 32);
  const latestCue = Math.max(audibleStartTime, Math.min(36, contentEndTime * 0.28));
  const cueTarget = Math.min(latestCue, Math.max(introEndTime, phraseStart + beatInterval * 32));
  const mixInTime = [...downbeats].reverse().find((time) => time <= cueTarget) ?? audibleStartTime;
  const outroStartTime = Math.max(introEndTime, contentEndTime - beatInterval * 32);
  return {
    downbeats,
    phraseBoundaries: [...new Set([...phraseBoundaries, introEndTime, outroStartTime, contentEndTime])]
      .sort((left, right) => left - right),
    introEndTime,
    outroStartTime,
    mixInTime,
    mixInConfidence: Math.min(1, 0.2 + (Number(analysis.beatConfidence) || 0) * 0.8)
  };
}

// `prepareOnly` returns native-ready mono PCM. Otherwise the same preparation
// feeds a numerically distinct JS fallback with the shared result/version shape.
// Transfer lists detach the posted mono buffer from this worker after delivery.
self.onmessage = (event) => {
  const { id, channels, sampleRate, duration, prepareOnly, targetSampleRate } = event.data;
  try {
    if (prepareOnly) {
      const prepared = downmixAndResample(channels, sampleRate, targetSampleRate);
      self.postMessage({
        id,
        prepared: {
          samples: prepared.pcm.buffer,
          sampleRate: prepared.sampleRate,
          duration
        }
      }, [prepared.pcm.buffer]);
      return;
    }
    const prepared = downmixAndResample(channels, sampleRate, targetSampleRate);
    const pcm = prepared.pcm;
    const analysisSampleRate = prepared.sampleRate;
    const result = {
      analysisVersion: 4,
      duration,
      ...analyzeKey(pcm, analysisSampleRate),
      ...analyzePickup(pcm, analysisSampleRate, duration),
      ...analyzeContentEnd(pcm, analysisSampleRate, duration),
      ...analyzeTempo(pcm, analysisSampleRate, duration)
    };
    Object.assign(result, buildDjStructure(result, duration));
    self.postMessage({ id, result });
  } catch (error) {
    self.postMessage({ id, error: error?.message || 'Smart Crossfade analysis failed' });
  }
};
