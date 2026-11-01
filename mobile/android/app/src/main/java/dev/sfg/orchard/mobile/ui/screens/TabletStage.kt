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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork

/** Looping portrait art across the player side, feathered into the pane (or into the room when solo). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun BoxWithConstraintsScope.TabletStage(
    url: String,
    stillUrl: String,
    playing: Boolean,
    aspect: Float,
    onAspect: (Float) -> Unit,
    split: Float,
    focus: Float,
    /** Book foldables end the art at the crease with no feather. */
    crease: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val core = minOf(maxWidth * 0.6f, maxHeight * aspect)
    val soloFeather = core * 0.3f
    val soloWidth = minOf(maxWidth, core + soloFeather * 2)
    val paneX = maxWidth * (0.5f - 0.12f * focus)
    val sideFeather = if (crease) 0.dp else paneX * 0.35f
    val sideWidth = if (crease) paneX else minOf(maxWidth, paneX + paneX * 0.12f)
    val width = soloWidth + (sideWidth - soloWidth) * split
    val x = (maxWidth - soloWidth) / 2 * (1f - split)
    val leftFeather = soloFeather * (1f - split)
    val rightFeather = soloFeather + (sideFeather - soloFeather) * split
    Box(
        modifier = modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val total = size.width
                val fadeIn = (leftFeather.toPx() / total).coerceIn(0f, 0.5f)
                val fadeOut = (rightFeather.toPx() / total).coerceIn(0f, 0.5f)
                // Darkens the lower half so the controls stay legible; the mask below feathers it too.
                drawRect(
                    brush = Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    ),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to if (fadeIn > 0f) Color.Transparent else Color.Black,
                        fadeIn.coerceAtLeast(0.0001f) to Color.Black,
                        (1f - fadeOut).coerceAtLeast(0.5f) to Color.Black,
                        1f to if (fadeOut > 0f) Color.Transparent else Color.Black,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        RemoteArtwork(url = stillUrl, description = "", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // The video view binds its player once, so each loop needs its own instance.
        key(url) {
            AnimatedArtworkVideo(
                url = url,
                active = playing,
                modifier = Modifier.fillMaxSize(),
                onVideoAspect = onAspect,
            )
        }
    }
}
