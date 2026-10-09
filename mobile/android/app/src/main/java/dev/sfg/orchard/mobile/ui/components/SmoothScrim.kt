/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) maxrave-dev and SimpMusic contributors
 *
 * This file is part of Orchard.
 *
 * Adapted from smoothScrimBrush in SimpMusic v2.2.0
 * (composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/extension/UIExt.kt),
 * https://github.com/maxrave-dev/SimpMusic, licensed under the GNU General Public
 * License version 3. It is combined with Orchard under section 13 of the GNU GPL v3
 * and GNU AGPL v3.
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

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Vertical scrim from [from] to [to] with no visible leading edge.
 *
 * Smoothstep keeps the ramp flat at both ends; a linear ramp's corner is what the eye reads
 * as a seam. Colours are interpolated here so `color.copy(alpha = 0f)` keeps its hue instead
 * of Skia dragging RGB through black (a grey band). [steps] keeps the piecewise curve below
 * 8-bit banding on dark backgrounds.
 */
fun smoothScrimBrush(
    from: Color,
    to: Color,
    startFraction: Float = 0f,
    endFraction: Float = 1f,
    // 25 colour stops to fade one gradient. The GPU has seen worse.
    steps: Int = 24,
): Brush = Brush.verticalGradient(
    colorStops = Array(steps + 1) { i ->
        val t = i / steps.toFloat()
        val position = startFraction + (endFraction - startFraction) * t
        position to lerp(from, to, t * t * (3f - 2f * t))
    },
)
