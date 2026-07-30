import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { setupAudioAnalysisService } from '../electron/audio/audioAnalysisService.js';
import { AUDIO_ANALYSIS_VERSION } from '../shared/audioAnalysis.js';

function fakeIpcMain() {
  const handlers = new Map();
  return {
    handle(channel, handler) {
      handlers.set(channel, handler);
    },
    invoke(channel, ...args) {
      return handlers.get(channel)?.({}, ...args);
    },
    removeHandler(channel) {
      handlers.delete(channel);
    }
  };
}

function tone(duration = 8, sampleRate = 11025) {
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    samples[index] = Math.sin(2 * Math.PI * 220 * index / sampleRate) * 0.2;
  }
  return { duration, sampleRate, samples };
}

test('only the tracks around a transition pay for the Essentia pass', async () => {
  // Essentia's rhythm extractor costs several times the whole native analysis,
  // so Best Mix -- which analyses up to fifty tracks at background priority --
  // must never trigger it.
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-'));
  const nativeModulePath = path.resolve('native/build/Release/orchard_audio_analysis.node');
  const calls = [];
  const ipc = fakeIpcMain();
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath,
    logger: () => {},
    refineConfidence: (samples, sampleRate) => {
      calls.push({ sampleCount: samples.length, sampleRate });
      return { beatConfidence: 0.87, essentiaConfidence: 4.12, essentiaBpm: 126.5, elapsedMs: 5 };
    }
  });

  try {
    const track = tone();
    const background = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'background-track',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 2
    });
    assert.equal(calls.length, 0, 'background analysis must not run Essentia');
    assert.equal(background.essentiaConfidence, undefined);

    const upcoming = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'next-track',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 1
    });
    assert.equal(calls.length, 1, 'the next track must run Essentia exactly once');
    assert.equal(upcoming.beatConfidence, 0.87);
    assert.equal(upcoming.essentiaConfidence, 4.12);
    // The native tempo stays authoritative; only confidence is replaced.
    assert.ok(Number.isFinite(upcoming.nativeBeatConfidence));
    assert.notEqual(upcoming.bpm, 126.5);
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('a refusal from Essentia leaves the native confidence untouched', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-'));
  const nativeModulePath = path.resolve('native/build/Release/orchard_audio_analysis.node');
  const ipc = fakeIpcMain();
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath,
    logger: () => {},
    refineConfidence: () => Promise.reject(new Error('wasm unavailable'))
  });

  try {
    const track = tone();
    const result = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'unrefined',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 0
    });
    assert.ok(result, 'analysis must still succeed without Essentia');
    assert.equal(result.essentiaConfidence, undefined);
    assert.ok(Number.isFinite(result.beatConfidence));
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('audio analysis service caches native results across service restarts', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-'));
  const cachePath = path.join(directory, 'cache.json');
  const nativeModulePath = path.resolve('native/build/Release/orchard_audio_analysis.node');
  const firstIpc = fakeIpcMain();
  const firstService = setupAudioAnalysisService({
    cachePath,
    ipcMain: firstIpc,
    nativeModulePath,
    logger: () => {}
  });

  try {
    assert.equal(await firstIpc.invoke('audio-analysis:available'), true);
    const audio = tone();
    const analyzed = await firstIpc.invoke('audio-analysis:analyze', {
      trackId: 'cached-track',
      duration: audio.duration,
      sampleRate: audio.sampleRate,
      samples: audio.samples.buffer
    });
    assert.equal(analyzed.analysisVersion, AUDIO_ANALYSIS_VERSION);

    const memoryHit = await firstIpc.invoke('audio-analysis:analyze', { trackId: 'cached-track' });
    assert.deepEqual(memoryHit, analyzed);
    await firstService.stop();

    const secondIpc = fakeIpcMain();
    const secondService = setupAudioAnalysisService({
      cachePath,
      ipcMain: secondIpc,
      nativeModulePath,
      logger: () => {}
    });
    const diskHit = await secondIpc.invoke('audio-analysis:get', 'cached-track');
    assert.deepEqual(diskHit, analyzed);
    await secondService.stop();
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('audio analysis service retries a native addon that was unavailable at startup', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-retry-'));
  const cachePath = path.join(directory, 'cache.json');
  const ipc = fakeIpcMain();
  const expected = {
    analysisVersion: AUDIO_ANALYSIS_VERSION,
    duration: 8,
    bpm: 120,
    beatInterval: 0.5,
    beats: [0, 0.5],
    downbeats: [0],
    phraseBoundaries: [0, 4],
    mixOutTime: 7.5
  };
  let loadAttempts = 0;
  const logs = [];
  const service = setupAudioAnalysisService({
    cachePath,
    ipcMain: ipc,
    nativeModulePath: 'test-native-addon',
    loadNativeAddon() {
      loadAttempts += 1;
      if (loadAttempts === 1) throw new Error('Native module is not ready yet');
      return { analysisVersion: AUDIO_ANALYSIS_VERSION, analyze: async () => expected };
    },
    logger: (event, details) => logs.push({ event, details })
  });

  try {
    assert.equal(await ipc.invoke('audio-analysis:available'), true);
    assert.equal(loadAttempts, 2);
    await ipc.invoke('audio-analysis:debug', {
      event: 'decode-failed',
      details: { trackId: 'startup-retry', errorMessage: 'Failed to fetch' }
    });
    assert.ok(logs.some((entry) => entry.event === 'native-load-failed'));
    assert.ok(logs.some((entry) => entry.event === 'renderer:decode-failed'));
    const audio = tone();
    const analyzed = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'startup-retry',
      duration: audio.duration,
      sampleRate: audio.sampleRate,
      samples: audio.samples.buffer
    });
    assert.equal(analyzed.bpm, expected.bpm);
    assert.equal(analyzed.bpmSource, 'local-native');
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('audio analysis service persists worker fallback results across restarts', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-worker-'));
  const cachePath = path.join(directory, 'cache.json');
  const result = {
    analysisVersion: AUDIO_ANALYSIS_VERSION,
    duration: 200,
    bpm: 118,
    beatInterval: 60 / 118,
    beatConfidence: 0.7,
    analysisSource: 'local-worker',
    beats: [0, 60 / 118],
    downbeats: [0],
    phraseBoundaries: [0, 16]
  };
  const firstIpc = fakeIpcMain();
  const first = setupAudioAnalysisService({
    cachePath,
    ipcMain: firstIpc,
    nativeModulePath: 'missing-native-addon',
    loadNativeAddon: () => { throw new Error('native unavailable'); },
    logger: () => {}
  });

  try {
    assert.equal(await firstIpc.invoke('audio-analysis:store', { trackId: 'worker-track', result }), true);
    await first.stop();

    const secondIpc = fakeIpcMain();
    const second = setupAudioAnalysisService({
      cachePath,
      ipcMain: secondIpc,
      nativeModulePath: 'missing-native-addon',
      loadNativeAddon: () => { throw new Error('native unavailable'); },
      logger: () => {}
    });
    const cached = await secondIpc.invoke('audio-analysis:get', 'worker-track');
    assert.equal(cached.bpm, 118);
    assert.equal(cached.analysisSource, 'local-worker');
    await second.stop();
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('audio analysis service rejects invalid BPM and redacts sensitive diagnostics', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-safe-'));
  const logs = [];
  const ipc = fakeIpcMain();
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath: 'missing-native-addon',
    loadNativeAddon: () => { throw new Error('native unavailable'); },
    logger: (event, details) => logs.push({ event, details })
  });

  try {
    await assert.rejects(ipc.invoke('audio-analysis:store', {
      trackId: 'invalid',
      result: { analysisVersion: AUDIO_ANALYSIS_VERSION, duration: 10, bpm: 0, beatInterval: 0 }
    }), /complete local audio analysis/i);
    await assert.rejects(ipc.invoke('audio-analysis:store', {
      trackId: 'incomplete',
      result: { analysisVersion: AUDIO_ANALYSIS_VERSION, duration: 10, bpm: 120, beatInterval: 0.5 }
    }), /complete local audio analysis/i);
    assert.equal(await ipc.invoke('audio-analysis:get', 'invalid'), null);

    await ipc.invoke('audio-analysis:debug', {
      event: 'decode-failed',
      details: {
        cookie: 'SID=private',
        authorization: 'Bearer private',
        message: 'https://stream.example/audio?signature=secret&token=private'
      }
    });
    const serialized = JSON.stringify(logs);
    assert.doesNotMatch(serialized, /SID=private|Bearer private|signature=secret|token=private/);
    assert.match(serialized, /redacted/);
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('audio analysis service renders beat-matched transitions over IPC', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-transition-'));
  const cachePath = path.join(directory, 'cache.json');
  const ipc = fakeIpcMain();
  const service = setupAudioAnalysisService({
    cachePath,
    ipcMain: ipc,
    nativeModulePath: path.resolve('native/build/Release/orchard_audio_analysis.node'),
    logger: () => {}
  });

  function stereoTone(seconds, frequency) {
    const sampleRate = 44100;
    const data = new Float32Array(Math.floor(seconds * sampleRate));
    for (let index = 0; index < data.length; index += 1) {
      data[index] = 0.3 * Math.sin((2 * Math.PI * frequency * index) / sampleRate);
    }
    return [data, new Float32Array(data)];
  }

  try {
    const result = await ipc.invoke('audio-analysis:render-transition', {
      outgoing: { channels: stereoTone(12, 220), anchor: 1, bpm: 126 },
      incoming: { channels: stereoTone(12, 330), anchor: 1, bpm: 126 },
      options: { sampleRate: 44100, beats: 8, bassSwap: 0.75 }
    });
    assert.equal(result.rendered, true, result.rejected);
    assert.equal(result.channels.length, 2);
    const expected = 8 * (60 / 126) * 44100;
    assert.ok(Math.abs(result.channels[0].length - expected) < 4);
    assert.equal(result.bpm, 126);

    const refused = await ipc.invoke('audio-analysis:render-transition', {
      outgoing: { channels: stereoTone(12, 220), anchor: 1, bpm: 100 },
      incoming: { channels: stereoTone(12, 330), anchor: 1, bpm: 126 },
      options: { sampleRate: 44100, beats: 8 }
    });
    assert.equal(refused.rendered, false);
    assert.match(refused.rejected, /transparent stretch range/);

    await assert.rejects(
      ipc.invoke('audio-analysis:render-transition', {
        outgoing: { channels: [], anchor: 0, bpm: 126 },
        incoming: { channels: stereoTone(2, 330), anchor: 0, bpm: 126 },
        options: { sampleRate: 44100 }
      }),
      /Invalid outgoing PCM/
    );
    await assert.rejects(
      ipc.invoke('audio-analysis:render-transition', {
        outgoing: { channels: stereoTone(2, 220), anchor: 0, bpm: 126 },
        incoming: { channels: stereoTone(2, 330), anchor: 0, bpm: 126 },
        options: {}
      }),
      /valid sample rate/
    );
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('beat-model windows pre-empt Essentia, and its refusal restores it', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-'));
  const nativeModulePath = path.resolve('native/build/Release/orchard_audio_analysis.node');
  const essentiaCalls = [];
  const modelCalls = [];
  const ipc = fakeIpcMain();
  let modelAnswer = {
    beatConfidence: 0.93,
    beatModelChecked: true,
    beatModelBpm: 126,
    beatModelAgreement: 0.02,
    downbeats: [1, 2, 3]
  };
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath,
    logger: () => {},
    refineConfidence: () => {
      essentiaCalls.push(1);
      return { beatConfidence: 0.6, essentiaConfidence: 2.0, essentiaBpm: 120, elapsedMs: 1 };
    },
    refineBeats: (rawResult, windows, { beatSpectrogram, track }) => {
      modelCalls.push({
        windows: windows.length,
        hasSpectrogram: typeof beatSpectrogram === 'function',
        // Inference must be routed through the utility process, never run
        // inline in the main process.
        hasTrack: typeof track === 'function'
      });
      return modelAnswer;
    },
    createModelHost: () => ({ track: async () => null, stop: () => {} })
  });

  try {
    const track = tone();
    const windowed = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'model-track',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 1,
      beatWindows: [
        { samples: new Float32Array(22050), sampleRate: 22050, offsetSeconds: 0 },
        { samples: new Float32Array(22050), sampleRate: 22050, offsetSeconds: 100 }
      ]
    });
    assert.deepEqual(modelCalls, [{ windows: 2, hasSpectrogram: true, hasTrack: true }]);
    assert.equal(essentiaCalls.length, 0, 'a model verdict must skip the Essentia pass');
    assert.equal(windowed.beatConfidence, 0.93);
    assert.deepEqual(windowed.downbeats, [1, 2, 3]);

    // No opinion from the model: the Essentia pass is back on duty.
    modelAnswer = null;
    const declined = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'declined-track',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 1,
      beatWindows: [{ samples: new Float32Array(22050), sampleRate: 22050, offsetSeconds: 0 }]
    });
    assert.equal(essentiaCalls.length, 1, 'a declined model must fall back to Essentia');
    assert.equal(declined.beatConfidence, 0.6);

    // No windows at all -- a background-priority analysis -- never touches
    // the model.
    const bare = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'bare-track',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 2
    });
    assert.equal(modelCalls.length, 2, 'an analysis without windows must not call the model');
    assert.ok(bare.bpm > 0);
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('a declined model still stamps beatModelChecked so the track is not re-analysed forever', async () => {
  // `beatModelChecked` records that the pass ran, not that it produced a
  // verdict -- exactly like `essentiaChecked`. Without the stamp the renderer's
  // cache gate would re-decode and re-analyse the track on every play.
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-'));
  const nativeModulePath = path.resolve('native/build/Release/orchard_audio_analysis.node');
  const ipc = fakeIpcMain();
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath,
    logger: () => {},
    refineConfidence: () => null,
    refineBeats: () => null,
    createModelHost: () => ({ track: async () => null, stop: () => {} })
  });

  try {
    const track = tone();
    const declined = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'declined-model',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 1,
      beatWindows: [{ samples: new Float32Array(22050), sampleRate: 22050, offsetSeconds: 0 }]
    });
    assert.equal(declined.beatModelChecked, true);

    // A background analysis, which never had windows, must not claim the pass ran.
    const background = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'no-windows',
      samples: track.samples,
      sampleRate: track.sampleRate,
      duration: track.duration,
      priority: 2
    });
    assert.notEqual(background.beatModelChecked, true);
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
