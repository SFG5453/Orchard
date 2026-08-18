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
import { ref } from 'vue';
import {
  DEFAULT_SPONSOR_BLOCK_MODE,
  installSponsorBlockActions,
  normalizeSponsorBlockMode,
  sponsorSkipLabel
} from '../src/app/playback/sponsorBlockActions.js';

function sponsorBlockContext({ mode = 'button', segments = [], seeks = [], duration = 240 } = {}) {
  const ctx = {
    sponsorBlockMode: ref(mode),
    sponsorBlockRequest: 0,
    sponsorBlockState: ref({ trackId: '', status: 'idle', segments: [] }),
    activeTrack: ref({ id: 'track-1' }),
    activeTrackIsLive: ref(false),
    currentTime: ref(0),
    duration: ref(duration),
    isSeeking: ref(false),
    socket: ref({ connected: true }),
    // Mirrors playbackControls.seek, including its refusal to move the playhead
    // before the duration is known and its clamp to the end of the track.
    seek: (time) => {
      if (!ctx.duration.value || ctx.activeTrackIsLive.value) return;

      const target = Math.max(0, Math.min(Number(time) || 0, ctx.duration.value));
      seeks.push(target);
      ctx.currentTime.value = target;
    },
    emitWithReply: async () => ({ segments })
  };

  installSponsorBlockActions(ctx);
  // Stands in for the computed installed by computedState.
  ctx.activeSponsorSegment = {
    get value() {
      return ctx.currentSponsorBlockSegment(ctx.currentTime.value, 0.2);
    }
  };

  return ctx;
}

const tenSecondIntro = [{ id: 'a', category: 'intro', startTime: 0, endTime: 10 }];

test('the mode preference accepts its own values and the boolean it replaced', () => {
  assert.equal(normalizeSponsorBlockMode('auto'), 'auto');
  assert.equal(normalizeSponsorBlockMode('button'), 'button');
  assert.equal(normalizeSponsorBlockMode('off'), 'off');
  assert.equal(normalizeSponsorBlockMode(true), 'button');
  assert.equal(normalizeSponsorBlockMode(false), 'off');
  assert.equal(normalizeSponsorBlockMode(undefined), DEFAULT_SPONSOR_BLOCK_MODE);
  assert.equal(normalizeSponsorBlockMode('nonsense'), DEFAULT_SPONSOR_BLOCK_MODE);
});

test('an intro segment shifts lyric time and round-trips back to playback time', async () => {
  const ctx = sponsorBlockContext({ segments: tenSecondIntro });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.equal(ctx.lyricTimeForPlaybackTime(12), 2);
  assert.equal(ctx.playbackTimeForLyricTime(2), 12);
  // Inside the intro the lyrics have not started yet.
  assert.equal(ctx.lyricTimeForPlaybackTime(4), 0);
});

test('skipping an intro seeks just past its end', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ segments: tenSecondIntro, seeks });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  ctx.currentTime.value = 5;

  assert.ok(ctx.activeSponsorSegment.value);
  ctx.skipSponsorSegment();
  assert.deepEqual(seeks, [10.05]);
});

test('the off mode skips the lookup and leaves lyric timing untouched', async () => {
  const ctx = sponsorBlockContext({ mode: 'off', segments: tenSecondIntro });
  ctx.emitWithReply = async () => assert.fail('segments must not be requested while disabled');

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.deepEqual(ctx.sponsorBlockState.value, { trackId: '', status: 'idle', segments: [] });
  assert.equal(ctx.lyricTimeForPlaybackTime(12), 12);
  assert.equal(ctx.activeSponsorSegment.value, null);
});

test('auto mode skips a segment as soon as its markers arrive', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'auto', segments: tenSecondIntro, seeks });
  ctx.currentTime.value = 3;

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.deepEqual(seeks, [10.05]);
});

// Segments routinely arrive before loadedmetadata, and `seek` refuses to move
// the playhead until the duration is known. A refused skip must stay pending
// rather than being recorded as already done.
test('auto mode retries a skip that was refused because the duration was unknown', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'auto', segments: tenSecondIntro, duration: 0, seeks });

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  assert.deepEqual(seeks, [], 'nothing to seek with yet');
  assert.equal(ctx.autoSkippedSegmentIds.size, 0, 'the segment is still pending');

  // loadedmetadata lands and the watch on duration re-runs the check.
  ctx.duration.value = 240;
  ctx.maybeAutoSkipSponsorSegment();

  assert.deepEqual(seeks, [10.05]);
});

