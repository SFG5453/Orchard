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
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dev.kawarp.KawarpEngine
import dev.sfg.orchard.mobile.artwork.highResolutionArtworkUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Low-bandwidth full-screen artwork motion for the tablet player.
 *
 * Kawarp preprocesses a cover at 128x128 once, then draws one AGSL pass. We deliberately drive
 * that pass at 30 fps rather than display refresh rate: this is ambient motion behind a scrim,
 * and doubling or quadrupling its fill rate buys no useful detail on high-refresh tablets.
 */
@Composable
fun KawarpArtworkBackdrop(
    artworkUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember {
        if (!KawarpEngine.isSupported()) {
            null
        } else {
            runCatching {
                KawarpEngine().apply {
                    setAnimationSpeed(0.72f)
                    setSaturation(1.22f)
                    setScale(1.08f)
                    setAutoDarken(0.68f)
                    setTransitionDuration(700)
                    setPlaybackReactive(true)
                }
            }.getOrNull()
        }
    }
    if (engine == null || artworkUrl.isBlank()) return

    // A revision starts the draw clock after an asynchronously loaded cover reaches the engine.
    // Without it, a paused player could stop invalidating before a slow network load completes.
    var coverRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(engine, artworkUrl) {
        loadKawarpCover(context, artworkUrl)?.let { cover ->
            engine.setCover(cover)
            coverRevision++
        }
    }

    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(engine, coverRevision, isPlaying) {
        engine.setPlaying(isPlaying)
        // Keep a short grace period for the background-thread blur and the cover crossfade. Once
        // paused and settled, isAnimating becomes false and the shader consumes no more frames.
        val forceFramesUntil = SystemClock.uptimeMillis() + COVER_SETTLE_GRACE_MS
        var lastDrawNanos = 0L
        while (
            currentCoroutineContext().isActive &&
                (isPlaying || SystemClock.uptimeMillis() < forceFramesUntil || engine.isAnimating)
        ) {
            withFrameNanos { now ->
                if (lastDrawNanos == 0L || now - lastDrawNanos >= FRAME_INTERVAL_NANOS) {
                    lastDrawNanos = now
                    frame++
                }
            }
        }
    }

    Canvas(modifier) {
        frame // Reading the clock invalidates only this draw layer, not the player hierarchy.
        engine.draw(drawContext.canvas.nativeCanvas, size.width, size.height)
    }
}

private suspend fun loadKawarpCover(
    context: android.content.Context,
    artworkUrl: String,
): Bitmap? {
    val loader = SingletonImageLoader.get(context)
    val sizedUrl = highResolutionArtworkUrl(artworkUrl, KAWARP_SOURCE_SIZE)

    suspend fun load(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(KAWARP_SOURCE_SIZE, KAWARP_SOURCE_SIZE))
            .allowHardware(false)
            .build()
        return loader.execute(request).image?.toBitmap()
    }

    return load(sizedUrl) ?: if (sizedUrl != artworkUrl) load(artworkUrl) else null
}

private const val KAWARP_SOURCE_SIZE = 256
// Slightly below the exact interval so fractional 60/120 Hz timestamps do not accidentally
// turn a nominal 30 fps loop into 20/24 fps by missing every second/fourth vsync by a nanosecond.
private const val FRAME_INTERVAL_NANOS = 32_000_000L
private const val COVER_SETTLE_GRACE_MS = 3_000L
