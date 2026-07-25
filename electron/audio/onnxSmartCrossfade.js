import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import {
  analyzeSeparatedStemActivity,
  extractAllInOneSpectrogram,
  HTDEMUCS_SAMPLE_RATE,
  postprocessAllInOne,
  separateHtdemucs
} from './allInOnePipeline.js';

export const SMART_CROSSFADE_MODEL_SCHEMA_VERSION = 1;
export const SMART_CROSSFADE_MODEL_MANIFEST = 'manifest.json';

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
      if (manifest?.pipeline !== 'all-in-one-htdemucs') continue;
      const demucsPath = insideDirectory(directory, manifest?.demucs?.file);
      const structurePath = insideDirectory(directory, manifest?.structure?.file);
      const configPath = insideDirectory(directory, manifest?.structure?.config);
      if (!await existingFile(demucsPath) ||
          !await existingFile(structurePath) ||
          !await existingFile(configPath)) continue;
      return {
        directory,
        manifest,
        demucsPath,
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
      sampleRate: HTDEMUCS_SAMPLE_RATE,
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
      const [demucs, structure, structureConfig] = await Promise.all([
        ort.InferenceSession.create(loaded.demucsPath, sessionOptions),
        ort.InferenceSession.create(loaded.structurePath, sessionOptions),
        readFile(loaded.configPath, 'utf8').then(JSON.parse)
      ]);
      return { loaded, ort, demucs, structure, structureConfig };
    })().catch((error) => {
      sessionsPromise = null;
      throw error;
    });
    return sessionsPromise;
  }

  async function run(input = {}) {
    const active = await sessions();
    const channels = tensorChannels(input.channels);
    const sampleRate = Number(input.sampleRate);
    const duration = Number(input.duration);
    if (!channels || !Number.isFinite(sampleRate) || sampleRate < 8_000 ||
        !Number.isFinite(duration) || duration <= 0 || duration > 60 * 30) {
      throw new Error('Invalid decoded audio for Smart Crossfade ONNX analysis.');
    }

    const report = (details) => {
      try {
        logger('onnx-progress', details);
      } catch {
        // Analysis progress is diagnostic-only.
      }
    };
    const separated = await separateHtdemucs({
      channels,
      inputName: cleanText(active.loaded.manifest.demucs?.inputName || 'mix'),
      onProgress: report,
      ort: active.ort,
      outputName: cleanText(active.loaded.manifest.demucs?.outputName || 'stems'),
      sampleRate,
      session: active.demucs
    });
    const stemActivity = analyzeSeparatedStemActivity(separated.stems, separated.sampleRate);
    const spectrogram = extractAllInOneSpectrogram({
      onProgress: report,
      sampleRate: separated.sampleRate,
      stems: separated.stems
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
    const analysis = postprocessAllInOne({
      config: active.structureConfig,
      duration,
      outputs: structureOutputs,
      stemActivity
    });
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
