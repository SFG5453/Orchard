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

package dev.sfg.orchard.mobile.playback

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Answers a failed load on the session player: refresh the stream, fall back, or give up. */
@UnstableApi
internal class PlaybackRecovery(
    private val resolver: YouTubeStreamResolver,
    private val streams: PlayerStreams,
    private val postWarning: (String) -> Unit,
) {
    /** Stream refreshes already spent per media id, cleared when the queue moves on. */
    private val retried = mutableMapOf<String, Int>()

    fun onMediaItemTransition() = retried.clear()

    fun recover(player: Player, error: PlaybackException) {
        val failedItem = player.currentMediaItem
        val mediaId = failedItem?.mediaId.orEmpty()
        val failedUri = failedItem?.localConfiguration?.uri
        if (mediaId.isBlank() || failedItem == null || failedUri == null) {
            Log.e(TAG, "Playback failed without a recoverable media item", error)
            return
        }
        val sourceId = MediaItemMapper.sourceId(failedUri)
        if (MediaItemMapper.isVideoUri(failedUri)) {
            val audioDuration = MediaItemMapper.toTrack(failedItem).durationMs
            val position = player.currentPosition.coerceAtLeast(0)
                .let { if (audioDuration > 0) it.coerceAtMost(audioDuration - 1) else it }
            resolver.resetForRetry(sourceId)
            streams.forget(failedUri.toString())
            Log.w(TAG, "Video playback failed; continuing with album audio", error)
            replace(player, MediaItemMapper.asAudio(failedItem), position, play = player.playWhenReady)
            postWarning("Video unavailable. Continuing with audio.")
            return
        }
        val resolvedStream = streams.forget(failedUri.toString())
        val responseCode = playbackHttpResponseCode(error)
        val rejectedClient = resolvedStream != null && responseCode != null &&
            resolver.reject(sourceId, resolvedStream, responseCode)
        if (!rejectedClient) resolver.resetForRetry(sourceId)
        if (MediaItemMapper.requiresAuthenticatedDirect(failedUri)) {
            Log.w(TAG, "Direct authenticated stream failed; falling back to HLS", error)
            replace(player, MediaItemMapper.asAuthenticatedHlsFallback(failedItem),
                player.currentPosition.coerceAtLeast(0), play = true)
            return
        }
        if (MediaItemMapper.requiresAuthenticatedHls(failedUri)) {
            Log.e(TAG, "Playback failed after authenticated HLS fallback", error)
            return
        }
        // Resolution failed outright. Retrying on its own only extends the spinner; an
        // explicit Play tap makes a fresh attempt.
        if (resolvedStream == null) {
            Log.e(TAG, "Playback stopped after stream resolution exhausted its fallbacks", error)
            return
        }
        if (isUnrecoverable(error)) {
            Log.e(TAG, "Playback error is not recoverable by refreshing the stream", error)
            return
        }
        // A rejected client earns its own attempt: [YouTubeStreamResolver.reject] blacklists the
        // profile that minted the refused URL, so the retry asks a client from another family.
        val attempts = retried.getOrDefault(mediaId, 0)
        val limit = if (rejectedClient) MAX_CLIENT_ROTATION_RETRIES else 1
        if (attempts >= limit) {
            Log.e(TAG, "Playback failed after $attempts stream refreshes", error)
            return
        }
        retried[mediaId] = attempts + 1
        Log.w(TAG, "Refreshing the failed stream (attempt ${attempts + 1} of $limit)", error)
        player.prepare()
        player.play()
    }

    private fun replace(player: Player, item: androidx.media3.common.MediaItem, position: Long, play: Boolean) {
        val index = player.currentMediaItemIndex
        player.replaceMediaItem(index, item)
        player.seekTo(index, position)
        player.prepare()
        if (play) player.play()
    }

    private fun isUnrecoverable(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            UNRECOVERABLE.any { message.contains(it, ignoreCase = true) }
        }

    private companion object {
        const val TAG = "OrchardPlayback"

        /**
         * How many times a refused CDN URL may be answered by resolving again. Each attempt is
         * spent on a client the resolver has not tried for this track, so the ceiling is really
         * "how many client families are worth walking while the listener waits".
         */
        const val MAX_CLIENT_ROTATION_RETRIES = 3

        val UNRECOVERABLE = listOf(
            "inappropriate for some users",
            "confirm your age",
            "private",
            "not available in your country",
            "removed",
        )
    }
}
