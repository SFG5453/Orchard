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

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The picture on a TextureView laid out here, so black bars baked into the video can be cropped
 * away. [fill] zooms until no bars remain and trims the edges of the picture.
 */
@Composable
internal fun MusicVideoSurface(player: Player?, modifier: Modifier = Modifier, fill: Boolean = false) {
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var videoSize by remember(player) { mutableStateOf(player?.videoSize ?: VideoSize.UNKNOWN) }
    var buffering by remember(player) { mutableStateOf(player?.playbackState == Player.STATE_BUFFERING) }
    var crop by remember(player) { mutableStateOf<ContentRect?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                crop = null
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    DisposableEffect(player, textureView) {
        val view = textureView
        if (player != null && view != null) player.setVideoTextureView(view)
        onDispose { if (player != null && view != null) player.clearVideoTextureView(view) }
    }
    // One frame a second is enough; bars do not move within a video.
    LaunchedEffect(textureView, videoSize) {
        val view = textureView ?: return@LaunchedEffect
        if (videoSize.width <= 0 || videoSize.height <= 0) return@LaunchedEffect
        val width = VideoLetterbox.SAMPLE_WIDTH
        val height = max(1, width * videoSize.height / videoSize.width)
        val pixels = IntArray(width * height)
        while (true) {
            delay(1_000)
            if (!view.isAvailable) continue
            val frame = view.getBitmap(width, height) ?: continue
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            frame.recycle()
            crop = VideoLetterbox.accumulate(crop, VideoLetterbox.contentRect(pixels, width, height))
        }
    }

    val target = crop ?: ContentRect.Full
    val left by animateFloatAsState(target.left, tween(CROP_EASE_MS), label = "crop left")
    val top by animateFloatAsState(target.top, tween(CROP_EASE_MS), label = "crop top")
    val right by animateFloatAsState(target.right, tween(CROP_EASE_MS), label = "crop right")
    val bottom by animateFloatAsState(target.bottom, tween(CROP_EASE_MS), label = "crop bottom")
    val zoom by animateFloatAsState(if (fill) 1f else 0f, tween(CROP_EASE_MS), label = "fill")

    Box(modifier.clipToBounds().background(Color.Black)) {
        AndroidView(
            factory = { context -> TextureView(context).also { textureView = it } },
            modifier = Modifier.layout { measurable, constraints ->
                val boxWidth = constraints.maxWidth
                val boxHeight = constraints.maxHeight
                val frameWidth = videoSize.width * videoSize.pixelWidthHeightRatio
                val frameHeight = videoSize.height.toFloat()
                if (frameWidth <= 0f || frameHeight <= 0f || boxWidth == Constraints.Infinity || boxHeight == Constraints.Infinity) {
                    val placeable = measurable.measure(constraints)
                    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                // Scale so the cropped picture fits the box, or covers it when filling.
                val pictureWidth = frameWidth * (right - left)
                val pictureHeight = frameHeight * (bottom - top)
                val fit = min(boxWidth / pictureWidth, boxHeight / pictureHeight)
                val cover = max(boxWidth / pictureWidth, boxHeight / pictureHeight)
                val scale = fit + (cover - fit) * zoom
                val viewWidth = (frameWidth * scale).roundToInt()
                val viewHeight = (frameHeight * scale).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(viewWidth, viewHeight))
                // Centre the picture's middle, not the frame's, on the box.
                val x = boxWidth / 2f - (left + right) / 2f * viewWidth
                val y = boxHeight / 2f - (top + bottom) / 2f * viewHeight
                layout(boxWidth, boxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
            },
        )
        if (buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }
}

private const val CROP_EASE_MS = 600
