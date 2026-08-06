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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.TrackActionsPopup

/** Which mode, if any, has taken over the middle of the player. */
enum class PlayerPanel { NONE, LYRICS, QUEUE }

/**
 * Everything below the track title: scrubber, transport, volume, destinations.
 *
 * Shared by both layouts. The phone stacks it under the artwork; the tablet puts
 * it in the right column beside the cover, and neither should drift from the
 * other as controls are added.
 */
@Composable
fun PlayerControlStack(
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    canControl: Boolean,
    localControls: Boolean,
    transition: TransitionMarker?,
    showBitrate: Boolean,
    bitrateKbps: Int,
    remoteVolume: Float,
    lyricsActive: Boolean,
    queueActive: Boolean,
    onRemoteVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLyrics: () -> Unit,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
) {
    PlayerScrubber(
        playback = playback,
        onSeek = onSeek,
        transition = transition,
        showBitrate = showBitrate,
        bitrateKbps = bitrateKbps,
    )
    Spacer(Modifier.height(16.dp))
    PlayerTransportControls(
        isPlaying = playback.isPlaying,
        status = playback.status,
        shuffle = playback.shuffle,
        repeatMode = playback.repeatMode,
        localControls = canControl,
        onToggle = onToggle,
        onPrevious = onPrevious,
        onNext = onNext,
        onShuffle = onShuffle,
        onRepeat = onRepeat,
    )
    Spacer(Modifier.height(24.dp))
    DeviceVolumeSlider(
        enabled = canControl,
        isRemote = !localControls,
        remoteVolume = remoteVolume,
        onRemoteVolumeChange = onRemoteVolumeChange,
    )
    Spacer(Modifier.height(20.dp))
    PlayerBottomDestinations(
        targets = targets,
        upcomingCount = playback.upcoming.size,
        onLyrics = onLyrics,
        lyricsActive = lyricsActive,
        onDevices = onDevices,
        onQueue = onQueue,
        queueActive = queueActive,
    )
}

/** Compact track identity shown above an open panel, replacing the large title row. */
@Composable
fun PanelTrackHeader(
    track: Track,
    liked: Boolean,
    onLiked: () -> Unit,
    onMore: () -> Unit,
    onShare: (() -> Unit)?,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = track,
            transitionSpec = {
                (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 })
                    .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
            },
            label = "PanelTrackHeaderContent",
            modifier = Modifier.weight(1f),
        ) { currentTrack ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ArtworkTile(currentTrack.artworkUrl, "Artwork for ${currentTrack.title}", Modifier.size(44.dp), 8)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        currentTrack.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        currentTrack.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        var menuOpen by remember { mutableStateOf(false) }
        if (menuOpen) {
            TrackActionsPopup(
                track = track,
                onDismiss = { menuOpen = false },
                onViewQueue = onMore,
                onDownload = if (!isDownloaded) onDownload else null,
                onRemoveDownload = if (isDownloaded) onRemoveDownload else null,
                onShare = onShare,
            )
        }
        TrackActionButtons(
            liked = liked,
            onLiked = onLiked,
            onMore = onMore,
            onShare = onShare,
            onOpenMenu = { menuOpen = true },
        )
    }
}

/** Centred status text for the states where there is nothing to sing along to. */
@Composable
fun LyricsNotice(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.70f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Sampled cover accents can be muddy or near-black. Saturate and brighten until the colour reads
 * as a highlight against the dimmed artwork, falling back to white for greys with no hue to keep.
 */
fun Color.readableOnDarkBackdrop(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    if (hsl[1] < 0.12f) return Color.White
    hsl[1] = hsl[1].coerceIn(0.55f, 0.95f)
    hsl[2] = hsl[2].coerceIn(0.62f, 0.80f)
    return Color(ColorUtils.HSLToColor(hsl))
}
