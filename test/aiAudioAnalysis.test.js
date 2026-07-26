import assert from 'node:assert/strict';
import test from 'node:test';

import {
  mergeAiAudioAnalysis,
  withoutAiAudioAnalysis
} from '../shared/aiAudioAnalysis.js';

function deterministicAnalysis() {
  return {
    analysisVersion: 7,
    duration: 180,
    bpm: 120,
    beatInterval: 0.5,
    beats: [0, 0.5],
    downbeats: [0],
    phrases: [{ start: 0, end: 24, type: 'native_intro', confidence: 0.8 }],
    phraseBoundaries: [0, 24],
    introEndTime: 24,
    mixInTime: 24,
    mixInConfidence: 0.8,
    mixOutTime: 172,
    outroStartTime: 168,
    vocalProbability: 0.2
  };
}

test('trusted neural structure can be removed without losing deterministic cues', () => {
  const base = deterministicAnalysis();
  const merged = mergeAiAudioAnalysis(base, {
    aiAnalysisStatus: 'ready',
    aiPipeline: 'all-in-one-mix',
    aiStructureConfidence: 0.82,
    phrases: [
      { start: 0, end: 16, type: 'intro', confidence: 0.9 },
      { start: 16, end: 160, type: 'verse', confidence: 0.8 },
      { start: 160, end: 180, type: 'outro', confidence: 0.85 }
    ],
    phraseBoundaries: [0, 16, 160, 180],
    introEndTime: 16,
    mixInTime: 16,
    outroStartTime: 160,
    mixOutTime: 160,
    vocalActivityMask: [0.1, 0.9],
    vocalActivityFrameSeconds: 1
  }, 'models@one');

  assert.equal(merged.aiAnalysisStatus, 'ready');
  assert.equal(merged.mixInTime, 16);
  assert.equal(merged.mixOutTime, 160);
  assert.equal(merged.aiModelSignature, 'models@one');

  const restored = withoutAiAudioAnalysis(merged);
  assert.equal(restored.aiAnalysisStatus, undefined);
  assert.equal(restored.aiModelSignature, undefined);
  assert.equal(restored.mixInTime, base.mixInTime);
  assert.equal(restored.mixOutTime, base.mixOutTime);
  assert.deepEqual(restored.phrases, base.phrases);
});

test('low-confidence neural structure remains diagnostic and cannot replace timing', () => {
  const base = deterministicAnalysis();
  const merged = mergeAiAudioAnalysis(base, {
    aiAnalysisStatus: 'ready',
    aiStructureConfidence: 0.2,
    phrases: [{ start: 0, end: 12, type: 'intro', confidence: 0.2 }],
    mixInTime: 12,
    mixOutTime: 150
  }, 'models@one');

  assert.equal(merged.mixInTime, base.mixInTime);
  assert.equal(merged.mixOutTime, base.mixOutTime);
  assert.deepEqual(merged.phrases, base.phrases);
  assert.equal(merged.aiStructureConfidence, 0.2);
});
