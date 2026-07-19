import Meyda from 'meyda';
import { kmeans } from 'ml-kmeans';
import { guess } from 'web-audio-beat-detector';
import { loadLearnedAudioProfiles, saveLearnedAudioProfiles } from './audioProfileStore.js';

const FEATURE_NAMES = [
  'rms',
  'spectralCentroid',
  'spectralFlatness',
  'spectralRolloff',
  'spectralSpread',
  'zcr',
  'loudness',
  'perceptualSharpness'
];

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, Number(value) || 0));
}

function average(values) {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
}

function normalizeVector(values = []) {
  return values.map((value) => clamp(value, 0, 1));
}

function extractFeatures(samples, sampleRate, spectrum) {
  Meyda.bufferSize = samples.length;
  Meyda.sampleRate = sampleRate;
  const result = Meyda.extract(FEATURE_NAMES, samples) || {};
  const nyquist = sampleRate / 2;
  const low = average(spectrum.slice(0, 3));
  const mid = average(spectrum.slice(3, 7));
  const high = average(spectrum.slice(7, 10));

  return normalizeVector([
    low,
    mid,
    high,
    clamp(result.rms * 4, 0, 1),
    clamp((result.spectralCentroid * sampleRate / samples.length) / nyquist, 0, 1),
    clamp(result.spectralFlatness, 0, 1),
    clamp(result.spectralRolloff / nyquist, 0, 1),
    clamp(result.zcr / samples.length, 0, 1),
    clamp((result.perceptualSharpness || result.loudness?.total || 0) / 4, 0, 1)
  ]);
}

function baseCorrections(features) {
  const [low, mid, high, rms, centroid, flatness, rolloff, zcr, sharpness] = features;
  const reference = average([low, mid, high]);
  const groupTargets = [reference + 0.035, reference, reference - 0.025];
  const groupLevels = [low, mid, high];
  const corrections = groupLevels.map((level, index) =>
    clamp((groupTargets[index] - level) * 8, -2.4, 2.4));
  const muddy = clamp((low - mid - 0.12) * 3.5, 0, 1.2);
  const brittle = clamp(((centroid + rolloff + sharpness) / 3 - 0.58) * 3, 0, 1.1);
  const noisy = clamp((flatness + zcr - 0.72) * 1.4, 0, 0.8);
  const quietLift = rms < 0.08 ? 0.25 : 0;

  return [
    corrections[0] + quietLift,
    corrections[0],
    ((corrections[0] + corrections[1]) / 2) - muddy,
    corrections[1] - muddy,
    corrections[1] - (muddy * 0.45),
    corrections[1],
    (corrections[1] + corrections[2]) / 2,
    corrections[2] - brittle,
    corrections[2] - brittle - noisy,
    corrections[2] - (brittle * 0.65) - noisy
  ].map((gain) => clamp(gain, -3, 3));
}

export function createAutomaticEq({ analyzer }) {
  let profiles = [];
  let clusters = null;
  let currentTrack = null;
  let featureMean = Array(9).fill(0);
  let gainMean = Array(10).fill(0);
  let sampleCount = 0;
  let analysisToken = 0;
  let ready = false;

  function rebuildClusters() {
    if (profiles.length < 6) {
      clusters = null;
      return;
    }
    const count = Math.min(4, Math.max(2, Math.round(Math.sqrt(profiles.length / 2))));
    clusters = kmeans(profiles.map((profile) => profile.features), count, {
      initialization: 'kmeans++',
      maxIterations: 60,
      seed: 545
    });
  }

  function learnedGains(features) {
    if (!clusters) return null;
    const cluster = clusters.nearest([features])[0];
    const peers = profiles.filter((_, index) => clusters.clusters[index] === cluster);
    if (!peers.length) return null;
    return Array.from({ length: 10 }, (_, index) =>
      average(peers.map((profile) => profile.gains[index])));
  }

  async function persistCurrent() {
    if (!currentTrack?.id || sampleCount < 6) return;
    const profile = {
      trackId: currentTrack.id,
      title: currentTrack.title || '',
      features: featureMean,
      gains: gainMean,
      tempo: currentTrack.tempo || null,
      sampleCount,
      updatedAt: Date.now()
    };
    profiles = [...profiles.filter((item) => item.trackId !== profile.trackId), profile].slice(-120);
    rebuildClusters();
    try {
      await saveLearnedAudioProfiles(profiles);
    } catch {
      // Automatic EQ stays functional if IndexedDB is unavailable.
    }
  }

  async function initialize() {
    if (ready) return;
    profiles = await loadLearnedAudioProfiles();
    rebuildClusters();
    ready = true;
  }

  async function analyzeTempo(track, streamUrl, token) {
    if (!streamUrl) return;
    const existing = profiles.find((profile) => profile.trackId === track.id);
    if (existing?.tempo) {
      if (token === analysisToken && currentTrack) currentTrack.tempo = existing.tempo;
      return;
    }
    try {
      const buffer = await analyzer.decodeAudio(streamUrl);
      if (!buffer || token !== analysisToken) return;
      const offset = buffer.duration > 70 ? Math.min(30, buffer.duration * 0.2) : 0;
      const duration = Math.min(45, Math.max(5, buffer.duration - offset));
      const result = await guess(buffer, offset, duration);
      if (token === analysisToken && currentTrack) currentTrack.tempo = result.bpm || result.tempo || null;
    } catch {
      // Tempo enriches the profile but is not required for EQ operation.
    }
  }

  async function beginTrack(track) {
    const token = ++analysisToken;
    await initialize();
    if (token !== analysisToken) return;
    await persistCurrent();
    if (token !== analysisToken) return;
    currentTrack = track?.id ? { id: track.id, title: track.title || '', tempo: null } : null;
    featureMean = Array(9).fill(0);
    gainMean = Array(10).fill(0);
    sampleCount = 0;
    if (currentTrack) void analyzeTempo(currentTrack, track.streamUrl || track.audioStreamUrl, token);
  }

  function update(element, previousGains = []) {
    const frame = analyzer.samples(element);
    if (!frame || !currentTrack) return null;
    let features;
    try {
      features = extractFeatures(frame.samples, frame.sampleRate, analyzer.spectrum(element, 10));
    } catch {
      return null;
    }
    const calculated = baseCorrections(features);
    const learned = learnedGains(features);
    const targets = calculated.map((gain, index) =>
      clamp(learned ? (gain * 0.78) + (learned[index] * 0.22) : gain, -3, 3));
    const gains = targets.map((target, index) =>
      ((previousGains[index] || 0) * 0.74) + (target * 0.26));

    sampleCount += 1;
    featureMean = featureMean.map((value, index) =>
      value + ((features[index] - value) / sampleCount));
    gainMean = gainMean.map((value, index) =>
      value + ((gains[index] - value) / sampleCount));
    if (sampleCount % 12 === 0) void persistCurrent();

    return {
      gains,
      profile: {
        learned: Boolean(learned),
        profileCount: profiles.length,
        sampleCount,
        tempo: currentTrack.tempo
      }
    };
  }

  return { beginTrack, initialize, persistCurrent, update };
}
