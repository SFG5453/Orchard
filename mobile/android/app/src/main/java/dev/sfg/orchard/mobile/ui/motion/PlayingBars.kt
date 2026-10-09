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

package dev.sfg.orchard.mobile.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Mismatched periods so the bars never fall into lockstep. Jazz, basically.
private val BarPeriods = intArrayOf(520, 410, 610)

/** Bouncing equaliser bars marking the row that is playing. */
@Composable
fun PlayingBars(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val transition = rememberInfiniteTransition(label = "PlayingBars")
    val heights = BarPeriods.mapIndexed { i, period ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period, delayMillis = i * 90, easing = LinearEasing), RepeatMode.Reverse),
            label = "Bar$i",
        )
    }
    // Heights are read in draw, so the bars bounce without recomposing the row.
    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.14f
        val barWidth = (this.size.width - gap * 2) / 3f
        heights.forEachIndexed { i, h ->
            val barHeight = this.size.height * h.value
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), this.size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
