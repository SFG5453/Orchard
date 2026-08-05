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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.TransitionMarker

/**
 * The word Orchard shows while two tracks are overlapping.
 *
 * Shifting is the orchard operation this actually is: two separate stems joined at a chosen point
 * so they carry on as one plant. It also keeps the readout in Orchard's own vocabulary rather than
 * borrowing anyone else's.
 */
const val TRANSITION_LABEL = "Shifting"

/** The hues the glow cycles through. Doubled ends so a mirrored tile joins seamlessly. */
private val RainbowStops =
        listOf(
                Color(0xFFFF3B5C),
                Color(0xFFFF9E2C),
                Color(0xFFFFE44D),
                Color(0xFF49E06B),
                Color(0xFF35D6E8),
                Color(0xFF4C7BFF),
                Color(0xFFB44CFF),
                Color(0xFFFF3B5C),
        )

/**
 * How far into the planned transition playback currently is, 0f before it starts and 1f at the
 * handoff. Zero whenever the marker does not describe the playing track, so callers can drive every
 * transition affordance off this one number.
 */
fun transitionProgress(playback: PlaybackSnapshot, marker: TransitionMarker?): Float {
    val track = playback.currentTrack ?: return 0f
    if (marker == null || marker.trackId.isBlank() || marker.trackId != track.id) return 0f
    val start = marker.startMs
    val end = marker.endMs
    if (start <= 0 || end <= start) return 0f
    val position = playback.positionMs
    if (position < start) return 0f
    return ((position - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

/**
 * Eases the raw progress so the glow arrives and leaves smoothly instead of snapping on at the
 * transition boundary, and holds at full strength through the middle of the mix.
 */
@Composable
fun rememberTransitionGlow(progress: Float): Float {
    val target =
            when {
                progress <= 0f -> 0f
                progress < 0.25f -> progress / 0.25f
                else -> 1f
            }
    val eased by
            animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(420),
                    label = "TransitionGlow",
            )
    return eased
}

/**
 * A rainbow that slides sideways forever. Mirrored tiling means the span is independent of the
 * element it paints, so the same brush reads identically on a scrubber or a label.
 */
@Composable
fun rememberRainbowBrush(spanPx: Float = 520f, periodMs: Int = 3_200): Brush {
    val transition = rememberInfiniteTransition(label = "RainbowSweep")
    val phase by
            transition.animateFloat(
                    initialValue = 0f,
                    targetValue = spanPx * 2f,
                    animationSpec =
                            infiniteRepeatable(
                                    tween(periodMs, easing = LinearEasing),
                                    RepeatMode.Restart
                            ),
                    label = "RainbowSweepPhase",
            )
    return Brush.linearGradient(
            colors = RainbowStops,
            start = Offset(phase - spanPx, 0f),
            end = Offset(phase, 0f),
            tileMode = TileMode.Mirror,
    )
}
