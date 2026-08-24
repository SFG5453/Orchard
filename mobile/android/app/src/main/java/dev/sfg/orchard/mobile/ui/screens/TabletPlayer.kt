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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork

/** Two-column layout used when the screen is wider than 600dp (tablets, foldables unfolded, landscape). */
@Composable
fun TabletPlayerBody(
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<dev.sfg.orchard.mobile.model.LyricLine>>,
    /** Sampled by the caller from the full-bleed backdrop so both agree on the colour. */
    lyricAccent: Color,
    /** The framed cover's bounds; on a tablet this, not the backdrop, is the cover you see. */
    onCoverBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    transition: TransitionMarker?,
    mixProgress: Float? = null,
    canControl: Boolean,
    localControls: Boolean,
    liked: Boolean,
    panel: PlayerPanel,
    animatedArtworkEnabled: Boolean,
    showBitrate: Boolean,
    bitrateKbps: Int,
    remoteVolume: Float,
    dragHandle: Modifier,
    onRemoteVolumeChange: (Float) -> Unit,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLiked: () -> Unit,
    onDevices: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onClearUpcoming: () -> Unit,
    downloadedTrackIds: Set<String> = emptySet(),
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onShare: (() -> Unit)?,
    onOpenCollection: ((String) -> Unit)?,
    onLyricsPanel: () -> Unit,
    onQueuePanel: () -> Unit,
    sleepTimerRemainingSeconds: Long = 0L,
    sleepTimerEndOfTrack: Boolean = false,
    onSleepTimer: () -> Unit = {},
    autoplayEnabled: Boolean = true,
    autoplayLoading: Boolean = false,
    autoplayError: String = "",
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
    smartCrossfade: Boolean = false,
    onBestMixUpcoming: ((onProgress: (String) -> Unit, onComplete: () -> Unit) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PlayerTopHandle(onDismiss = onBack, modifier = dragHandle)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cover is framed rather than full-bleed. At this size the
            // blurred backdrop behind it reads as the room, and the square reads
            // as the record.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { onCoverBounds?.invoke(it.boundsInRoot()) }
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        (fadeIn(tween(500)) + scaleIn(initialScale = 0.94f, animationSpec = tween(500)))
                            .togetherWith(fadeOut(tween(400)) + scaleOut(targetScale = 1.04f, animationSpec = tween(400)))
                    },
                    label = "TabletArtworkTransition",
                    modifier = Modifier.fillMaxSize(),
                ) { currentTrack ->
                    val motion = currentTrack.animatedArtworkUrl.ifBlank { currentTrack.animatedArtworkVerticalUrl }
                    Box(Modifier.fillMaxSize()) {
                        if (animatedArtworkEnabled && motion.isNotBlank()) {
                            AnimatedArtworkVideo(motion, playback.isPlaying, Modifier.fillMaxSize())
                        } else {
                            RemoteArtwork(currentTrack.artworkUrl, currentTrack.title, Modifier.fillMaxSize())
                        }
                    }
                }
            }

            Spacer(Modifier.width(40.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                TrackInfoRow(
                    track = track,
                    liked = liked,
                    onLiked = onLiked,
                    onMore = onQueuePanel,
                    onShare = onShare,
                    isDownloaded = downloadedTrackIds.contains(track.id),
                    onDownload = onDownloadTrack?.let { action -> { action(track) } },
                    onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                    onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
                    onOpenAlbum = track.albumId.takeIf { it.isNotBlank() }
                        ?.let { id -> onOpenCollection?.let { open -> { open(id) } } },
                    onOpenArtist = track.artistId.takeIf { it.isNotBlank() }
                        ?.let { id -> onOpenCollection?.let { open -> { open(id) } } },
                )

                if (panel != PlayerPanel.NONE) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Same dissolve as the phone panel, so lines fade
                            // into the column rather than being cut by its edges.
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.08f to Color.Black,
                                        0.9f to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    ) {
                        if (panel == PlayerPanel.QUEUE) {
                            PlayerQueuePanel(
                                playback = playback,
                                editable = canControl,
                                onPlayIndex = onPlayQueueIndex,
                                onRemove = onRemoveQueueIndex,
                                onMove = onMoveQueueItem,
                                onClearUpcoming = onClearUpcoming,
                                onShuffleUpcoming = onShuffle,
                                autoplayEnabled = autoplayEnabled,
                                autoplayLoading = autoplayLoading,
                                autoplayError = autoplayError,
                                onAutoplayEnabled = onAutoplayEnabled,
                                smartCrossfade = smartCrossfade,
                                onBestMixUpcoming = onBestMixUpcoming,
                                sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                                sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                                onSleepTimer = onSleepTimer,
                            )
                        } else {
                            when (lyrics) {
                                is LoadState.Content -> LyricLines(
                                    lines = lyrics.value,
                                    positionMs = playback.positionMs,
                                    playing = playback.isPlaying,
                                    onSeek = onSeek,
                                    contentPadding = PaddingValues(vertical = 24.dp),
                                    accent = lyricAccent,
                                )

                                LoadState.Loading -> LyricsNotice("Finding lyrics…")
                                is LoadState.Empty -> LyricsNotice(lyrics.message)
                                is LoadState.Error -> LyricsNotice(lyrics.message)
                                LoadState.Idle -> LyricsNotice("Start a song to see its lyrics.")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                } else {
                    Spacer(Modifier.height(28.dp))
                }

                PlayerControlStack(
                    playback = playback,
                    targets = targets,
                    canControl = canControl,
                    localControls = localControls,
                    transition = transition,
                    mixProgress = mixProgress,
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    remoteVolume = remoteVolume,
                    lyricsActive = panel == PlayerPanel.LYRICS,
                    queueActive = panel == PlayerPanel.QUEUE,
                    onRemoteVolumeChange = onRemoteVolumeChange,
                    onSeek = onSeek,
                    onToggle = onToggle,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLyrics = onLyricsPanel,
                    onDevices = onDevices,
                    onQueue = onQueuePanel,
                    sleepTimerActive = sleepTimerRemainingSeconds > 0 || sleepTimerEndOfTrack,
                    onSleepTimer = onSleepTimer,
                )
            }
        }
    }
}
