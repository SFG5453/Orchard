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

package dev.sfg.orchard.mobile.catalog

import android.util.Log
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Swaps a music-video track for its album-audio counterpart.
 *
 * Some album pages only ever link the official video: browsing SZA's SOS returns the Kill Bill
 * video for every client YouTube offers, so there is no audio id to read off the page. The audio
 * version does exist, it is just only reachable through search, which is what this does.
 */
class AudioVersionResolver(private val client: InnerTubeClient) {
    private val resolved = mutableMapOf<String, Track>()

    /** Returns the album-audio equivalent, or [track] unchanged when there is nothing better. */
    suspend fun audioVersion(track: Track): Track = withContext(Dispatchers.IO) {
        if (!track.isVideoUpload || track.title.isBlank()) return@withContext track
        synchronized(resolved) { resolved[track.id] }?.let { return@withContext it }

        val match = runCatching { findAudioMatch(track) }
            .onFailure { Log.w(TAG, "Audio version lookup failed for ${track.title}", it) }
            .getOrNull()
            ?: track

        // Cache misses too: a track with no audio version should not be searched again.
        synchronized(resolved) { resolved[track.id] = match }
        match
    }

    private fun findAudioMatch(track: Track): Track? {
        val payload = client.searchSongs("${track.title} ${track.artist}".trim())
        val candidates = CatalogParser.search(payload).tracks.filter { it.isAudioOnly }
        if (candidates.isEmpty()) return null

        return candidates
            .map { it to it.matchScore(track) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            // Keep the original metadata: the album page knows the album, artwork and track order,
            // and search results routinely disagree on all three.
            ?.let { track.copy(id = it.id, musicVideoType = it.musicVideoType) }
    }

    /**
     * Titles must match once video-only decorations are stripped, otherwise a search for a track
     * can happily return a remix, a live take, or the next song on the album.
     */
    private fun Track.matchScore(target: Track): Int {
        if (normalizedTitle() != target.normalizedTitle()) return 0
        var score = 1
        if (artist.normalized() == target.artist.normalized()) score += 4
        else if (artist.normalized().contains(target.artist.normalized())) score += 2
        // Album audio runs close to the album listing; videos carry intros and outros.
        if (target.durationMs > 0 && durationMs > 0) {
            val drift = abs(durationMs - target.durationMs)
            if (drift <= 3_000) score += 4 else if (drift <= 15_000) score += 1 else score -= 2
        }
        return score.coerceAtLeast(0)
    }

    private fun Track.normalizedTitle(): String = title
        .replace(Regex("\\((?:official\\s+)?(?:music\\s+)?(?:video|audio|visualizer)\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[[^]]*]"), "")
        .normalized()

    private fun String.normalized(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private companion object {
        const val TAG = "AudioVersionResolver"
    }
}
