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

// Categories the skip affordance offers. `music_offtopic` is what SponsorBlock
// calls "Non-Music" and carries nearly every submission on music videos.
export const SKIPPABLE_CATEGORIES = ['music_offtopic', 'intro', 'outro'];

// An outro cannot displace lyric timing -- nothing musical follows it -- so it
// is offered as a skip but left out of the playback/lyric time mapping.
const DISPLACING_CATEGORIES = ['music_offtopic', 'intro'];

const SKIP_LABELS = {
  music_offtopic: 'Skip non-music',
  intro: 'Skip intro',
  outro: 'Skip outro'
};

export function sponsorSkipLabel(segment) {
  return SKIP_LABELS[segment?.category] || 'Skip';
}

// Defaults to the manual button: automatically moving the playhead is the more
// surprising behavior, so it stays opt-in.
export const DEFAULT_SPONSOR_BLOCK_MODE = 'button';

export const SPONSOR_BLOCK_MODE_OPTIONS = [
  { label: 'Off', value: 'off' },
  { label: 'Show button', value: 'button' },
  { label: 'Skip automatically', value: 'auto' }
];

export function normalizeSponsorBlockMode(value) {
  if (SPONSOR_BLOCK_MODE_OPTIONS.some((option) => option.value === value)) return value;
  // Carries over the boolean this preference shipped as before the third state.
  if (typeof value === 'boolean') return value ? 'button' : 'off';

  return DEFAULT_SPONSOR_BLOCK_MODE;
}

