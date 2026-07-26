import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ALL_IN_ONE_SAMPLE_RATE,
  extractAllInOneMixSpectrogram,
  postprocessAllInOne
} from './allInOnePipeline.js';
import {
  SMART_CROSSFADE_HEAD_SECONDS,
  SMART_CROSSFADE_TAIL_SECONDS
} from '../../shared/aiAudioAnalysis.js';

export const SMART_CROSSFADE_MODEL_SCHEMA_VERSION = 1;
export const SMART_CROSSFADE_MODEL_MANIFEST = 'manifest.json';
export {
  SMART_CROSSFADE_HEAD_SECONDS,
  SMART_CROSSFADE_TAIL_SECONDS
};

function cleanText(value, maximum = 100) {
  return String(value || '').trim().slice(0, maximum);
}

function insideDirectory(directory, relativePath) {
  const resolved = path.resolve(directory, relativePath);
  const relative = path.relative(path.resolve(directory), resolved);
  if (!relative || relative === '.') return resolved;
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error('Smart Crossfade model paths must stay inside their model pack.');
  }
  return resolved;
}

async function existingFile(filePath) {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function loadManifest(searchPaths) {
  for (const directory of searchPaths) {
    if (!directory) continue;
    const manifestPath = path.join(directory, SMART_CROSSFADE_MODEL_MANIFEST);
    try {
      const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
      if (manifest?.schemaVersion !== SMART_CROSSFADE_MODEL_SCHEMA_VERSION) continue;
      if (!['all-in-one-mix', 'all-in-one-htdemucs'].includes(manifest?.pipeline)) continue;
      const structurePath = insideDirectory(directory, manifest?.structure?.file);
      const configPath = insideDirectory(directory, manifest?.structure?.config);
      if (!await existingFile(structurePath) ||
          !await existingFile(configPath)) continue;
      return {
        directory,
        manifest,
        structurePath,
        configPath
      };
    } catch {
      // Invalid or incomplete packs are skipped so deterministic analysis keeps working.
    }
  }
  return null;
}

function manifestSignature(manifest) {
  const id = cleanText(manifest?.id || 'smart-crossfade-models');
  const version = cleanText(manifest?.version || '1');
  return `${id}@${version}`;
}

function tensorChannels(values) {
  if (!Array.isArray(values) || !values.length || values.length > 2) return null;
  const channels = values.map((value) => {
    if (value instanceof Float32Array) return value;
    if (value instanceof ArrayBuffer) return new Float32Array(value);
    if (ArrayBuffer.isView(value) &&
        value.byteLength % Float32Array.BYTES_PER_ELEMENT === 0) {
      return new Float32Array(
        value.buffer,
        value.byteOffset,
        value.byteLength / Float32Array.BYTES_PER_ELEMENT
      );
    }
    return null;
  });
  if (channels.some((channel) => !channel?.length) ||
      channels.some((channel) => channel.length !== channels[0].length)) return null;
  return channels;
}

function clampedConfidence(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, Math.min(1, number)) : 0;
}

function shiftedPhrases(phrases, offset, duration) {
  return (Array.isArray(phrases) ? phrases : []).map((phrase) => ({
    ...phrase,
    start: Math.max(0, Math.min(duration, (Number(phrase?.start) || 0) + offset)),
    end: Math.max(0, Math.min(duration, (Number(phrase?.end) || 0) + offset))
  })).filter((phrase) => phrase.end > phrase.start);
}

function shiftedCandidates(candidates, offset, duration) {
  return (Array.isArray(candidates) ? candidates : []).map((candidate) => ({
    ...candidate,
    time: Math.max(0, Math.min(duration, (Number(candidate?.time) || 0) + offset))
  }));
}

function uniqueTimes(values, duration) {
  return [...new Set(values
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0 && value <= duration)
    .map((value) => Math.round(value * 10_000) / 10_000))]
    .sort((left, right) => left - right);
}

