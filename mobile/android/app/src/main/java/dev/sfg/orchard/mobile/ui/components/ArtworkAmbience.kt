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

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.sfg.orchard.mobile.artwork.highResolutionArtworkUrl
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import androidx.compose.ui.graphics.lerp

/** Colours sampled from the cover so the player can extend the image beyond its edges. */
data class ArtworkPalette(
    val top: Color = CanopyColors.PlayerBackdrop,
    val bottom: Color = CanopyColors.PlayerBackdrop,
    val accent: Color = CanopyColors.PlayerBackdrop,
    val deep: Color = CanopyColors.PlayerBackdrop,
)

/**
 * The backdrop is tinted with the artwork's own colours
 * instead of fading to black. This samples the cover's top edge, bottom edge, and
 * most saturated tone so the backdrop reads as a continuation of the image.
 */
@Composable
fun rememberArtworkPalette(
    artworkUrl: String,
    visibleAspect: Float = 1f,
    renderedFrame: Bitmap? = null,
): ArtworkPalette {
    val context = LocalContext.current
    var sampled by remember(artworkUrl) { mutableStateOf(ArtworkPalette()) }

    // Sample either the live video frame or the static artwork image.
    LaunchedEffect(artworkUrl, visibleAspect, renderedFrame) {
        if (renderedFrame != null) {
            withContext(Dispatchers.Default) {
                runCatching { palette(renderedFrame, visibleAspect) }.getOrNull()
            }?.let { sampled = it }
            return@LaunchedEffect
        }

        if (artworkUrl.isBlank()) return@LaunchedEffect
        val formattedUrl = highResolutionArtworkUrl(artworkUrl, 128)
        val request = ImageRequest.Builder(context)
            .data(formattedUrl)
            .allowHardware(false)
            .build()

        var imageResult = SingletonImageLoader.get(context).execute(request).image
        if (imageResult == null && formattedUrl != artworkUrl) {
            val fallbackRequest = ImageRequest.Builder(context)
                .data(artworkUrl)
                .allowHardware(false)
                .build()
            imageResult = SingletonImageLoader.get(context).execute(fallbackRequest).image
        }

        val image = imageResult ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            runCatching { palette(image.toBitmap(), visibleAspect) }.getOrNull()
        }?.let { sampled = it }
    }

    val top by animateColorAsState(sampled.top, tween(700), label = "ArtworkTintTop")
    val bottom by animateColorAsState(sampled.bottom, tween(700), label = "ArtworkTintBottom")
    val accent by animateColorAsState(sampled.accent, tween(700), label = "ArtworkTintAccent")
    val deep by animateColorAsState(sampled.deep, tween(700), label = "ArtworkTintDeep")
    return ArtworkPalette(top, bottom, accent, deep)
}

private fun palette(bitmap: Bitmap, visibleAspect: Float): ArtworkPalette {
    val height = bitmap.height.coerceAtLeast(1)
    val width = bitmap.width.coerceAtLeast(1)

    // ContentScale.Crop centres the cover and trims whichever axis overflows: a cover
    // wider than the box loses its sides, a taller one loses its top and bottom rows.
    // Only the surviving rect reaches the screen, so only it may feed the tint.
    val sourceAspect = width.toFloat() / height
    val visibleWidth = (visibleAspect / sourceAspect).coerceIn(0.1f, 1f)
    val visibleHeight = (sourceAspect / visibleAspect).coerceIn(0.1f, 1f)

    val firstColumn = ((width * (1f - visibleWidth)) / 2f).toInt().coerceIn(0, width - 1)
    val lastColumn = (width - firstColumn).coerceIn(firstColumn + 1, width)
    val firstRow = ((height * (1f - visibleHeight)) / 2f).toInt().coerceIn(0, height - 1)
    val lastRow = (height - firstRow).coerceIn(firstRow + 1, height)

    // Thin bands: the tint has to match the pixels it abuts, not the whole half of the cover.
    val band = ((lastRow - firstRow) * EDGE_BAND).toInt().coerceAtLeast(1)
    val rawTop = edgeAverage(bitmap, firstRow, firstRow + band, firstColumn, lastColumn)
    val rawBottom = edgeAverage(bitmap, lastRow - band, lastRow, firstColumn, lastColumn)
    val top = rawTop.toneForBackdrop()
    val bottom = rawBottom.toneForBackdrop()
    val accent = dominantAccent(bitmap)
    val deep = lerp(bottom, CanopyColors.Chrome, 0.40f)
    return ArtworkPalette(
        top = top,
        bottom = bottom,
        accent = accent.shade(0.92f),
        deep = deep,
    )
}

/** Averages a horizontal band of the cover across the columns the crop leaves visible. */
private fun edgeAverage(bitmap: Bitmap, firstRow: Int, lastRow: Int, firstColumn: Int, lastColumn: Int): Color {
    val step = ((lastColumn - firstColumn) / 24).coerceAtLeast(1)
    var red = 0L
    var green = 0L
    var blue = 0L
    var samples = 0

    for (y in firstRow.coerceAtLeast(0) until lastRow.coerceAtMost(bitmap.height)) {
        var x = firstColumn
        while (x < lastColumn) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel shr 24) and 0xFF
            if (alpha < 32) {
                x += step
                continue
            }
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            samples++
            x += step
        }
    }
    if (samples == 0) return CanopyColors.PlayerBackdrop
    return Color((red / samples) / 255f, (green / samples) / 255f, (blue / samples) / 255f)
}

private const val EDGE_BAND = 0.05f

/**
 * The cover's dominant colourful hue.
 *
 * Picking the single most saturated pixel let a few square centimetres of skin tone outvote an
 * entire ocean, so hues are binned and weighted by how much of the cover they actually cover.
 * The winning bin is then averaged, which keeps the colour honest rather than extreme.
 */
private fun dominantAccent(bitmap: Bitmap): Color {
    val step = (bitmap.width / 40).coerceAtLeast(1)
    val weights = FloatArray(HUE_BINS)
    val reds = FloatArray(HUE_BINS)
    val greens = FloatArray(HUE_BINS)
    val blues = FloatArray(HUE_BINS)
    val hsv = FloatArray(3)

    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (((pixel shr 24) and 0xFF) < 32) {
                x += step
                continue
            }
            AndroidColor.colorToHSV(pixel, hsv)
            val (hue, saturation, value) = Triple(hsv[0], hsv[1], hsv[2])
            // Greys carry no hue to bin, and near-black or blown-out pixels report unstable ones.
            if (saturation >= 0.18f && value in 0.12f..0.97f) {
                val bin = ((hue / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                val weight = saturation * (1f - abs(value - 0.62f))
                weights[bin] += weight
                reds[bin] += ((pixel shr 16) and 0xFF) / 255f * weight
                greens[bin] += ((pixel shr 8) and 0xFF) / 255f * weight
                blues[bin] += (pixel and 0xFF) / 255f * weight
            }
            x += step
        }
        y += step
    }

    val bin = weights.indices.maxByOrNull { weights[it] } ?: return CanopyColors.PlayerBackdrop
    val total = weights[bin]
    if (total <= 0f) return CanopyColors.PlayerBackdrop
    return Color(reds[bin] / total, greens[bin] / total, blues[bin] / total)
}

private const val HUE_BINS = 36

private fun Color.shade(factor: Float): Color = Color(red * factor, green * factor, blue * factor)

private fun Color.toneForBackdrop(): Color {
    val maxChannel = max(red, max(green, blue))
    return if (maxChannel > 0.80f) {
        val factor = 0.80f / maxChannel
        Color(red * factor, green * factor, blue * factor, alpha)
    } else {
        this
    }
}

