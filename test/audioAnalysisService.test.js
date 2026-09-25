/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

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
  const nativeModulePath = path.resolve('native-audio-rust/index.cjs');
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
  const nativeModulePath = path.resolve('native-audio-rust/index.cjs');
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
  const nativeModulePath = path.resolve('native-audio-rust/index.cjs');
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
        message: 'https://stream.example/audio?signature=secret&token=private',
        candidates: Array.from({ length: 25 }, (_, index) => ({
          index,
          accessToken: `secret-${index}`,
          nested: { more: { privateUrl: `https://stream.example/${index}?token=private` } }
        }))
      }
    });
    const serialized = JSON.stringify(logs);
    assert.doesNotMatch(serialized, /SID=private|Bearer private|signature=secret|token=private/);
    assert.match(serialized, /redacted/);
    const debug = logs.find((entry) => entry.event === 'renderer:decode-failed');
    assert.equal(debug.details.candidates.length, 20);
    assert.equal(debug.details.candidates[0].accessToken, '[redacted]');
    assert.equal(debug.details.candidates[0].nested.more, '[truncated]');
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
    nativeModulePath: path.resolve('native-audio-rust/index.cjs'),
    transitionModulePath: path.resolve('native-audio-rust/index.cjs'),
    logger: () => {}
  });

  const SAMPLE_RATE = 44100;
  const BPM = 126;

  // A kick-like pulse train: the engine measures bands and transients, so a bare
  // sine gives it nothing to tell one candidate from another.
  function source(seconds, frequency) {
    const beatFrames = Math.floor((60 / BPM) * SAMPLE_RATE);
    const data = new Float32Array(Math.floor(seconds * SAMPLE_RATE));
    for (let index = 0; index < data.length; index += 1) {
      const time = index / SAMPLE_RATE;
      const beatPhase = (index % beatFrames) / SAMPLE_RATE;
      data[index] = 0.6 * Math.exp(-beatPhase * 14) * Math.sin(2 * Math.PI * 55 * time)
        + 0.2 * Math.sin(2 * Math.PI * frequency * time);
    }
    const interval = 60 / BPM;
    const beats = [];
    for (let index = 0; index * interval < seconds; index += 1) beats.push(index * interval);
    return {
      channels: [data, new Float32Array(data)],
      sampleRate: SAMPLE_RATE,
      bpm: BPM,
      beats,
      downbeats: beats.filter((_, index) => index % 4 === 0)
    };
  }

  // Both anchors sit on a downbeat, because every reachable end does.
  const bar = 4 * (60 / BPM);
  const mixOut = Math.round(24 / bar) * bar;
  const drop = Math.round(12 / bar) * bar;

  try {
    const result = await ipc.invoke('audio-analysis:render-transition', {
      outgoing: source(30, 220),
      incoming: source(30, 330),
      options: {
        outgoing: { endEarliest: mixOut - 0.05, endLatest: mixOut + 0.05 },
        incoming: { endEarliest: drop - 0.05, endLatest: drop + 0.05 },
        beatLengths: [8]
      }
    });
    assert.equal(result.rendered, true, result.rejected);
    assert.equal(result.channels.length, 2);
    assert.equal(result.beats, 8);
    const expected = 8 * (60 / BPM) * SAMPLE_RATE;
    assert.ok(Math.abs(result.channels[0].length - expected) < 4);
    assert.equal(result.bpm, BPM);
    // Both anchors are honoured, which is the whole point of constraining.
    assert.ok(Math.abs(result.outgoingResume - mixOut) <= 0.05);
    assert.ok(Math.abs(result.incomingResume - drop) <= 0.05);
    assert.ok(result.strategy.length > 0);

    // A window between downbeats is refused rather than approximated, and a
    // refusal is reported for the caller to fall back on, never thrown.
    const refused = await ipc.invoke('audio-analysis:render-transition', {
      outgoing: source(30, 220),
      incoming: source(30, 330),
      options: {
        incoming: { endEarliest: drop + 0.9, endLatest: drop + 1.0 }
      }
    });
    assert.equal(refused.rendered, false);
    assert.match(refused.rejected, /no viable transition/i);

    await assert.rejects(
      ipc.invoke('audio-analysis:render-transition', {
        outgoing: { ...source(4, 220), channels: [] },
        incoming: source(4, 330),
        options: {}
      }),
      /Invalid outgoing PCM/
    );
    await assert.rejects(
      ipc.invoke('audio-analysis:render-transition', {
        outgoing: { ...source(4, 220), sampleRate: 0 },
        incoming: source(4, 330),
        options: {}
      }),
      /Invalid outgoing PCM/
    );
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('planned transition payloads use the exact native method without search constraints', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-selected-transition-'));
  const ipc = fakeIpcMain();
  const plannedCalls = [];
  const legacyCalls = [];
  const logs = [];
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath: 'analysis-addon',
    transitionModulePath: 'transition-addon',
    loadNativeAddon(modulePath) {
      if (modulePath === 'analysis-addon') {
        return { analysisVersion: AUDIO_ANALYSIS_VERSION, analyze: async () => null };
      }
      return {
        renderTransition: async (...args) => {
          legacyCalls.push(args);
          throw new Error('free planning must not run');
        },
        renderPlannedTransition: async (outgoing, incoming, plan, options) => {
          plannedCalls.push({ outgoing, incoming, plan, options });
          return {
            rendered: true,
            rejected: '',
            channels: [new Float32Array(64), new Float32Array(64)],
            sampleRate: 8000,
            duration: plan.duration,
            beats: plan.beats,
            strategy: plan.strategy,
            outgoingStart: plan.outgoingStart,
            incomingStart: plan.incomingStart,
            outgoingResume: plan.outgoingStart + plan.duration * plan.outgoingTempoRatio,
            incomingResume: plan.incomingStart + plan.duration * plan.incomingTempoRatio,
            outgoingTempoRatio: plan.outgoingTempoRatio,
            incomingTempoRatio: plan.incomingTempoRatio,
            targetBpm: plan.targetBpm,
            summary: 'selected'
          };
        }
      };
    },
    logger: (event, details) => logs.push({ event, details })
  });
  const source = {
    channels: [new Float32Array(8000 * 12), new Float32Array(8000 * 12)],
    sampleRate: 8000,
    bpm: 120,
    beats: [0, 0.5, 1],
    downbeats: [0]
  };
  const plan = {
    outgoingStart: 1.5,
    incomingStart: 1.5,
    duration: 8,
    beats: 16,
    outgoingBpm: 120,
    incomingBpm: 120,
    targetBpm: 120,
    outgoingTempoRatio: 1,
    incomingTempoRatio: 1,
    strategy: 'filtered_blend'
  };

  try {
    const result = await ipc.invoke('audio-analysis:render-transition', {
      outgoing: source,
      incoming: source,
      options: {
        plan,
        duckCurve: [0.1, 0.5, 0.9],
        // These must never leak into an exact render even if a stale caller
        // supplied them beside the authoritative plan.
        outgoing: { endEarliest: 1, endLatest: 2 },
        incoming: { endEarliest: 3, endLatest: 4 },
        beatLengths: [4, 8, 16]
      }
    });

    assert.equal(legacyCalls.length, 0);
    assert.equal(plannedCalls.length, 1);
    assert.deepEqual(plannedCalls[0].plan, plan);
    assert.deepEqual(plannedCalls[0].options, { duckCurve: [0.1, 0.5, 0.9] });
    assert.equal(result.outgoingStart, plan.outgoingStart);
    assert.equal(result.incomingStart, plan.incomingStart);
    assert.equal(result.outgoingResume, 9.5);
    assert.equal(result.incomingResume, 9.5);
    assert.ok(logs.some(({ event, details }) =>
      event === 'transition-render-ready' && details.requestedOutgoingStart === 1.5
    ));

    await assert.rejects(ipc.invoke('audio-analysis:render-transition', {
      outgoing: source,
      incoming: source,
      options: { plan: { ...plan, duration: Number.NaN } }
    }), /Invalid transition plan/i);
    assert.equal(plannedCalls.length, 1, 'invalid plans must be rejected before native work');
  } finally {
    await service.stop();
    await rm(directory, { recursive: true, force: true });
  }
});

