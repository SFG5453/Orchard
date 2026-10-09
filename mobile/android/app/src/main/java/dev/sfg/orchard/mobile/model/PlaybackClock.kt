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

package dev.sfg.orchard.mobile.model

/**
 * The time-varying part of [PlaybackSnapshot], published on its own so a position tick only
 * reaches the few things that draw time.
 */
data class PlaybackClock(
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val renderedMixPositionMs: Long? = null,
    val playing: Boolean = false,
    /** `elapsedRealtime` when this sample was taken; the anchor for projection. */
    val sampledAtMs: Long = 0,
) {
    /**
     * The clock advanced to [nowMs]. Ticks arrive twice a second, so per-frame readers project
     * between them. Capped so a stalled publisher cannot run the playhead off on its own.
     */
    fun projectedTo(nowMs: Long, durationMs: Long): PlaybackClock {
        if (!playing || sampledAtMs <= 0) return this
        val elapsed = (nowMs - sampledAtMs).coerceIn(0, MAX_PROJECTION_MS)
        if (elapsed == 0L) return this
        val position = (positionMs + elapsed).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        return copy(positionMs = position, renderedMixPositionMs = renderedMixPositionMs?.plus(elapsed))
    }
}

/** Snapshot with every clock field zeroed, so equality tracks only what the clock does not. */
fun PlaybackSnapshot.withoutClock(): PlaybackSnapshot =
    if (positionMs == 0L && bufferedPositionMs == 0L && renderedMixPositionMs == null) this
    else copy(positionMs = 0, bufferedPositionMs = 0, renderedMixPositionMs = null)

fun PlaybackSnapshot.withClock(clock: PlaybackClock): PlaybackSnapshot = copy(
    positionMs = clock.positionMs,
    bufferedPositionMs = clock.bufferedPositionMs,
    renderedMixPositionMs = clock.renderedMixPositionMs,
)

fun PlaybackSnapshot.clock(sampledAtMs: Long): PlaybackClock = PlaybackClock(
    positionMs = positionMs,
    bufferedPositionMs = bufferedPositionMs,
    renderedMixPositionMs = renderedMixPositionMs,
    playing = isPlaying,
    sampledAtMs = sampledAtMs,
)

/** Two ticks' worth: enough to bridge a late publish without drifting far on a stall. */
private const val MAX_PROJECTION_MS = 1_000L
