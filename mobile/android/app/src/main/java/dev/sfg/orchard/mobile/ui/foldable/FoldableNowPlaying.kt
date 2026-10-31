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

package dev.sfg.orchard.mobile.ui.foldable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.SmartCrossfadeBadge
import dev.sfg.orchard.mobile.ui.screens.NowPlayingArtworkCard
import dev.sfg.orchard.mobile.ui.screens.PlayerControlStack
import dev.sfg.orchard.mobile.ui.screens.PlayerPanel
import dev.sfg.orchard.mobile.ui.screens.PlayerTopHandle
import dev.sfg.orchard.mobile.ui.screens.TrackInfoRow

/**
 * Dedicated Now Playing body for foldable devices unfolded in their large inner display.
 *
 * Delivers a much bigger album artwork centerpiece in Hero Mode (when no panel is open),
 * and an adaptive side-by-side dual pane in Split Mode (when lyrics or queue are active).
 */
@Composable
fun FoldableNowPlayingBody(
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
    lyricAccent: Color,
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
    isQobuz: Boolean = false,
    remoteVolume: Float,
    modifier: Modifier,
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
    onOpenArtist: (() -> Unit)?,
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
        PlayerTopHandle(onDismiss = onBack, modifier = modifier)

        AnimatedContent(
            targetState = panel != PlayerPanel.NONE,
            transitionSpec = {
                (fadeIn(tween(350)) + scaleIn(initialScale = 0.98f, animationSpec = tween(350)))
                    .togetherWith(fadeOut(tween(250)) + scaleOut(targetScale = 1.02f, animationSpec = tween(250)))
            },
            label = "FoldableNowPlayingModeTransition",
            modifier = Modifier.fillMaxSize(),
        ) { isSplitMode ->
            if (!isSplitMode) {
                // HERO MODE: Massive centered artwork centerpiece + ergonomic console
                FoldableNowPlayingHero(
                    track = track,
                    playback = playback,
                    targets = targets,
                    lyricAccent = lyricAccent,
                    onCoverBounds = onCoverBounds,
                    transition = transition,
                    mixProgress = mixProgress,
                    canControl = canControl,
                    localControls = localControls,
                    liked = liked,
                    panel = panel,
                    animatedArtworkEnabled = animatedArtworkEnabled,
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    isQobuz = isQobuz,
                    remoteVolume = remoteVolume,
                    onRemoteVolumeChange = onRemoteVolumeChange,
                    onSeek = onSeek,
                    onToggle = onToggle,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLiked = onLiked,
                    onDevices = onDevices,
                    downloadedTrackIds = downloadedTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onAddToPlaylist = onAddToPlaylist,
                    onShare = onShare,
                    onOpenCollection = onOpenCollection,
                    onOpenArtist = onOpenArtist,
                    onLyricsPanel = onLyricsPanel,
                    onQueuePanel = onQueuePanel,
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                    onSleepTimer = onSleepTimer,
                )
            } else {
                // SPLIT MODE: Side-by-side prominent artwork + full-height Lyrics/Queue panel
                FoldableNowPlayingSplit(
                    track = track,
                    playback = playback,
                    targets = targets,
                    lyrics = lyrics,
                    lyricAccent = lyricAccent,
                    onCoverBounds = onCoverBounds,
                    transition = transition,
                    mixProgress = mixProgress,
                    canControl = canControl,
                    localControls = localControls,
                    liked = liked,
                    panel = panel,
                    animatedArtworkEnabled = animatedArtworkEnabled,
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    isQobuz = isQobuz,
                    remoteVolume = remoteVolume,
                    onRemoteVolumeChange = onRemoteVolumeChange,
                    onSeek = onSeek,
                    onToggle = onToggle,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLiked = onLiked,
                    onDevices = onDevices,
                    onPlayQueueIndex = onPlayQueueIndex,
                    onRemoveQueueIndex = onRemoveQueueIndex,
                    onMoveQueueItem = onMoveQueueItem,
                    onClearUpcoming = onClearUpcoming,
                    downloadedTrackIds = downloadedTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onAddToPlaylist = onAddToPlaylist,
                    onShare = onShare,
                    onOpenCollection = onOpenCollection,
                    onOpenArtist = onOpenArtist,
                    onLyricsPanel = onLyricsPanel,
                    onQueuePanel = onQueuePanel,
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                    onSleepTimer = onSleepTimer,
                    autoplayEnabled = autoplayEnabled,
                    autoplayLoading = autoplayLoading,
                    autoplayError = autoplayError,
                    onAutoplayEnabled = onAutoplayEnabled,
                    smartCrossfade = smartCrossfade,
                    onBestMixUpcoming = onBestMixUpcoming,
                )
            }
        }
    }
}

