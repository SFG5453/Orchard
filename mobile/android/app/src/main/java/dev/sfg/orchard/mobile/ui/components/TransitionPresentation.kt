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

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Live crossfades remain on the outgoing track's source timeline. The legacy rendered clock is
 * still understood so playback restored across an app upgrade cannot corrupt presentation.
 */
fun transitionProgress(playback: PlaybackSnapshot, marker: TransitionMarker?): Float {
    val track = playback.currentTrack ?: return 0f
    if (marker == null || marker.trackId.isBlank()) return 0f

    // During a live overlap the transport still reports the outgoing deck. Once it reports the
    // incoming track, either the fade is over or the listener skipped there without one.
    if (track.id != marker.trackId) return 0f

    val liveWindowMs = marker.endMs - marker.startMs
    if (liveWindowMs <= 0) return 0f

    val renderedWindowMs = marker.renderedDurationMs
    val onRenderedTimeline =
        renderedWindowMs > 0 &&
            abs(playback.durationMs - renderedWindowMs) <= RENDERED_DURATION_TOLERANCE_MS
    val raw = if (playback.renderedMixPositionMs != null || onRenderedTimeline) {
        (playback.renderedMixPositionMs ?: playback.positionMs).toDouble() / renderedWindowMs.coerceAtLeast(1).toDouble()
    } else {
        (playback.positionMs - marker.startMs).toDouble() / liveWindowMs.toDouble()
    }

    // Decoder timestamps can overshoot the rounded WAV duration by a millisecond. Keep the
    // incoming identity at the endpoint until transport advances to the remainder item.
    if (playback.renderedMixPositionMs != null) {
        return raw.coerceIn(0.0, 1.0).toFloat()
    }
    // A stale marker must not light up a later part of the outgoing track indefinitely.
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

    val transitionWindowMs = (marker.renderedDurationMs.takeIf { it > 0 }
        ?: (marker.endMs - marker.startMs)).coerceAtLeast(1)
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

/**
 * The service clears its marker at handoff in process, while the session's player swap reaches
 * this controller a beat later. Holding the cleared marker while the snapshot still names the
 * outgoing track keeps the incoming song on screen through that gap.
 */
@Composable
fun rememberHandoffMarker(marker: TransitionMarker?, currentTrackId: String?): TransitionMarker? {
    val hold = remember { HandoffHold() }
    return hold.resolve(marker, currentTrackId, SystemClock.elapsedRealtime())
}

/** Plain fields: a cache read and written by composition, never a source of recomposition. */
private class HandoffHold {
    private var last: TransitionMarker? = null
    private var clearedAt = 0L

    fun resolve(marker: TransitionMarker?, currentTrackId: String?, nowMs: Long): TransitionMarker? {
        if (marker != null) {
            last = marker
            clearedAt = 0L
            return marker
        }
        val previous = last ?: return null
        if (clearedAt == 0L) clearedAt = nowMs
        // A new track, or long enough that this was an abort and no swap is coming.
        if (previous.trackId != currentTrackId || nowMs - clearedAt > HANDOFF_HOLD_MS) {
            last = null
            return null
        }
        return previous
    }
}

private const val RENDERED_DURATION_TOLERANCE_MS = 250L

/** Far longer than a controller round trip; short enough that an aborted mix does not linger. */
private const val HANDOFF_HOLD_MS = 2_000L
