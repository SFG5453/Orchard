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

package dev.sfg.orchard.mobile.ui.components

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.TransitionMarker
import kotlin.math.abs

/** Playback values the player chrome should present at this instant of a transition. */
data class TransitionPresentation(
    val playback: PlaybackSnapshot,
    val progress: Float,
    val incomingDominant: Boolean,
)

/**
 * How far into the active overlap playback is.
 *
 * A rendered Smart Crossfade becomes a short mix item whose clock starts at zero, while a live
 * crossfade remains on the outgoing track's original timeline. Supporting both clocks here keeps
 * every transition affordance synchronized to the audio that is actually playing.
 */
fun transitionProgress(playback: PlaybackSnapshot, marker: TransitionMarker?): Float {
    val track = playback.currentTrack ?: return 0f
    if (marker == null || marker.trackId.isBlank()) return 0f

    val liveWindowMs = marker.endMs - marker.startMs
    if (liveWindowMs <= 0) return 0f

    val raw =
        when (track.id) {
            marker.trackId -> {
                val renderedWindowMs = marker.renderedDurationMs
                val onRenderedTimeline =
                    renderedWindowMs > 0 &&
                        abs(playback.durationMs - renderedWindowMs) <= RENDERED_DURATION_TOLERANCE_MS
                if (onRenderedTimeline) {
                    playback.positionMs.toDouble() / renderedWindowMs.toDouble()
                } else {
                    (playback.positionMs - marker.startMs).toDouble() / liveWindowMs.toDouble()
                }
            }
            marker.incomingTrackId -> {
                val rate = marker.incomingPlaybackRate.coerceAtLeast(0.01)
                val elapsedWallMs = (playback.positionMs - marker.incomingCueMs) / rate
                elapsedWallMs / liveWindowMs.toDouble()
            }
            else -> return 0f
        }

    // A stale marker must not light up a later part of the incoming track indefinitely.
    if (raw < 0.0 || raw > 1.0) return 0f
    return raw.toFloat()
}

/**
 * Changes the player's visible identity only once the incoming track owns the mix.
 *
 * The service remains authoritative for transport. This projection advances artwork, metadata,
 * queue identity, and progress together so no part of the player describes a different song.
 */
fun transitionPresentation(
    playback: PlaybackSnapshot,
    marker: TransitionMarker?,
): TransitionPresentation {
    val progress = transitionProgress(playback, marker)
    if (marker == null || progress <= marker.audibleHandoffProgress) {
        return TransitionPresentation(playback, progress, incomingDominant = false)
    }

    val incomingIndex =
        playback.queue.indices.firstOrNull { index ->
            index != playback.currentIndex && playback.queue[index].id == marker.incomingTrackId
        } ?: -1
    if (incomingIndex < 0) {
        return TransitionPresentation(playback, progress, incomingDominant = false)
    }
    val incoming = playback.queue[incomingIndex]
    if (playback.currentTrack?.id == incoming.id) {
        return TransitionPresentation(playback, progress, incomingDominant = true)
    }

    val transitionWindowMs = (marker.endMs - marker.startMs).coerceAtLeast(1)
    val incomingPosition =
        (marker.incomingCueMs +
            progress * transitionWindowMs * marker.incomingPlaybackRate)
            .toLong()
            .coerceAtLeast(0)
            .let { position ->
                if (incoming.durationMs > 0) position.coerceAtMost(incoming.durationMs) else position
            }

    return TransitionPresentation(
        playback =
            playback.copy(
                currentTrack = incoming,
                currentIndex = incomingIndex,
                positionMs = incomingPosition,
                durationMs = incoming.durationMs.coerceAtLeast(0),
                bufferedPositionMs = incomingPosition,
            ),
        progress = progress,
        incomingDominant = true,
    )
}

private const val RENDERED_DURATION_TOLERANCE_MS = 250L
