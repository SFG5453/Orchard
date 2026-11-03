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

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private val PopEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Three dots for an instrumental break. They pop in one by one and fill as the break passes. */
@Composable
internal fun InstrumentalPauseRow(
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
    // Fraction of the break elapsed, read while drawing so frames never recompose the row.
    progress: () -> Float = { 0f },
) {
    val basePulse by if (active) rememberInfiniteTransition(label = "PausePulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "BasePulse",
    ) else remember { mutableFloatStateOf(0f) }

    val inactiveColor = Color.White.copy(alpha = 0.24f)

    Box(
        modifier = modifier
            .padding(vertical = 12.dp)
            .clickable(onClick = onClick),
        contentAlignment = if (alignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val shown by animateFloatAsState(
                    targetValue = if (active) 1f else 0.85f,
                    animationSpec = tween(380, delayMillis = if (active) index * 80 else 0, easing = PopEasing),
                    label = "DotPop",
                )
                val dotColor = if (active) accent else inactiveColor

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .graphicsLayer {
                            val lit = if (active) (progress() * 3f - index).coerceIn(0f, 1f) else 0f
                            val wave = (1f + kotlin.math.sin(2.0 * Math.PI * (basePulse - index * 0.12))).toFloat() / 2f
                            val scale = shown * (0.78f + 0.22f * lit + 0.14f * wave * lit)
                            scaleX = scale
                            scaleY = scale
                        }
                        .drawWithCache {
                            val radius = size.minDimension / 2f
                            onDrawBehind {
                                val lit = if (active) (progress() * 3f - index).coerceIn(0f, 1f) else 0f
                                val wave = (1f + kotlin.math.sin(2.0 * Math.PI * (basePulse - index * 0.12))).toFloat() / 2f
                                val alpha = if (active) 0.25f + 0.75f * lit * (0.8f + 0.2f * wave) else 0.24f
                                if (active) {
                                    drawCircle(
                                        color = dotColor.copy(alpha = 0.40f * alpha * lit),
                                        radius = radius * 2.2f,
                                    )
                                }
                                drawCircle(color = dotColor.copy(alpha = alpha), radius = radius)
                            }
                        },
                )
            }
        }
    }
}