test('auto mode leaves a live stream alone', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'auto', segments: tenSecondIntro, seeks });
  ctx.activeTrackIsLive.value = true;

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.deepEqual(seeks, []);
  assert.equal(ctx.autoSkippedSegmentIds.size, 0);
});

test('auto mode skips each segment only once, so seeking back replays it', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'auto', segments: tenSecondIntro, seeks });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  assert.equal(seeks.length, 1);

  // The listener deliberately goes back into the segment they just skipped.
  ctx.currentTime.value = 2;
  ctx.maybeAutoSkipSponsorSegment();

  assert.equal(seeks.length, 1);
});

test('auto mode holds off while the user is scrubbing', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'auto', segments: tenSecondIntro, seeks });
  ctx.isSeeking.value = true;

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.deepEqual(seeks, []);
});

test('button mode never moves the playhead on its own', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'button', segments: tenSecondIntro, seeks });

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  ctx.currentTime.value = 3;
  ctx.maybeAutoSkipSponsorSegment();

  assert.deepEqual(seeks, []);
});

test('a segment running to the end of the track seeks no further than its end', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({
    mode: 'auto',
    segments: [{ id: 'a', category: 'outro', startTime: 220, endTime: 241.5 }],
    duration: 241.5,
    seeks
  });
  ctx.currentTime.value = 230;

  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.deepEqual(seeks, [241.5]);
});

test('a new track clears the record of what was already auto-skipped', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({ mode: 'auto', segments: tenSecondIntro, seeks });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  assert.equal(seeks.length, 1);

  ctx.activeTrack.value = { id: 'track-2' };
  ctx.currentTime.value = 0;
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);

  assert.equal(seeks.length, 2, 'the same segment id on a new track is skipped again');
});

test('turning the preference off mid-request drops the in-flight reply', async () => {
  const ctx = sponsorBlockContext();
  let release;
  ctx.emitWithReply = () => new Promise((resolve) => {
    release = () => resolve({ segments: tenSecondIntro });
  });

  const inFlight = ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  ctx.clearSponsorBlockSegments();
  release();
  await inFlight;

  assert.deepEqual(ctx.sponsorBlockState.value.segments, []);
});

// Regression for issue #87: the tracks users want skipped are marked
// `music_offtopic` ("Non-Music"), not `intro`.
test('a music_offtopic segment drives the skip button and lyric timing', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({
    segments: [{ id: 'a', category: 'music_offtopic', startTime: 0, endTime: 15.778 }],
    seeks
  });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  ctx.currentTime.value = 5;

  assert.equal(sponsorSkipLabel(ctx.activeSponsorSegment.value), 'Skip non-music');
  ctx.skipSponsorSegment();
  assert.equal(seeks.length, 1);
  assert.ok(Math.abs(seeks[0] - 15.828) < 1e-6);
  assert.ok(Math.abs(ctx.lyricTimeForPlaybackTime(20) - (20 - 15.778)) < 1e-6);
});

test('an outro is skippable but never shifts lyric timing', async () => {
  const seeks = [];
  const ctx = sponsorBlockContext({
    segments: [{ id: 'a', category: 'outro', startTime: 200, endTime: 240 }],
    duration: 300,
    seeks
  });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  ctx.currentTime.value = 210;

  assert.equal(sponsorSkipLabel(ctx.activeSponsorSegment.value), 'Skip outro');
  ctx.skipSponsorSegment();
  assert.deepEqual(seeks, [240.05]);
  // Nothing musical follows an outro, so a lyric at 205s is still at 205s.
  assert.equal(ctx.lyricTimeForPlaybackTime(205), 205);
});

test('segments belonging to a previous track are ignored', async () => {
  const ctx = sponsorBlockContext({ segments: tenSecondIntro });
  await ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
  ctx.activeTrack.value = { id: 'track-2' };

  assert.deepEqual(ctx.sponsorBlockSegmentsForActiveTrack(), []);
  assert.equal(ctx.lyricTimeForPlaybackTime(12), 12);
});
