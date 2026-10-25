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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import dev.sfg.orchard.mobile.ui.glass.LocalGlass

/** Share of black mixed into the cover colour for the active control. */
internal const val COVER_FILL_ACTIVE = 0.35f

/** Flat fill from the playing cover's colour, read at draw time so a cover change does not recompose. */
@Composable
internal fun Modifier.coverFill(shape: Shape, darken: Float, alpha: Float = 1f): Modifier {
    val style = LocalGlass.current
    return drawBehind {
        val color: Color = lerp(style.tint, Color.Black, darken).copy(alpha = alpha)
        drawOutline(shape.createOutline(size, layoutDirection, this), color)
    }
}
