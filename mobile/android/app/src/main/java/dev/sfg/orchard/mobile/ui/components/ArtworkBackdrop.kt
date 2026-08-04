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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Ambient wash behind the whole app, tinted by whatever is playing.
 *
 * Deliberately faint: this sits under lists and headers that need to stay readable, so it reads as
 * a warmth in the background rather than a coloured screen. When [animated] it drifts slowly, which
 * is the same idea as the player's own glow but far more restrained.
 */
@Composable
fun ArtworkBackdrop(
    artworkUrl: String,
    animated: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = rememberArtworkPalette(artworkUrl)
    val tint by animateColorAsState(palette.accent, tween(900), label = "BackdropTint")
    val base by animateColorAsState(palette.bottom, tween(900), label = "BackdropBase")

    val drift by rememberInfiniteTransition(label = "BackdropDrift").animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(18_000), RepeatMode.Reverse),
        label = "BackdropDriftValue",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(CanopyColors.Chrome)
                // Two offset radial pools, one warm and one cool, sliding past each other.
                val top = Offset(size.width * (0.22f + 0.30f * drift), size.height * (0.10f + 0.12f * drift))
                val low = Offset(size.width * (0.80f - 0.28f * drift), size.height * (0.74f - 0.14f * drift))
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = GLOW_ALPHA), Color.Transparent),
                        center = top,
                        radius = size.maxDimension * 0.72f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            lerp(base, tint, 0.35f).copy(alpha = GLOW_ALPHA * 0.8f),
                            Color.Transparent,
                        ),
                        center = low,
                        radius = size.maxDimension * 0.66f,
                    ),
                )
            },
    )
}

/** Low enough that body text keeps its contrast over the wash. */
private const val GLOW_ALPHA = 0.20f
