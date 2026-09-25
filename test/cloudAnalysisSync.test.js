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
import test from 'node:test';

import {
  AUDIO_ANALYSIS_VERSION,
  isValidLocalAnalysis
} from '../shared/audioAnalysis.js';
import { finalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import { fetchBatchCloudAnalysis } from '../src/services/cloudAnalysisSync.js';
import { supabaseClient } from '../src/services/supabaseClient.js';

function analysis(version = AUDIO_ANALYSIS_VERSION) {
  const bpm = 120;
  const duration = 20;
  const beatInterval = 60 / bpm;
  const beats = Array.from({ length: 41 }, (_, index) => index * beatInterval);
  return finalizeTrackAnalysis({
    analysisVersion: version,
    duration,
    bpm,
    beatInterval,
    beatConfidence: 0.9,
    downbeatConfidence: 0.9,
    beats,
    downbeats: beats.filter((_, index) => index % 4 === 0),
    audibleStartTime: 0,
    contentEndTime: duration,
    key: 'C major',
    keyConfidence: 0.8,
    transitionFeatureFrames: [
      { time: 0, energy: 0.4, low: 0.3, mid: 0.5, high: 0.2, vocal: 0.1 },
      { time: duration, energy: 0.4, low: 0.3, mid: 0.5, high: 0.2, vocal: 0.1 }
    ],
    structuralBoundaryCandidates: [],
    meter: { beatsPerBar: 4, confidence: 0.15, source: 'assumed-4-4' }
  });
}

function row(videoId, payload, rowVersion = payload.analysisVersion) {
  return {
    video_id: videoId,
    duration: payload.duration,
    bpm: payload.bpm,
    musical_key: payload.key,
    key_confidence: payload.keyConfidence,
    beat_confidence: payload.beatConfidence,
    analysis_version: rowVersion,
    analysis_data: payload
  };
}

test('cloud cache accepts only a complete current-version analysis', async (t) => {
  const originalFetch = supabaseClient.fetchTrackAnalysis;
  t.after(() => {
    supabaseClient.fetchTrackAnalysis = originalFetch;
  });

  const current = analysis();
  const legacy = analysis(AUDIO_ANALYSIS_VERSION - 1);
  supabaseClient.fetchTrackAnalysis = async () => [
    row('legacy-row', legacy),
    row('legacy-payload', legacy, AUDIO_ANALYSIS_VERSION),
    row('partial-current', { analysisVersion: AUDIO_ANALYSIS_VERSION, bpm: 120 }),
    row('current', current)
  ];

  const result = await fetchBatchCloudAnalysis([
    'legacy-row',
    'legacy-payload',
    'partial-current',
    'current'
  ]);

  assert.deepEqual([...result.keys()], ['current']);
  assert.equal(result.get('current').analysisVersion, AUDIO_ANALYSIS_VERSION);
  assert.equal(result.get('current').analysisSource, 'cloud-cache');
  assert.equal(isValidLocalAnalysis(result.get('current')), true);
});
