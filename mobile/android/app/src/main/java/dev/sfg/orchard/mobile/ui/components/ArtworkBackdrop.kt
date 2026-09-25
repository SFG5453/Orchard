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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Ambient wash behind the whole app, tinted by whatever is playing.
 *
 * Deliberately faint: this sits under lists and headers that need to stay readable, so it reads as
 * a warmth in the background rather than a coloured screen. When [animated] it drifts slowly, which
 * is the same idea as the player's own glow but far more restrained.
 *
 * [rich] turns the restraint off, because frosted glass has nothing to be translucent over on a
 * near-black screen: the panes come out as grey cards. With it the wash becomes the screen — a
 * full vertical gradient of the cover's own tones with the pools laid over it — and the panes
 * finally read as glass sitting on top of something.
 *
 * Every animating value here is held as [State] and read inside the draw lambda rather than at
 * composition. This covers the whole window, and reading the drift during composition put a
 * full-screen recomposition on every frame for a wash that only ever needs to repaint.
 */
@Composable
fun ArtworkBackdrop(
    palette: ArtworkPalette,
    animated: Boolean,
    modifier: Modifier = Modifier,
    rich: Boolean = false,
) {
    val tint = animateColorAsState(
        if (rich) palette.accent.wash(0.12f, 0.32f, 0.18f) else palette.accent,
        tween(900),
        label = "BackdropTint",
    )
    val base = animateColorAsState(
        if (rich) palette.bottom.wash(0.06f, 0.18f, 0.04f) else palette.bottom,
        tween(900),
        label = "BackdropBase",
    )
    val crown = animateColorAsState(
        palette.top.wash(0.08f, 0.22f, 0.06f),
        tween(900),
        label = "BackdropCrown",
    )
    val core = animateColorAsState(
        palette.accent.wash(0.10f, 0.25f, 0.08f),
        tween(900),
        label = "BackdropCore",
    )

    // Held still rather than animated to a fixed target: an infinite transition keeps asking for
    // frames for as long as it exists, so the app would never go idle with the drift switched off.
    val drift: State<Float> = if (animated) {
        rememberInfiniteTransition(label = "BackdropDrift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(18_000), RepeatMode.Reverse),
            label = "BackdropDriftValue",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val phase = drift.value
                val accent = tint.value
                val glow = if (rich) RICH_GLOW_ALPHA else GLOW_ALPHA

                // Base is always AMOLED deep dark chrome
                drawRect(CanopyColors.Chrome)

                // Two offset radial pools, one warm and one cool, sliding past each other.
                val top = Offset(size.width * (0.22f + 0.30f * phase), size.height * (0.10f + 0.12f * phase))
                val low = Offset(size.width * (0.80f - 0.28f * phase), size.height * (0.74f - 0.14f * phase))
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = glow), Color.Transparent),
                        center = top,
                        radius = size.maxDimension * 0.72f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            lerp(base.value, accent, 0.35f).copy(alpha = glow * 0.8f),
                            Color.Transparent,
                        ),
                        center = low,
                        radius = size.maxDimension * 0.66f,
                    ),
                )
            },
    )
}

/**
 * Puts a sampled colour where the wash can use it: the cover's hue, its saturation pulled into a
 * usable band, and a lightness the wash chooses outright. Covers are routinely near-black or
 * washed out, and mixing those towards the app's chrome only ever produces mud.
 *
 * A cover with no hue worth keeping is left grey rather than given an invented one — the hue
 * angle of a near-neutral colour is noise, and forcing saturation onto it picks a random tint.
 */
private fun Color.wash(minSaturation: Float, maxSaturation: Float, lightness: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = if (hsl[1] < 0.08f) 0.05f else hsl[1].coerceIn(minSaturation, maxSaturation)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Low enough that body text keeps its contrast over the wash. */
private const val GLOW_ALPHA = 0.12f

/**
 * Behind glass the wash provides depth while staying deep, dark, and sleek.
 */
private const val RICH_GLOW_ALPHA = 0.14f