// SponsorBlock non-music handling for the active track: the skip affordance plus
// the two-way mapping between playback time and lyric time, so lyrics stay
// aligned when a track opens with talking, silence, or other non-musical audio.
export function installSponsorBlockActions(ctx) {
  ctx.sponsorSkipLabel = sponsorSkipLabel;
  ctx.autoSkippedSegmentIds = new Set();

  ctx.sponsorBlockSegmentsForActiveTrack = function sponsorBlockSegmentsForActiveTrack(categories = SKIPPABLE_CATEGORIES) {
    if (!ctx.activeTrack.value?.id || ctx.sponsorBlockState.value.trackId !== ctx.activeTrack.value.id) return [];

    return ctx.sponsorBlockState.value.segments.filter((segment) => categories.includes(segment.category));
  };

  ctx.currentSponsorBlockSegment = function currentSponsorBlockSegment(time = ctx.currentTime.value, endPadding = 0) {
    return ctx.sponsorBlockSegmentsForActiveTrack().find((segment) => (
      time >= segment.startTime &&
      time < Math.max(segment.startTime, segment.endTime - endPadding)
    )) || null;
  };

  ctx.displacingSegmentsForActiveTrack = function displacingSegmentsForActiveTrack() {
    return ctx.sponsorBlockSegmentsForActiveTrack(DISPLACING_CATEGORIES);
  };

  ctx.nonMusicOffsetAtPlaybackTime = function nonMusicOffsetAtPlaybackTime(time) {
    const playbackTime = Math.max(0, Number(time) || 0);
    let offset = 0;

    for (const segment of ctx.displacingSegmentsForActiveTrack()) {
      if (playbackTime <= segment.startTime) break;

      offset += Math.min(playbackTime, segment.endTime) - segment.startTime;
      if (playbackTime < segment.endTime) break;
    }

    return Math.max(0, offset);
  };

  ctx.lyricTimeForPlaybackTime = function lyricTimeForPlaybackTime(time) {
    const playbackTime = Math.max(0, Number(time) || 0);
    return Math.max(0, playbackTime - ctx.nonMusicOffsetAtPlaybackTime(playbackTime));
  };

  ctx.playbackTimeForLyricTime = function playbackTimeForLyricTime(time) {
    const lyricTime = Math.max(0, Number(time) || 0);
    let offset = 0;

    for (const segment of ctx.displacingSegmentsForActiveTrack()) {
      const adjustedSegmentStart = Math.max(0, segment.startTime - offset);
      if (lyricTime < adjustedSegmentStart) break;

      offset += segment.endTime - segment.startTime;
    }

    return lyricTime + offset;
  };

  ctx.sponsorSegmentSkipTarget = function sponsorSegmentSkipTarget(segment) {
    // A segment that runs to the end of the track would otherwise seek past it.
    const trackEnd = Number(ctx.duration.value) || 0;
    const target = segment.endTime + 0.05;

    return trackEnd > 0 ? Math.min(target, trackEnd) : target;
  };

  ctx.seekPastSponsorSegment = function seekPastSponsorSegment(segment) {
    if (!segment) return false;

    const target = ctx.sponsorSegmentSkipTarget(segment);
    ctx.seek(target);

    // `seek` refuses outright while the duration is still unknown, and segments
    // routinely arrive before loadedmetadata. The playhead failing to move is
    // how we find out the skip did not happen.
    return ctx.currentTime.value >= target - 0.001;
  };

  ctx.skipSponsorSegment = function skipSponsorSegment() {
    ctx.seekPastSponsorSegment(ctx.activeSponsorSegment.value);
  };

  // Auto-skip fires once per segment. Seeking back into a segment you already
  // skipped is a deliberate act, so it is left alone the second time.
  ctx.maybeAutoSkipSponsorSegment = function maybeAutoSkipSponsorSegment() {
    if (ctx.sponsorBlockMode.value !== 'auto' || ctx.isSeeking.value) return;
    // Live streams cannot be seeked, and a guest follows the host's playhead
    // rather than steering it -- retrying either would just spin.
    if (ctx.activeTrackIsLive?.value) return;
    if (ctx.listeningParty?.value?.status === 'connected' && !ctx.listeningPartyIsHost?.value) return;

    const segment = ctx.activeSponsorSegment.value;
    if (!segment || ctx.autoSkippedSegmentIds.has(segment.id)) return;

    // Only remember the segment once the playhead has really moved, or a skip
    // that was refused would be recorded as done and never retried.
    if (ctx.seekPastSponsorSegment(segment)) ctx.autoSkippedSegmentIds.add(segment.id);
  };

  ctx.clearSponsorBlockSegments = function clearSponsorBlockSegments() {
    ctx.sponsorBlockRequest += 1;
    ctx.autoSkippedSegmentIds.clear();
    ctx.sponsorBlockState.value = { trackId: '', status: 'idle', segments: [] };
  };

  ctx.loadSponsorBlockSegments = async function loadSponsorBlockSegments(track) {
    if (ctx.sponsorBlockMode.value === 'off') {
      ctx.clearSponsorBlockSegments();
      return;
    }

    ctx.autoSkippedSegmentIds.clear();

    const requestId = ++ctx.sponsorBlockRequest;

    if (!track?.id || !ctx.socket.value?.connected) {
      ctx.sponsorBlockState.value = { trackId: '', status: 'idle', segments: [] };
      return;
    }

    ctx.sponsorBlockState.value = {
      trackId: track.id,
      status: 'loading',
      segments: []
    };

    try {
      const data = await ctx.emitWithReply('sponsorblock:segments', { videoId: track.id });
      if (requestId !== ctx.sponsorBlockRequest || ctx.activeTrack.value?.id !== track.id) return;

      ctx.sponsorBlockState.value = {
        trackId: track.id,
        status: 'ready',
        segments: data.segments || []
      };
      // Segments usually land after playback has already started, and the most
      // common one begins at 0 -- so the playhead is normally sitting inside it.
      ctx.maybeAutoSkipSponsorSegment();
    } catch {
      if (requestId === ctx.sponsorBlockRequest) {
        ctx.sponsorBlockState.value = {
          trackId: track.id,
          status: 'unavailable',
          segments: []
        };
      }
    }
  };
}
