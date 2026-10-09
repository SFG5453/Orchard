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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import dev.sfg.orchard.mobile.model.PlaybackClock
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.model.withClock
import dev.sfg.orchard.mobile.model.withoutClock
import kotlinx.coroutines.delay

/** Where the visible track's playhead is, in that track's own timeline. */
@Immutable
data class Playhead(val positionMs: Long, val bufferedMs: Long, val durationMs: Long)

/**
 * Presented playback time for draw-phase and effect readers.
 *
 * Read it from draw, layout, effects or `derivedStateOf`, never straight from composition:
 * a composition read subscribes the caller to every tick, which is the cost this exists to remove.
 */
@Stable
class PlayerClock internal constructor(
    private val snapshot: State<PlaybackSnapshot>,
    private val clock: State<PlaybackClock>,
    private val marker: State<TransitionMarker?>,
) {
    val playing: Boolean get() = clock.value.playing

    /** The last published sample. Moves twice a second. */
    fun reported(): Playhead = present(clock.value)

    /** The sample projected to [nowMs], for anything that redraws per frame. */
    fun projected(nowMs: Long = SystemClock.elapsedRealtime()): Playhead =
        present(clock.value.projectedTo(nowMs, snapshot.value.durationMs))

    private fun present(sample: PlaybackClock): Playhead {
        val state = snapshot.value
        val mix = marker.value
        // Fast path: outside a planned transition the presented track is the playing one.
        if (mix == null) return Playhead(sample.positionMs, sample.bufferedPositionMs, state.durationMs)
        val shown = transitionPresentation(state.withClock(sample), mix).playback
        return Playhead(shown.positionMs, shown.bufferedPositionMs, shown.durationMs)
    }
}

/** Never null in the app; the idle default keeps previews and tests composable. */
val LocalPlayerClock = staticCompositionLocalOf {
    PlayerClock(
        snapshot = derivedStateOf { PlaybackSnapshot() },
        clock = derivedStateOf { PlaybackClock() },
        marker = derivedStateOf { null },
    )
}

/** Player identity for this instant, split from the clock that drives it. */
@Stable
class PlayerPresentation internal constructor(
    identity: State<TransitionPresentation>,
    mixProgress: State<Float>,
    val clock: PlayerClock,
) {
    /** Clock fields are zeroed; read time through [clock]. */
    val playback: PlaybackSnapshot by derivedStateOf { identity.value.playback }
    val incomingDominant: Boolean by derivedStateOf { identity.value.incomingDominant }

    /** Raw overlap progress. Constant 0 outside a mix, so reading it is free most of the time. */
    val mixProgress: Float by mixProgress
}

/**
 * Projects [playback] through [marker] without subscribing the caller to the clock.
 *
 * Identity only changes at the audible handoff, so the derived states below recompute every
 * tick but notify readers only when the answer actually differs.
 */
@Composable
fun rememberPlayerPresentation(
    playback: PlaybackSnapshot,
    clock: State<PlaybackClock>,
    marker: TransitionMarker?,
): PlayerPresentation {
    val latestPlayback = rememberUpdatedState(playback)
    val latestMarker = rememberUpdatedState(marker)
    return remember(clock) {
        val identity = derivedStateOf {
            val presented = transitionPresentation(latestPlayback.value.withClock(clock.value), latestMarker.value)
            TransitionPresentation(presented.playback.withoutClock(), 0f, presented.incomingDominant)
        }
        val progress = derivedStateOf {
            transitionProgress(latestPlayback.value.withClock(clock.value), latestMarker.value)
        }
        PlayerPresentation(identity, progress, PlayerClock(latestPlayback, clock, latestMarker))
    }
}

/**
 * Invalidation source for playhead drawing. Ticks no faster than [intervalMs] while [active], so
 * a bar that moves one pixel every 200 ms is not redrawn sixty times a second.
 */
@Composable
fun rememberPlayheadTick(active: Boolean, intervalMs: Long): State<Long> {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active, intervalMs) {
        if (!active) return@LaunchedEffect
        while (true) {
            withFrameMillis { tick.longValue = it }
            if (intervalMs > FRAME_MS) delay(intervalMs - FRAME_MS)
        }
    }
    return tick
}

/** Milliseconds of playback per pixel of bar: the coarsest step that still looks continuous. */
fun playheadStepMs(durationMs: Long, widthPx: Float): Long =
    if (widthPx <= 0f || durationMs <= 0) 250L else (durationMs / widthPx).toLong().coerceIn(FRAME_MS, 1_000L)

private const val FRAME_MS = 16L
