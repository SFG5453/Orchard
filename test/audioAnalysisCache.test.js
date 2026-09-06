/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import { access, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';

import {
  audioAnalysisCacheInternals,
  createAudioAnalysisCache
} from '../electron/audio/audioAnalysisCache.js';
import {
  AUDIO_ANALYSIS_VERSION,
  isValidLocalAnalysis,
  localAnalysisWithSource
} from '../shared/audioAnalysis.js';

function analysis(seed = 0) {
  const frames = [0, 1, 2].map((time) => ({
    time,
    energy: 0.2 + seed,
    low: 0.1,
    mid: 0.2,
    high: 0.3,
    vocal: 0.4,
    novelty: 0.5,
    transientDensity: 0.6,
    stability: 0.7
  }));
  return localAnalysisWithSource({
    analysisVersion: AUDIO_ANALYSIS_VERSION,
    duration: 2,
    bpm: 120,
    beatInterval: 0.5,
    beatConfidence: 0.8,
    beats: [0, 0.5, 1, 1.5, 2],
    downbeats: [0, 2],
    chroma: Array.from({ length: 12 }, (_, index) => index / 12),
    energyCurve: frames.map(({ time, energy }) => ({ time, energy })),
    lowEnergyCurve: frames.map(({ time, low }) => ({ time, energy: low })),
    midEnergyCurve: frames.map(({ time, mid }) => ({ time, energy: mid })),
    highEnergyCurve: frames.map(({ time, high }) => ({ time, energy: high })),
    vocalActivityMask: frames.map(({ vocal }) => vocal),
    transitionFeatureFrames: frames,
    structuralBoundaryCandidates: []
  }, 'local-native');
}

test('analysis cache compaction round-trips derived and legacy views', () => {
  const original = analysis();
  const compact = audioAnalysisCacheInternals.compactAnalysis(original);
  assert.equal(compact.frames, undefined);
  assert.equal(compact.timing, undefined);
  assert.equal(compact.energyCurve, undefined);
  assert.ok(Array.isArray(compact.transitionFeatureFrames));
  assert.deepEqual(audioAnalysisCacheInternals.expandAnalysis(compact), original);
});

test('analysis cache migrates legacy JSON into bounded SQLite rows', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'orchard-analysis-cache-'));
  const databasePath = path.join(directory, 'cache.sqlite3');
  const legacyPath = path.join(directory, 'cache.json');
  const records = Array.from({ length: 4 }, (_, index) => ({
    trackId: `track-${index}`,
    lastUsed: index,
    result: analysis(index / 100)
  }));
  await writeFile(legacyPath, JSON.stringify({
    version: AUDIO_ANALYSIS_VERSION,
    items: records
  }));

  const cache = createAudioAnalysisCache({
    databasePath,
    legacyPath,
    version: AUDIO_ANALYSIS_VERSION,
    maxItems: 3,
    hotItems: 1,
    cleanTrackId: (value) => String(value || ''),
    validate: isValidLocalAnalysis
  });

  try {
    await cache.ready;
    await access(`${legacyPath}.migrated-v${AUDIO_ANALYSIS_VERSION}`);
    assert.deepEqual(cache.get('track-3'), records[3].result);
    assert.equal(cache.get('track-0'), null, 'oldest migrated row must be pruned');
    cache.close();

    const database = new DatabaseSync(databasePath, { readOnly: true });
    try {
      assert.equal(database.prepare('SELECT count(*) AS count FROM audio_analysis').get().count, 3);
      const stored = JSON.parse(database.prepare(
        'SELECT result_json FROM audio_analysis WHERE track_id = ?'
      ).get('track-3').result_json);
      assert.equal(stored.frames, undefined);
      assert.equal(stored.energyCurve, undefined);
      assert.ok(Array.isArray(stored.transitionFeatureFrames));
    } finally {
      database.close();
    }
    const backup = JSON.parse(await readFile(
      `${legacyPath}.migrated-v${AUDIO_ANALYSIS_VERSION}`,
      'utf8'
    ));
    assert.equal(backup.items.length, 4);
  } finally {
    try {
      cache.close();
    } catch {}
    await rm(directory, { recursive: true, force: true });
  }
});
