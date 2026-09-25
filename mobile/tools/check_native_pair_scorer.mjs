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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mobileTransitionCases } from '../../test/fixtures/mobileTransitionPlanner.js';
import { planPairTransition } from '../../src/audio/crossfade/pairTransitionPlanner.js';
import { bestTransitionOrder, transitionCost } from '../../src/app/playback/queueTransitionSort.js';
import { finalizeTrackAnalysis } from '../../shared/trackAnalysis.js';

const binary = process.argv[2];
if (!binary) throw new Error('Usage: node mobile/tools/check_native_pair_scorer.mjs <native CLI>');
const fixture = JSON.parse(readFileSync('artifacts/transition-pinkpantheress/v3_planner_input_device_gpu.json'));
const cases = mobileTransitionCases.filter(({ name }) => name.endsWith('@0'))
  .map(({ name, input }) => ({ name, analysis: input.analysis, nextAnalysis: input.nextAnalysis }));
cases.push({ name: 'real-v3', analysis: fixture.analysis, nextAnalysis: fixture.nextAnalysis });
cases.push({ name: 'canonical-v3', analysis: finalizeTrackAnalysis(fixture.analysis),
  nextAnalysis: finalizeTrackAnalysis(fixture.nextAnalysis) });
cases.push({ name: 'compact-dsp',
  analysis: { duration: 120, bpm: 120, beatConfidence: 0.7, key: 'C major', keyConfidence: 0.6,
    downbeats: [0, 2, 4, 6, 8, 10, 12, 14, 16, 112, 114, 116, 118, 120],
    energyCurve: [{ t: 0, e: 0.5 }, { t: 120, e: 0.3 }], vocalProbability: 0.4 },
  nextAnalysis: { duration: 120, bpm: 123, beatConfidence: 0.6, key: 'G major', keyConfidence: 0.7,
    downbeats: [0, 2, 4, 6, 8, 10, 12, 14, 16, 112, 114, 116, 118, 120],
    energyCurve: [{ t: 0, e: 0.4 }, { t: 120, e: 0.6 }], vocalProbability: 0.3 } });

const deviceNames = ['safe', 'tempo-shift', 'distant-tempo', 'weak-grid',
  'missing-evidence', 'vocal-collision', 'real-v3'];
const devicePairs = deviceNames.map(name => {
  const source = cases.find(item => item.name === name || item.name === `${name}@0`);
  const { analysis, nextAnalysis } = source;
  const plan = planPairTransition({ analysis, nextAnalysis,
    duration: analysis.duration || 0, nextDuration: nextAnalysis.duration || 0 });
  return { name, analysis, nextAnalysis, expected: {
    transitionClass: plan.transitionClass,
    confidence: plan.confidence,
    quality: plan.diagnostics?.selected?.quality ?? plan.confidence,
    selectedId: plan.diagnostics?.selected?.id ?? null,
    cost: transitionCost(analysis, nextAnalysis)
  } };
});
const deviceAnalyses = [devicePairs[0].analysis, devicePairs[1].nextAnalysis,
  devicePairs[2].nextAnalysis, devicePairs[3].nextAnalysis, devicePairs[4].nextAnalysis];
const deviceTracks = deviceAnalyses.map((_, index) => ({ id: String(index) }));
const deviceExpected = bestTransitionOrder(deviceTracks,
  new Map(deviceAnalyses.map((analysis, index) => [String(index), analysis])), {})
  .ordered.map(track => Number(track.id));
assert.deepEqual(JSON.parse(readFileSync('mobile/android/app/src/androidTest/assets/native_bestmix_parity.json')),
  { pairs: devicePairs, queue: { analyses: deviceAnalyses, expected: deviceExpected } },
  'Android fixture is stale relative to desktop sources');

let state = 545;
function random() {
  state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
  return state / 4294967296;
}
function vary(source, role) {
  const value = structuredClone(source);
  const bpm = [0, 60, 87, 116, 120, 121, 124, 127, 142][Math.floor(random() * 9)];
  value.bpm = bpm;
  value.beatConfidence = [0.1, 0.35, 0.54, 0.55, 0.7, 0.92][Math.floor(random() * 6)];
  value.key = ['C major', 'A minor', 'F# minor', 'G major', 'unknown'][Math.floor(random() * 5)];
  value.keyConfidence = [0, 0.2, 0.65, 0.9][Math.floor(random() * 4)];
  value.vocalProbability = random();
  if (random() < 0.3) value.structuralBoundaryCandidates = [];
  if (random() < 0.25) value.downbeats = [];
  if (random() < 0.2) value.transitionFeatureFrames = [];
  if (random() < 0.3 && value.transitionFeatureFrames?.length) {
    value.transitionFeatureFrames = value.transitionFeatureFrames.map(frame => ({
      ...frame, vocal: random() < 0.5 ? 0.05 : 0.9, low: random() < 0.5 ? 0.2 : 0.85
    }));
  }
  if (random() < 0.15) value.contentEndTime = role === 'outgoing' ? 90 : 110;
  if (random() < 0.15) value.audibleStartTime = role === 'incoming' ? 5 : 0;
  return value;
}
for (let index = 0; index < 80; index += 1) {
  cases.push({
    name: `varied-${index}`,
    analysis: vary(cases[0].analysis, 'outgoing'),
    nextAnalysis: vary(cases[0].nextAnalysis, 'incoming')
  });
}