test('beat-model windows pre-empt Essentia, and its refusal restores it', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-'));
  const nativeModulePath = path.resolve('native-audio-rust/index.cjs');
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

test('analysis service rebuilds rhythmic evidence after the beat model moves downbeats', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-final-grid-'));
  const ipc = fakeIpcMain();
  const beats = Array.from({ length: 21 }, (_, index) => index * 0.5);
  const raw = {
    analysisVersion: AUDIO_ANALYSIS_VERSION,
    duration: 10,
    bpm: 120,
    beatInterval: 0.5,
    beatConfidence: 0.7,
    beats,
    downbeats: [0, 2, 4, 6, 8, 10],
    phraseBoundaries: [0, 8],
    audibleStartTime: 0,
    contentEndTime: 10,
    energyCurve: [{ time: 0, energy: 0.2 }, { time: 10, energy: 0.4 }]
  };
  const service = setupAudioAnalysisService({
    cachePath: path.join(directory, 'cache.json'),
    ipcMain: ipc,
    nativeModulePath: 'test-native-addon',
    loadNativeAddon: () => ({
      analysisVersion: AUDIO_ANALYSIS_VERSION,
      analyze: async () => raw,
      beatSpectrogram: async () => ({ values: new Float32Array(1), frames: 1, mels: 1 })
    }),
    logger: () => {},
    refineConfidence: () => null,
    refineBeats: () => ({
      beatConfidence: 0.9,
      beatModelChecked: true,
      beatModelAgreement: 0.01,
      downbeats: [1, 3, 5, 7, 9]
    }),
    createModelHost: () => ({ track: async () => null, stop: () => {} })
  });

  try {
    const audio = tone();
    const result = await ipc.invoke('audio-analysis:analyze', {
      trackId: 'final-grid',
      samples: audio.samples,
      sampleRate: audio.sampleRate,
      duration: audio.duration,
      priority: 1,
      beatWindows: [{ samples: new Float32Array(22050), sampleRate: 22050, offsetSeconds: 0 }]
    });

    assert.deepEqual(result.timing.downbeats, [1, 3, 5, 7, 9]);
    assert.deepEqual(
      result.boundaries
        .filter((boundary) => boundary.source === 'rhythmic-fallback')
        .map((boundary) => boundary.time),
      [1, 9]
    );
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
  const nativeModulePath = path.resolve('native-audio-rust/index.cjs');
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
