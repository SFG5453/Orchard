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
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import dev.sfg.orchard.connect.R
import dev.sfg.orchard.mobile.artwork.highResolutionArtworkUrl
import dev.sfg.orchard.mobile.ui.theme.OrchardColors
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

@Composable
fun RemoteArtwork(
    url: String,
    description: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val source = remember(url) { highResolutionArtworkUrl(url) }
    Box(modifier.background(OrchardColors.Bark), contentAlignment = Alignment.Center) {
        OrchardMark(Modifier.fillMaxSize(0.48f))
        if (source.isNotBlank()) {
            AsyncImage(
                model = artworkRequest(context, source),
                contentDescription = description,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun artworkRequest(context: android.content.Context, url: String): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .crossfade(150)
    .build()


/** Slow, blurred artwork motion ported from desktop's ambient Pixi backdrop. */
@Composable
fun AmbientArtworkHaze(
    artworkUrl: String,
    moving: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orchard haze")
    val drift by transition.animateFloat(
        initialValue = -0.025f,
        targetValue = 0.025f,
        animationSpec = infiniteRepeatable(tween(18_000), RepeatMode.Reverse),
        label = "haze drift",
    )
    if (artworkUrl.isNotBlank()) {
        val context = LocalContext.current
        AsyncImage(
            model = artworkRequest(context, highResolutionArtworkUrl(artworkUrl, 720)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.32f
                    scaleX = 1.34f
                    scaleY = 1.34f
                    translationX = size.width * if (moving) drift else 0f
                    translationY = size.height * if (moving) -drift else 0f
                }
                .blur(64.dp),
        )
    }
}

/** Plays optional HLS/video artwork silently; the still artwork stays beneath it. */
@Composable
@UnstableApi
fun AnimatedArtworkVideo(
    url: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onFrame: ((Bitmap) -> Unit)? = null,
) {
    if (url.isBlank()) return
    val context = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    var firstFrameReady by remember(url) { mutableStateOf(false) }
    if (failed) return

    val player = remember(url) {
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Orchard Android/2.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        // Motion covers are short silent loops, so the stock buffering targets (which hold
        // playback until seconds of video are ready) just delay the first frame. Start as soon
        // as there is anything to show, but let the buffer run past the 10s that adaptive
        // selection demands before it will step up a rendition: capped shorter, an HLS cover
        // stayed pinned to the lowest variant for the entire loop.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 30_000, 120, 240)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // A cover loop is a few seconds long, so there is nothing to gain by easing into it at a
        // low bitrate the way ABR would for a full track: take the best rendition immediately.
        // The viewport constraint goes too, since it is sized for the display while the artwork
        // is drawn zoom-cropped and can use detail beyond it.
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .clearViewportSizeConstraints(),
            )
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    LaunchedEffect(url, active) {
        if (player.currentMediaItem?.localConfiguration?.uri?.toString() != url) {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .apply {
                    if (url.contains(".m3u8")) {
                        setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    } else if (url.contains(".mp4")) {
                        setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                    }
                }
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
        }
        player.playWhenReady = active
    }

    DisposableEffect(player, url) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameReady = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    firstFrameReady = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("AnimatedArtwork", "Animated artwork failed; retaining the still image: ${error.message}", error)
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (firstFrameReady) 1f else 0f,
        animationSpec = tween(400),
        label = "AnimatedArtworkAlpha",
    )

    var playerView by remember(url) { mutableStateOf<PlayerView?>(null) }

    // The texture is the zoom-cropped frame as drawn, so a caller tinting the screen
    // around the video can sample it directly without replaying the crop.
    if (onFrame != null) {
        LaunchedEffect(playerView, firstFrameReady, url) {
            val texture = playerView?.videoSurfaceView as? TextureView ?: return@LaunchedEffect
            if (!firstFrameReady) return@LaunchedEffect
            while (true) {
                if (texture.isAvailable && texture.width > 0 && texture.height > 0) {
                    val height = (FRAME_SAMPLE_WIDTH * texture.height / texture.width).coerceAtLeast(1)
                    texture.getBitmap(FRAME_SAMPLE_WIDTH, height)?.let(onFrame)
                }
                delay(FRAME_SAMPLE_INTERVAL_MS)
            }
        }
    }

    AndroidView(
        factory = { targetContext ->
            (LayoutInflater.from(targetContext).inflate(R.layout.animated_artwork_player, null) as PlayerView).apply {
                this.player = player
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                playerView = this
            }
        },
        update = { view ->
            view.alpha = animatedAlpha
        },
        modifier = modifier.graphicsLayer { alpha = animatedAlpha },
    )
}

private const val FRAME_SAMPLE_WIDTH = 64
private const val FRAME_SAMPLE_INTERVAL_MS = 1_500L

@Composable
fun ArtworkTile(
    url: String,
    description: String,
    modifier: Modifier = Modifier,
    radius: Int = 14,
) {
    RemoteArtwork(
        url = url,
        description = description,
        modifier = modifier.clip(RoundedCornerShape(radius.dp)),
    )
}
