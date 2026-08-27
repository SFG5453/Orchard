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
import test from 'node:test';

import { ANALYSIS_PRIORITIES } from '../src/audio/crossfade/smartCrossfadeAnalysis.js';
import { createAutomaticEq } from '../src/audio/engine/automaticEq.js';
import { analyzeAutomaticEqTempo } from '../src/app/playback/audioEngineActions.js';

test('Automatic EQ requests shared analysis at background priority without a crossfade-mode dependency', async () => {
  let request;
  const tempo = await analyzeAutomaticEqTempo({
    async analyze(trackId, streamUrl, options) {
      request = { trackId, streamUrl, options };
      return { bpm: 123.4 };
    }
  }, {
    id: 'track-1',
    streamUrl: 'https://example.test/audio',
    durationSeconds: 240
  });

  assert.equal(tempo, 123.4);
  assert.deepEqual(request, {
    trackId: 'track-1',
    streamUrl: 'https://example.test/audio',
    options: { duration: 240, priority: ANALYSIS_PRIORITIES.background }
  });
  assert.equal('forPlayback' in request.options, false);
});

test('Automatic EQ accepts the analyzed BPM compatibility field', async () => {
  const tempo = await analyzeAutomaticEqTempo({
    analyze: async () => ({ bpm: 0, analyzedBpm: 98 })
  }, { id: 'track-2' });

  assert.equal(tempo, 98);
});

test('Automatic EQ track changes no longer decode audio for a separate tempo pass', async () => {
  let decoded = false;
  const automaticEq = createAutomaticEq({
    analyzer: {
      decodeAudio: async () => {
        decoded = true;
      }
    }
  });

  await automaticEq.beginTrack({ id: 'track-3', streamUrl: 'https://example.test/audio' });

  assert.equal(decoded, false);
  assert.equal(automaticEq.hasTempo('track-3'), false);
  assert.equal(automaticEq.setTempo('another-track', 120), false);
  assert.equal(automaticEq.setTempo('track-3', 120), true);
  assert.equal(automaticEq.hasTempo('track-3'), true);
});
