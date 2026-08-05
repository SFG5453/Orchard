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

import { supabaseClient } from './supabaseClient.js';
import { isValidLocalAnalysis } from '../../shared/audioAnalysis.js';

export const CLOUD_SYNC_DISCLAIMER =
  'Audio analysis metadata (BPM, musical key, downbeats, cue points) is shared publicly with the Orchard Cloud cache by Track Video ID. No personal listening history, user playlists, or identifying info is included.';

/**
 * Uploads a locally analyzed track's features to Supabase if authenticated.
 * @param {string} videoId
 * @param {object} analysis
 */
export async function syncTrackAnalysisToCloud(videoId, analysis) {
  if (!videoId || !analysis || !supabaseClient.isAuthenticated()) return false;
  if (!isValidLocalAnalysis(analysis) && (!analysis.bpm || analysis.bpm <= 0)) return false;

  const record = {
    videoId,
    duration: analysis.duration,
    bpm: analysis.bpm,
    musical_key: analysis.key || analysis.musicalKey || '',
    key_confidence: analysis.keyConfidence || 0.0,
    beat_confidence: analysis.beatConfidence || analysis.tempoConfidence || 0.0,
    analysis_version: analysis.analysisVersion || 9,
    analysis_data: analysis
  };

  return await supabaseClient.upsertTrackAnalysis([record]);
}

/**
 * Batch-fetches cloud analysis records for a list of video IDs.
 * @param {string[]} videoIds
 * @returns {Promise<Map<string, object>>} Map of videoId -> analysis payload
 */
export async function fetchBatchCloudAnalysis(videoIds) {
  const result = new Map();
  if (!Array.isArray(videoIds) || videoIds.length === 0) return result;

  try {
    const records = await supabaseClient.fetchTrackAnalysis(videoIds);
    for (const row of records) {
      if (row.video_id && row.bpm) {
        result.set(row.video_id, {
          bpm: row.bpm,
          key: row.musical_key,
          keyConfidence: row.key_confidence,
          beatConfidence: row.beat_confidence,
          duration: row.duration,
          analysisVersion: row.analysis_version,
          ...(row.analysis_data || {}),
          analysisSource: 'cloud-cache'
        });
      }
    }
  } catch (err) {
    console.warn('Error querying cloud analysis sync:', err);
  }

  return result;
}
