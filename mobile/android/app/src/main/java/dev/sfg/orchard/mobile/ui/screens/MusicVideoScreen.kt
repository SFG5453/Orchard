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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ui.foldable.isFoldableOrWideLayout
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import dev.sfg.orchard.mobile.app.MusicVideoState
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Transport callbacks shared by the portrait and fullscreen video layouts. */
internal class MusicVideoActions(
    val onMinimize: () -> Unit,
    val onSwitch: () -> Unit,
    val onToggle: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onLiked: () -> Unit,
    val onDevices: () -> Unit,
    val onQuality: (Int) -> Unit,
)

/**
 * The video player over the song player while the video is on. Holding the video or
 * tapping Song returns to the song.
 */
@Composable
internal fun BoxScope.MusicVideoLayer(
    musicVideo: MusicVideoState,
    player: Player?,
    track: Track,
    playback: PlaybackSnapshot,
    liked: Boolean,
    localControls: Boolean,
    actions: MusicVideoActions,
) {
    var fullscreen by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(musicVideo.playing) { if (!musicVideo.playing) fullscreen = false }

    AnimatedVisibility(
        visible = musicVideo.playing,
        enter = fadeIn(tween(260)),
        exit = fadeOut(tween(200)),
        modifier = Modifier.fillMaxSize(),
    ) {
        FullscreenVideoEffect(fullscreen) { fullscreen = false }
        if (fullscreen) {
            MusicVideoFullscreen(
                musicVideo = musicVideo,
                player = player,
                track = track,
                playback = playback,
                localControls = localControls,
                actions = actions,
                onSong = { fullscreen = false; actions.onSwitch() },
                onExit = { fullscreen = false },
            )
        } else {
            MusicVideoPortrait(
                musicVideo = musicVideo,
                player = player,
                track = track,
                playback = playback,
                liked = liked,
                localControls = localControls,
                actions = actions,
                onFullscreen = { fullscreen = true },
            )
        }
    }
}

@Composable
private fun MusicVideoPortrait(
    musicVideo: MusicVideoState,
    player: Player?,
    track: Track,
    playback: PlaybackSnapshot,
    liked: Boolean,
    localControls: Boolean,
    actions: MusicVideoActions,
    onFullscreen: () -> Unit,
) {
    val palette = rememberFullBleedPalette(track)
    Box(Modifier.fillMaxSize().background(CanopyColors.Chrome)) {
        // Ambient light: the cover palette bleeding out from behind the video.
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (isFoldableOrWideLayout()) Modifier.fillMaxHeight(0.8f) else Modifier.height(380.dp))
                .padding(top = 40.dp)
                .blur(70.dp)
                .background(Brush.verticalGradient(listOf(palette.deep, palette.accent, Color.Transparent)))
                .background(Color.Black.copy(alpha = 0.35f)),
        )
        val header: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = actions.onMinimize) {
                    Icon(Icons.Rounded.ExpandMore, "Minimize player", tint = CanopyColors.Text)
                }
                SongTab(actions.onSwitch)
                IconButton(onClick = actions.onDevices) {
                    Icon(Icons.Rounded.Cast, "Connect to a device", tint = CanopyColors.Text)
                }
            }
        }
        val video: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.aspectRatio(16f / 9f).background(Color.Black).holdToSwitch(true, actions.onSwitch)) {
                MusicVideoSurface(player, Modifier.fillMaxSize())
                VideoQualityButton(musicVideo, actions.onQuality, Modifier.align(Alignment.BottomStart).padding(12.dp))
                IconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Box(
                        Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Fullscreen, "Full screen", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        val controls: @Composable ColumnScope.(Boolean) -> Unit = { pinNextToBottom ->
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = if (pinNextToBottom) 24.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        color = CanopyColors.Text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        color = CanopyColors.MutedStrong,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = actions.onLiked) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Unlike" else "Like",
                        tint = if (liked) CanopyColors.Favorite else CanopyColors.Text,
                    )
                }
            }
            PlayerScrubber(playback, actions.onSeek, Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            PlayerTransportControls(
                isPlaying = playback.isPlaying,
                status = playback.status,
                shuffle = playback.shuffle,
                repeatMode = playback.repeatMode,
                localControls = localControls,
                onToggle = actions.onToggle,
                onPrevious = actions.onPrevious,
                onNext = actions.onNext,
                onShuffle = actions.onShuffle,
                onRepeat = actions.onRepeat,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (pinNextToBottom) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(16.dp))
            playback.upcoming.firstOrNull()?.let { next -> UpNextPeek(next, actions.onNext) }
        }
        if (isFoldableOrWideLayout()) {
            // Wide screens put the picture beside its controls so neither is pushed off screen.
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                header()
                Row(Modifier.weight(1f).padding(start = 24.dp, end = 16.dp, bottom = 24.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        video(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                    }
                    Column(
                        Modifier.width(380.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) { controls(false) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                header()
                video(Modifier.fillMaxWidth())
                controls(true)
            }
        }
    }
}

@Composable
private fun UpNextPeek(next: Track, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CanopyColors.Canvas)
            .border(1.dp, CanopyColors.Rule, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Play next", onClick = onNext)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = next.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(54.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text("UP NEXT", color = CanopyColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(next.title, color = CanopyColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(next.artist, color = CanopyColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = CanopyColors.Muted)
    }
}

/** Flat text control that returns from the video to the song. */
@Composable
internal fun SongTab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "Song",
        color = CanopyColors.Text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
