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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.sfg.orchard.mobile.app.MusicVideoState

@Composable
fun MusicVideoToggle(
    state: MusicVideoState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = state.available && !state.checking,
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.62f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.checking) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = if (state.playing) Icons.Rounded.MusicNote else Icons.Rounded.Videocam,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = when {
                    state.playing -> "Audio"
                    state.checking -> "Finding video"
                    else -> "Video"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Session-backed music-video surface with Media3 transport, seeking, and fullscreen controls. */
@Composable
fun MusicVideoPlayer(
    player: Player?,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }
    DisposableEffect(fullscreen, activity) {
        if (fullscreen && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (fullscreen && activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { playerView?.player = null }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { targetContext ->
                PlayerView(targetContext).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = true
                    controllerShowTimeoutMs = 3_000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowPreviousButton(true)
                    setShowNextButton(true)
                    setFullscreenButtonClickListener(onFullscreenChange)
                    setFullscreenButtonState(fullscreen)
                    this.player = player
                    playerView = this
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.setFullscreenButtonState(fullscreen)
            },
            modifier = Modifier.fillMaxSize(),
        )

        MusicVideoToggle(
            state = MusicVideoState(videoId = "active", playing = true),
            onClick = {
                if (fullscreen) onFullscreenChange(false)
                onAudio()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(16.dp),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
