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

import { ref } from 'vue';
import { ANALYSIS_PRIORITIES } from '../../audio/crossfade/smartCrossfadeAnalysis.js';
import { syncTrackAnalysisToCloud, fetchBatchCloudAnalysis } from '../../services/cloudAnalysisSync.js';

export function createPlaylistAnalysisRunner(ctx) {
  const isAnalyzing = ref(false);
  const progress = ref(0);
  const totalTracks = ref(0);
  const completedTracks = ref(0);
  const currentStatus = ref('');
  let abortController = null;

  function reset() {
    isAnalyzing.value = false;
    progress.value = 0;
    totalTracks.value = 0;
    completedTracks.value = 0;
    currentStatus.value = '';
    abortController = null;
  }

  function cancel() {
    if (abortController) {
      abortController.abort();
    }
    reset();
  }

  /**
   * Analyzes an entire playlist / album of tracks in the background,
   * checking cloud cache first, analyzing missing tracks locally,
   * and uploading newly analyzed tracks to Supabase.
   * If any song fails, it skips that song without failing the whole process.
   *
   * @param {Array<object>} tracks
   * @returns {Promise<{ success: number, failed: number, cached: number }>}
   */
  async function analyzePlaylist(tracks) {
    if (!Array.isArray(tracks) || tracks.length === 0) {
      return { success: 0, failed: 0, cached: 0 };
    }

    if (isAnalyzing.value) {
      cancel();
    }

    const uniqueTracks = Array.from(
      new Map(tracks.filter(t => t?.id).map(t => [t.id, t])).values()
    );

    if (uniqueTracks.length === 0) {
      return { success: 0, failed: 0, cached: 0 };
    }

    isAnalyzing.value = true;
    totalTracks.value = uniqueTracks.length;
    completedTracks.value = 0;
    progress.value = 0;
    currentStatus.value = 'Checking cloud analysis cache…';

    abortController = new AbortController();
    const signal = abortController.signal;

    const stats = { success: 0, failed: 0, cached: 0 };

    try {
      // 1. Check Cloud Cache for existing analysis
      const videoIds = uniqueTracks.map(t => t.id);
      const cloudMetadata = await fetchBatchCloudAnalysis(videoIds);

      const missingTracks = [];
      for (const track of uniqueTracks) {
        if (cloudMetadata.has(track.id)) {
          stats.cached++;
          completedTracks.value++;
        } else {
          missingTracks.push(track);
        }
      }

      progress.value = completedTracks.value / totalTracks.value;

      if (missingTracks.length === 0) {
        currentStatus.value = `All ${totalTracks.value} tracks are already analyzed in the cloud.`;
        isAnalyzing.value = false;
        return stats;
      }

      const analyze = ctx.smartCrossfadeAnalyzer?.analyze;
      const resolvePlayable = ctx.resolvePlayableTrack;

      if (typeof analyze !== 'function' || typeof resolvePlayable !== 'function') {
        currentStatus.value = 'Local audio analyzer not available.';
        isAnalyzing.value = false;
        return stats;
      }

      // 2. Sequentially analyze missing tracks
      for (let i = 0; i < missingTracks.length; i++) {
        if (signal.aborted) break;

        const track = missingTracks[i];
        currentStatus.value = `Analyzing (${completedTracks.value + 1}/${totalTracks.value}): ${track.title || track.name || track.id}`;

        try {
          // Resolve stream
          const resolved = await resolvePlayable.call(ctx, track).catch(() => null);
          const streamUrl = resolved?.streamUrl || '';

          if (!streamUrl) {
            stats.failed++;
            completedTracks.value++;
            progress.value = completedTracks.value / totalTracks.value;
            continue;
          }

          const durationSeconds = Number(track.durationSeconds || track.duration) || 0;
          const analysis = await analyze.call(
            ctx.smartCrossfadeAnalyzer,
            track.id,
            streamUrl,
            { duration: durationSeconds, priority: ANALYSIS_PRIORITIES.background }
          );

          if (analysis && (analysis.bpm > 0 || analysis.analyzedBpm > 0)) {
            stats.success++;
            // Push to cloud
            void syncTrackAnalysisToCloud(track.id, analysis);
          } else {
            stats.failed++;
          }
        } catch (err) {
          // Failure on single song must not stop the batch
          stats.failed++;
          console.warn(`Analysis skipped for song ${track.id}:`, err);
        }

        completedTracks.value++;
        progress.value = completedTracks.value / totalTracks.value;

        // Brief yield to keep UI responsive
        await new Promise(resolve => setTimeout(resolve, 50));
      }

      currentStatus.value = `Done. ${stats.success + stats.cached} analyzed (${stats.cached} from cloud, ${stats.success} newly processed).`;
    } catch (err) {
      console.error('Playlist analysis runner error:', err);
      currentStatus.value = 'Analysis ended unexpectedly.';
    } finally {
      isAnalyzing.value = false;
    }

    return stats;
  }

  return {
    isAnalyzing,
    progress,
    totalTracks,
    completedTracks,
    currentStatus,
    analyzePlaylist,
    cancel
  };
}
