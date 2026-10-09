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

package dev.sfg.orchard.mobile.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlin.random.Random

// Blur alone washes colour out; a saturation lift gives the backdrop back its body.
internal val SaturatedBackdropEffect by lazy {
    val matrix = ColorMatrix().apply { setSaturation(1.3f) }
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix)).asComposeRenderEffect()
}

private const val GRAIN_TILE = 64
private const val GRAIN_ALPHA = 0.07f

// One tile for the whole app. Noise is noise, nobody checks which pane it came from.
private val GrainBrush by lazy {
    val random = Random(0x0C4A12D)
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE) {
        val white = random.nextBoolean()
        val alpha = (random.nextFloat() * GRAIN_ALPHA * 255f).toInt()
        (alpha shl 24) or if (white) 0xFFFFFF else 0x000000
    }
    val tile = Bitmap.createBitmap(pixels, GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
    ShaderBrush(BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
}

/**
 * Finish for devices without AGSL (Android 12). The blur comes from [RenderEffect]; this adds
 * film, tiled grain and a diagonal rim light in place of the shader's corner-aware rim.
 */
internal class GlassFrostGradient(private val spec: GlassSpec) {
    private var undercoatBrush: Brush? = null
    private var film: Brush? = null
    private var filmTint = Color.Unspecified
    private var filmBlurred: Boolean? = null

    // Light from the top left: bright leading edge, dim trailing edge.
    private val rim =
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.40f),
            0.45f to Color.White.copy(alpha = 0.08f),
            1f to Color.Black.copy(alpha = 0.16f),
            start = Offset.Zero,
            end = Offset.Infinite,
        )

    fun draw(scope: DrawScope, outline: Outline, tint: Color, blurred: Boolean) {
        if (tint != filmTint || blurred != filmBlurred) {
            val undercoatWeight = if (blurred) spec.contrastUndercoat else spec.solidUndercoat
            undercoatBrush =
                if (undercoatWeight > 0.001f) {
                    Brush.verticalGradient(
                        0f to CanopyColors.Chrome.copy(alpha = undercoatWeight * 0.85f),
                        1f to CanopyColors.Chrome.copy(alpha = undercoatWeight * 1.15f),
                    )
                } else {
                    null
                }

            val weight = if (blurred) spec.film else spec.solidFilm
            val mixed =
                lerp(
                    if (blurred) spec.base else spec.solidBase,
                    tint,
                    if (blurred) spec.tintMix else spec.solidTintMix,
                )
            film =
                Brush.verticalGradient(
                    0f to mixed.copy(alpha = (weight * 1.12f).coerceAtMost(1f)),
                    0.35f to mixed.copy(alpha = weight),
                    1f to mixed.copy(alpha = weight * 0.88f),
                )
            filmTint = tint
            filmBlurred = blurred
        }
        undercoatBrush?.let { scope.drawOutline(outline, brush = it) }
        scope.drawOutline(outline, brush = film ?: return)
        scope.drawOutline(outline, brush = GrainBrush)
        scope.drawOutline(outline, brush = rim, style = Stroke(width = 1.5f))
    }
}