/** Hero presentation featuring a big ~480dp album artwork centerpiece and centered controls console. */
@Composable
private fun FoldableNowPlayingHero(
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyricAccent: Color,
    onCoverBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)?,
    transition: TransitionMarker?,
    mixProgress: Float?,
    canControl: Boolean,
    localControls: Boolean,
    liked: Boolean,
    panel: PlayerPanel,
    animatedArtworkEnabled: Boolean,
    showBitrate: Boolean,
    bitrateKbps: Int,
    isQobuz: Boolean,
    remoteVolume: Float,
    onRemoteVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLiked: () -> Unit,
    onDevices: () -> Unit,
    downloadedTrackIds: Set<String>,
    onDownloadTrack: ((Track) -> Unit)?,
    onRemoveDownloadTrack: ((String) -> Unit)?,
    onAddToPlaylist: ((Track) -> Unit)?,
    onShare: (() -> Unit)?,
    onOpenCollection: ((String) -> Unit)?,
    onOpenArtist: (() -> Unit)?,
    onLyricsPanel: () -> Unit,
    onQueuePanel: () -> Unit,
    sleepTimerRemainingSeconds: Long,
    sleepTimerEndOfTrack: Boolean,
    onSleepTimer: () -> Unit,
) {
    val motion = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
    val hasRichArtwork = animatedArtworkEnabled && motion.isNotBlank()
    val activeProgress = mixProgress ?: 0f
    val incomingTrack = remember(playback.queue, transition?.incomingTrackId) {
        val id = transition?.incomingTrackId
        if (id.isNullOrBlank()) null else playback.queue.firstOrNull { it.id == id }
    }
    val outgoingTrack = remember(playback.queue, transition?.trackId, track) {
        val id = transition?.trackId
        if (id.isNullOrBlank()) track else playback.queue.firstOrNull { it.id == id } ?: track
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasRichArtwork) {
            // Grand Artwork Centerpiece: dynamically up to 480x480dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // Ambient aura behind the card tinted from artwork palette
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.92f)
                        .aspectRatio(1f)
                        .widthIn(max = 500.dp)
                        .heightIn(max = 500.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    lyricAccent.copy(alpha = 0.36f),
                                    Color.Transparent,
                                ),
                            ),
                            shape = RoundedCornerShape(32.dp),
                        )
                )

                // Primary Artwork Card
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.88f)
                        .aspectRatio(1f)
                        .widthIn(max = 480.dp)
                        .heightIn(max = 480.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NowPlayingArtworkCard(
                        track = track,
                        incomingTrack = incomingTrack,
                        outgoingTrack = outgoingTrack,
                        transitionProgress = activeProgress,
                        transitionStyle = transition?.style.orEmpty(),
                        onArtworkBounds = onCoverBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            // Full-bleed animated art is playing in FullBleedPlayerBackdrop
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))

        // Center-aligned controls console with generous width
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SmartCrossfadeBadge(
                visible = activeProgress in 0.001f..0.999f && transition != null,
                style = transition?.style.orEmpty(),
                incomingTrack = incomingTrack,
                progress = activeProgress,
                modifier = Modifier.padding(bottom = 12.dp),
            )
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
                onOpenArtist = onOpenArtist,
            )

            Spacer(Modifier.height(12.dp))

            PlayerControlStack(
                playback = playback,
                targets = targets,
                canControl = canControl,
                localControls = localControls,
                transition = transition,
                mixProgress = mixProgress,
                showBitrate = showBitrate,
                bitrateKbps = bitrateKbps,
                isQobuz = isQobuz,
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