export function combineEdgeAnalyses({
  duration,
  head,
  headDuration,
  tail,
  tailDuration
}) {
  const fullDuration = Math.max(0, Number(duration) || 0);
  const safeHeadDuration = Math.max(0, Number(headDuration) || 0);
  const safeTailDuration = Math.max(0, Number(tailDuration) || 0);
  const tailOffset = Math.max(0, fullDuration - safeTailDuration);
  const headPhrases = shiftedPhrases(head?.phrases, 0, fullDuration);
  const tailPhrases = shiftedPhrases(tail?.phrases, tailOffset, fullDuration);
  const phrases = [...headPhrases, ...tailPhrases]
    .sort((left, right) => left.start - right.start || left.end - right.end);
  const totalAnalyzed = Math.max(1, safeHeadDuration + safeTailDuration);
  const structureConfidence = (
    clampedConfidence(head?.aiStructureConfidence) * safeHeadDuration +
    clampedConfidence(tail?.aiStructureConfidence) * safeTailDuration
  ) / totalAnalyzed;
  const headMixIn = Number(head?.mixInTime) || 0;
  const tailMixOut = Number(tail?.mixOutTime);
  const tailOutro = Number(tail?.outroStartTime) || 0;

  return {
    aiAnalysisStatus: 'ready',
    aiPipeline: 'all-in-one-mix-edges',
    aiAnalysisScope: 'head-tail',
    aiStructureConfidence: structureConfidence,
    aiBeatActivationConfidence: Math.max(
      clampedConfidence(head?.aiBeatActivationConfidence),
      clampedConfidence(tail?.aiBeatActivationConfidence)
    ),
    aiDownbeatActivationConfidence: Math.max(
      clampedConfidence(head?.aiDownbeatActivationConfidence),
      clampedConfidence(tail?.aiDownbeatActivationConfidence)
    ),
    phrases,
    phraseBoundaries: uniqueTimes([
      ...headPhrases.map((phrase) => phrase.start),
      ...tailPhrases.map((phrase) => phrase.start),
      headPhrases.at(-1)?.end,
      tailPhrases.at(-1)?.end
    ], fullDuration),
    introEndTime: Number(head?.introEndTime) || 0,
    mixInTime: headMixIn,
    mixInConfidence: clampedConfidence(head?.mixInConfidence),
    mixInCandidates: shiftedCandidates(head?.mixInCandidates, 0, fullDuration),
    outroStartTime: tailOutro > 0 ? tailOffset + tailOutro : 0,
    mixOutTime: Number.isFinite(tailMixOut)
      ? Math.min(fullDuration, tailOffset + tailMixOut)
      : fullDuration,
    mixOutCandidates: shiftedCandidates(tail?.mixOutCandidates, tailOffset, fullDuration)
  };
}

