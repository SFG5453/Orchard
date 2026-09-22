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
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 */

package dev.sfg.orchard.mobile.catalog

import android.util.Log
import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_OMV
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Finds the music-video companion of an album-audio queue item without changing its identity. */
class VideoVersionResolver(private val client: InnerTubeClient) {
    private data class LookupKey(val id: String, val explicit: Boolean)

    private val resolved = mutableMapOf<LookupKey, String?>()

    suspend fun videoId(track: Track): String? = withContext(Dispatchers.IO) {
        if (track.isQobuz || track.id.isBlank() || track.title.isBlank()) return@withContext null
        track.musicVideoId.takeIf(String::isNotBlank)?.let { return@withContext it }
        if (track.isVideoUpload) return@withContext track.id

        val key = LookupKey(track.id, track.explicit)
        synchronized(resolved) {
            if (resolved.containsKey(key)) return@withContext resolved[key]
        }
        val result = runCatching {
            val payload = client.searchVideos("${track.title} ${track.artist}".trim())
            bestVideoMatch(track, CatalogParser.search(payload).tracks)?.id
        }
        result.exceptionOrNull()?.let {
            // Do not cache network failures; opening the player later should get another chance.
            Log.w(TAG, "Music video lookup failed for ${track.title}", it)
            return@withContext null
        }
        result.getOrNull().also { videoId -> synchronized(resolved) { resolved[key] = videoId } }
    }

    private companion object {
        const val TAG = "VideoVersionResolver"
    }
}

/** Chooses the same recording without crossing the clean/explicit boundary. */
internal fun bestVideoMatch(target: Track, candidates: List<Track>): Track? = candidates
    .asSequence()
    .filter { it.isVideoUpload && it.explicit == target.explicit }
    .map { it to it.videoMatchScore(target) }
    .filter { it.second > 0 }
    .maxWithOrNull(compareBy<Pair<Track, Int>> { it.second }
        .thenBy { if (it.first.musicVideoType == MUSIC_VIDEO_TYPE_OMV) 1 else 0 })
    ?.first

private fun Track.videoMatchScore(target: Track): Int {
    if (videoNormalizedTitle() != target.videoNormalizedTitle()) return 0
    var score = 2
    val artistName = artist.videoNormalizedText()
    val targetArtist = target.artist.videoNormalizedText()
    if (artistName == targetArtist) score += 5
    else if (artistName.contains(targetArtist) || targetArtist.contains(artistName)) score += 2
    if (musicVideoType == MUSIC_VIDEO_TYPE_OMV) score += 2
    if (target.durationMs > 0 && durationMs > 0) {
        val drift = abs(durationMs - target.durationMs)
        // Videos commonly add an intro/outro, but a radically different runtime is another song.
        if (drift > maxOf(45_000L, target.durationMs / 3)) return 0
        if (drift <= 8_000L) score += 3 else score += 1
    }
    return score
}

private fun Track.videoNormalizedTitle(): String = title
    .replace(
        Regex(
            """\s*[\[(][^)\]]*(?:official|music|video|audio|visualizer|lyrics?|4k|hd)[^)\]]*[)\]]\s*""",
            RegexOption.IGNORE_CASE,
        ),
        " ",
    )
    .videoNormalizedText()

private fun String.videoNormalizedText(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