const queueCases = [];
for (let index = 0; index < 12; index += 1) {
  const analyses = Array.from({ length: 4 + index % 4 }, (_, offset) =>
    offset === 3 && index % 3 === 0 ? null :
      vary(offset % 2 ? cases[0].analysis : cases[0].nextAnalysis, offset % 2 ? 'outgoing' : 'incoming'));
  queueCases.push({ name: `queue-${index}`, analyses,
    initial: index % 2 ? vary(cases[0].analysis, 'outgoing') : null });
}
queueCases.push({ name: 'canonical-queue', analyses: [
  finalizeTrackAnalysis(cases[0].analysis),
  finalizeTrackAnalysis(cases[1].nextAnalysis),
  finalizeTrackAnalysis(cases[2].nextAnalysis),
  finalizeTrackAnalysis(cases[3].nextAnalysis)
], initial: {} });

const requests = [
  ...cases.map(({ analysis, nextAnalysis }) => ({ analysis, nextAnalysis })),
  ...queueCases.map(({ analyses, initial }) => ({ analyses, initial }))
];
const child = spawnSync(binary, [], {
  input: requests.map(request => JSON.stringify(request)).join('\n') + '\n',
  encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout: 180_000
});
if (child.status !== 0) throw new Error(`Native scorer exited ${child.status}: ${child.stderr}`);
const outputs = child.stdout.trim().split('\n').map(JSON.parse);
assert.equal(outputs.length, requests.length);

function close(actual, expected, name) {
  if (typeof actual === 'number' && typeof expected === 'number') {
    assert.ok(Math.abs(actual - expected) <= 1e-8, `${name}: ${actual} != ${expected}`);
  } else assert.deepEqual(actual, expected, name);
}
for (let index = 0; index < cases.length; index += 1) {
  const { name, analysis, nextAnalysis } = cases[index];
  const native = outputs[index];
  const desktop = planPairTransition({ analysis, nextAnalysis,
    duration: analysis.duration || 0, nextDuration: nextAnalysis.duration || 0 });
  const selected = desktop.diagnostics?.selected;
  const expected = {
    transitionClass: desktop.transitionClass,
    confidence: desktop.confidence,
    quality: selected?.quality ?? desktop.confidence,
    selectedId: selected?.id ?? null,
    strategy: desktop.strategy,
    outgoingStart: desktop.outgoing.start,
    outgoingEnd: desktop.outgoing.end,
    incomingStart: desktop.incoming.start,
    incomingHandoff: desktop.incoming.handoff,
    durationSeconds: desktop.durationSeconds,
    beats: desktop.beats,
    targetBpm: desktop.targetBpm,
    outgoingRatio: desktop.outgoing.tempoRatio,
    incomingRatio: desktop.incoming.tempoRatio,
    gates: selected?.gates,
    cost: transitionCost(analysis, nextAnalysis)
  };
  for (const [field, value] of Object.entries(expected)) {
    try { close(native[field], value, `${name}.${field}`); }
    catch (error) {
      console.error(JSON.stringify({ name, field, outgoing: {
        bpm: analysis.bpm, key: analysis.key, keyConfidence: analysis.keyConfidence,
        beatConfidence: analysis.beatConfidence
      }, incoming: {
        bpm: nextAnalysis.bpm, key: nextAnalysis.key, keyConfidence: nextAnalysis.keyConfidence,
        beatConfidence: nextAnalysis.beatConfidence
      }, native }, null, 2));
      throw error;
    }
  }
}
for (let index = 0; index < queueCases.length; index += 1) {
  const { name, analyses, initial } = queueCases[index];
  const tracks = analyses.map((_, position) => ({ id: String(position) }));
  const map = new Map(analyses.map((analysis, position) => [String(position), analysis]));
  const expected = bestTransitionOrder(tracks, map, initial || {}).ordered.map(track => Number(track.id));
  assert.deepEqual(outputs[cases.length + index], expected, name);
}
console.log(`${cases.length} pair decisions and ${queueCases.length} queue orders match desktop`);
