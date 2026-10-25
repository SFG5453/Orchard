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

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

private const val HOLD_MS = 600L

// Fraction of the hold before the ring appears, so taps and swipes never flash it.
private const val RING_DELAY = 0.3f

/** Switches between the song and its video after a still press of [HOLD_MS], with a filling ring. */
@Composable
internal fun Modifier.holdToSwitch(enabled: Boolean, onSwitch: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val fill = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pressed) {
        if (pressed) fill.animateTo(1f, tween(HOLD_MS.toInt(), easing = LinearEasing)) else fill.snapTo(0f)
    }
    return this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                anchor = down.position
                pressed = true
                // Null means the timeout elapsed with the finger still down and in place.
                val aborted = withTimeoutOrNull(HOLD_MS) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (change.changedToUp() || change.isConsumed || event.changes.size > 1 ||
                            (change.position - down.position).getDistance() > slop
                        ) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                pressed = false
                if (aborted == null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSwitch()
                    // Swallow the release so it does not count as a tap.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
        .drawWithContent {
            drawContent()
            val shown = ((fill.value - RING_DELAY) / (1f - RING_DELAY)).coerceIn(0f, 1f)
            if (shown <= 0f) return@drawWithContent
            val radius = 34.dp.toPx()
            val stroke = 3.dp.toPx()
            val topLeft = anchor - Offset(radius, radius)
            val box = Size(radius * 2, radius * 2)
            drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, topLeft, box, style = Stroke(stroke))
            drawArc(Color.White, -90f, 360f * shown, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
}
