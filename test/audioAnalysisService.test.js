import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { setupAudioAnalysisService } from '../electron/audio/audioAnalysisService.js';

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
    assert.equal(analyzed.analysisVersion, 7);

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
    analysisVersion: 7,
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
      return { analysisVersion: 7, analyze: async () => expected };
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
    analysisVersion: 7,
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
      result: { analysisVersion: 7, duration: 10, bpm: 0, beatInterval: 0 }
    }), /complete local audio analysis/i);
    await assert.rejects(ipc.invoke('audio-analysis:store', {
      trackId: 'incomplete',
      result: { analysisVersion: 7, duration: 10, bpm: 120, beatInterval: 0.5 }
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
