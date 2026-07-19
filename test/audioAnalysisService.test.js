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
    assert.equal(analyzed.analysisVersion, 4);

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
  const expected = { analysisVersion: 4, duration: 8, mixOutTime: 7.5 };
  let loadAttempts = 0;
  const logs = [];
  const service = setupAudioAnalysisService({
    cachePath,
    ipcMain: ipc,
    nativeModulePath: 'test-native-addon',
    loadNativeAddon() {
      loadAttempts += 1;
      if (loadAttempts === 1) throw new Error('Native module is not ready yet');
      return { analysisVersion: 4, analyze: async () => expected };
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
    assert.deepEqual(await ipc.invoke('audio-analysis:analyze', {
      trackId: 'startup-retry',
      duration: audio.duration,
      sampleRate: audio.sampleRate,
      samples: audio.samples.buffer
    }), expected);
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
