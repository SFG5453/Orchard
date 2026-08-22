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
 * Resolves the catalog's album-audio id before playback.
 *
 * Some album pages only ever link the official video: browsing SZA's SOS returns the Kill Bill
 * video for every client YouTube offers, so there is no audio id to read off the page. The audio
 * version does exist, it is just only reachable through search. Explicit rows are checked too,
 * even when the catalog calls them album audio or omits the type: those are the rows where a clean
 * playback id can otherwise sit behind an explicit badge.
 */
class AudioVersionResolver(private val client: InnerTubeClient) {
    private data class LookupKey(val id: String, val explicit: Boolean)
    private data class ResolvedVersion(val id: String, val musicVideoType: String)

    private val resolved = mutableMapOf<LookupKey, ResolvedVersion>()

    /** Returns the album-audio equivalent, or [track] unchanged when there is nothing better. */
    suspend fun audioVersion(track: Track): Track = withContext(Dispatchers.IO) {
        if (!track.needsAudioVersionLookup() || track.title.isBlank()) return@withContext track
        val key = LookupKey(track.id, track.explicit)
        synchronized(resolved) { resolved[key] }?.let { return@withContext track.withVersion(it) }

        val lookup = runCatching { findAudioMatch(track) }
        lookup.exceptionOrNull()?.let {
            // A network failure is not a catalog miss. Do not cache it, so a later attempt can
            // repair the track when connectivity returns.
            Log.w(TAG, "Audio version lookup failed for ${track.title}", it)
            return@withContext track
        }
        val version = lookup.getOrNull() ?: ResolvedVersion(track.id, track.musicVideoType)

        // Cache misses too: a track with no audio version should not be searched again.
        synchronized(resolved) {
            resolved[key] = version
            // The queue receives the resolved id. Remember it as confirmed too, so observing the
            // now-current item does not repeat the same search while retaining its own metadata.
            resolved.putIfAbsent(LookupKey(version.id, track.explicit), version)
        }
        track.withVersion(version)
    }

    private fun findAudioMatch(track: Track): ResolvedVersion? {
        val payload = client.searchSongs("${track.title} ${track.artist}".trim())
        return bestAudioMatch(track, CatalogParser.search(payload).tracks)
            ?.let { ResolvedVersion(it.id, it.musicVideoType) }
    }

    // Keep the original metadata: the album page knows the album, artwork and track order, and
    // search results routinely disagree on all three.
    private fun Track.withVersion(version: ResolvedVersion): Track =
        copy(id = version.id, musicVideoType = version.musicVideoType)

    private companion object {
        const val TAG = "AudioVersionResolver"
    }
}

/**
 * Chooses an album-audio result without crossing the clean/explicit boundary.
 *
 * The returned id is later combined with the original track's metadata. If content ratings are
 * ignored here, a clean result can therefore play while the UI continues to show the original
 * explicit badge (or vice versa).
 */
internal fun bestAudioMatch(target: Track, candidates: List<Track>): Track? = candidates
    .asSequence()
    .filter { it.isAudioOnly && it.explicit == target.explicit }
    .toList()
    .let { matches -> matches.firstOrNull { it.id == target.id } ?: matches.bestMatch(target) }

/** Explicit rows need verification even when their renderer claims ATV or carries no type. */
internal fun Track.needsAudioVersionLookup(): Boolean = explicit || isVideoUpload

private fun List<Track>.bestMatch(target: Track): Track? = asSequence()
    .map { it to it.matchScore(target) }
    .filter { it.second > 0 }
    .maxByOrNull { it.second }
    ?.first

/**
 * Titles must match once video-only decorations are stripped, otherwise a search for a track can
 * happily return a remix, a live take, or the next song on the album.
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