export function createOnnxSmartCrossfadeAnalyzer({
  loadRuntime = () => import('onnxruntime-node'),
  logger = () => {},
  modelSearchPaths = []
} = {}) {
  let packPromise = null;
  let sessionsPromise = null;
  let queueTail = Promise.resolve();

  function pack() {
    if (!packPromise) packPromise = loadManifest(modelSearchPaths);
    return packPromise;
  }

  async function status() {
    const loaded = await pack();
    if (!loaded) {
      return {
        available: false,
        pipeline: '',
        modelSignature: '',
        sampleRate: 0,
        channels: 0
      };
    }
    return {
      available: true,
      pipeline: loaded.manifest.pipeline,
      modelSignature: manifestSignature(loaded.manifest),
      sampleRate: ALL_IN_ONE_SAMPLE_RATE,
      channels: 2
    };
  }

  async function sessions() {
    if (sessionsPromise) return sessionsPromise;
    sessionsPromise = (async () => {
      const loaded = await pack();
      if (!loaded) throw new Error('No Smart Crossfade ONNX model pack is installed.');
      const ort = await loadRuntime();
      const sessionOptions = {
        executionProviders: ['cpu'],
        executionMode: 'sequential',
        graphOptimizationLevel: 'all',
        intraOpNumThreads: 2,
        interOpNumThreads: 1
      };
      const [structure, structureConfig] = await Promise.all([
        ort.InferenceSession.create(loaded.structurePath, sessionOptions),
        readFile(loaded.configPath, 'utf8').then(JSON.parse)
      ]);
      return { loaded, ort, structure, structureConfig };
    })().catch((error) => {
      sessionsPromise = null;
      throw error;
    });
    return sessionsPromise;
  }

  async function analyzeWindow(active, input, report) {
    const spectrogram = extractAllInOneMixSpectrogram({
      channels: input.channels,
      onProgress: report,
      sampleRate: input.sampleRate
    });
    const structureInput = cleanText(
      active.loaded.manifest.structure?.inputName ||
      active.structure.inputNames?.[0] ||
      'spec'
    );
    const structureOutputs = await active.structure.run({
      [structureInput]: new active.ort.Tensor(
        'float32',
        spectrogram.data,
        [1, 4, spectrogram.frames, spectrogram.bands]
      )
    });
    return postprocessAllInOne({
      config: active.structureConfig,
      duration: input.duration,
      outputs: structureOutputs,
      pipeline: 'all-in-one-mix'
    });
  }

  async function run(input = {}) {
    const active = await sessions();
    const channels = tensorChannels(input.channels);
    const sampleRate = Number(input.sampleRate);
    const duration = Number(input.duration);
    const suppliedHeadDuration = Number(input.headDuration) || 0;
    const suppliedTailDuration = Number(input.tailDuration) || 0;
    const hasSuppliedEdges = suppliedHeadDuration !== 0 || suppliedTailDuration !== 0;
    if (!channels || !Number.isFinite(sampleRate) || sampleRate < 8_000 ||
        !Number.isFinite(duration) || duration <= 0 || duration > 60 * 30 ||
        (hasSuppliedEdges && (
          suppliedHeadDuration <= 0 ||
          suppliedTailDuration <= 0 ||
          suppliedHeadDuration > SMART_CROSSFADE_HEAD_SECONDS ||
          suppliedTailDuration > SMART_CROSSFADE_TAIL_SECONDS ||
          suppliedHeadDuration + suppliedTailDuration >= duration
        ))) {
      throw new Error('Invalid decoded audio for Smart Crossfade ONNX analysis.');
    }

    const report = (details) => {
      try {
        logger('onnx-progress', details);
      } catch {
        // Analysis progress is diagnostic-only.
      }
    };
    const decodedDuration = channels[0].length / sampleRate;
    const edgeDuration = SMART_CROSSFADE_HEAD_SECONDS + SMART_CROSSFADE_TAIL_SECONDS;
    let analysis;
    if (hasSuppliedEdges) {
      const headSamples = Math.round(suppliedHeadDuration * sampleRate);
      const tailSamples = Math.round(suppliedTailDuration * sampleRate);
      if (headSamples + tailSamples !== channels[0].length) {
        throw new Error('Invalid edge windows for Smart Crossfade ONNX analysis.');
      }
      const head = await analyzeWindow(active, {
        channels: channels.map((channel) => channel.slice(0, headSamples)),
        duration: suppliedHeadDuration,
        sampleRate
      }, report);
      const tail = await analyzeWindow(active, {
        channels: channels.map((channel) => channel.slice(headSamples)),
        duration: suppliedTailDuration,
        sampleRate
      }, report);
      analysis = combineEdgeAnalyses({
        duration,
        head,
        headDuration: suppliedHeadDuration,
        tail,
        tailDuration: suppliedTailDuration
      });
    } else if (Math.min(duration, decodedDuration) <= edgeDuration) {
      const analyzedDuration = Math.min(duration, decodedDuration);
      analysis = await analyzeWindow(active, {
        channels,
        duration: analyzedDuration,
        sampleRate
      }, report);
    } else {
      const headSamples = Math.min(
        channels[0].length,
        Math.round(SMART_CROSSFADE_HEAD_SECONDS * sampleRate)
      );
      const tailSamples = Math.min(
        channels[0].length,
        Math.round(SMART_CROSSFADE_TAIL_SECONDS * sampleRate)
      );
      const headChannels = channels.map((channel) => channel.slice(0, headSamples));
      const tailChannels = channels.map((channel) => channel.slice(-tailSamples));
      const head = await analyzeWindow(active, {
        channels: headChannels,
        duration: headSamples / sampleRate,
        sampleRate
      }, report);
      const tail = await analyzeWindow(active, {
        channels: tailChannels,
        duration: tailSamples / sampleRate,
        sampleRate
      }, report);
      analysis = combineEdgeAnalyses({
        duration: Math.min(duration, decodedDuration),
        head,
        headDuration: headSamples / sampleRate,
        tail,
        tailDuration: tailSamples / sampleRate
      });
    }
    analysis.aiModelSignature = manifestSignature(active.loaded.manifest);
    return analysis;
  }

  function analyze(input) {
    const task = queueTail
      .catch(() => {})
      .then(() => run(input));
    queueTail = task;
    return task;
  }

  return { analyze, status };
}
