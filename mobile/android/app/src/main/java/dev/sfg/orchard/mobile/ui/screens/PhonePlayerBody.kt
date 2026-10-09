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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.SmartCrossfadeBadge

/**
 * Phone layout: header, then the cover or whichever panel replaced it, then title and controls.
 * Panels only ever take the cover's slot, so the title and transport never move under the thumb.
 */
@Composable
internal fun PhonePlayerBody(
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
    lyricAccent: Color,
    /** Set when the backdrop already shows motion artwork, so no framed cover is drawn. */
    hasRichArtwork: Boolean,
    incomingTrack: Track?,
    outgoingTrack: Track,
    onCoverBounds: ((Rect) -> Unit)?,
    transition: TransitionMarker?,
    mixProgress: Float,
    canControl: Boolean,
    localControls: Boolean,
    liked: Boolean,
    panel: PlayerPanel,
    swipe: ArtworkSwipe,
    gesturesEnabled: Boolean,
    showBitrate: Boolean,
    bitrateKbps: Int,
    isQobuz: Boolean,
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
    autoplayEnabled: Boolean,
    autoplayLoading: Boolean,
    autoplayError: String,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
    smartCrossfade: Boolean,
    onBestMixUpcoming: ((onProgress: (String) -> Unit, onComplete: () -> Unit) -> Unit)?,
) {
    val queueOpen = panel == PlayerPanel.QUEUE
    val isDownloaded = downloadedTrackIds.contains(track.id)
    val onOpenAlbum = track.albumId.takeIf { it.isNotBlank() }
        ?.let { id -> onOpenCollection?.let { open -> { open(id) } } }
    val (previous, next) = playback.neighbours()
    val swipeEnabled = gesturesEnabled && canControl && panel == PlayerPanel.NONE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerHeader(
            track = track,
            playingFrom = playback.playingFromLabel(),
            onCollapse = onBack,
            modifier = modifier,
            onViewQueue = onQueuePanel,
            onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
            onShare = onShare,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            isDownloaded = isDownloaded,
            onDownload = onDownloadTrack?.let { action -> { action(track) } },
            onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Motion art has no framed cover to measure, so the slot's width is the skip distance.
                .onSizeChanged { if (hasRichArtwork) swipe.span = it.width.toFloat() }
                .artworkSwipe(
                    swipe = swipe,
                    enabled = swipeEnabled,
                    hasPrevious = previous != null,
                    hasNext = next != null,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
                // This box sits over the backdrop and takes its touches, so it owns double-tap too.
                .then(
                    if (gesturesEnabled && panel == PlayerPanel.NONE) {
                        Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onLiked() }) }
                    } else Modifier,
                )
                .then(if (queueOpen) Modifier.queueEdgeFade() else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            when {
                queueOpen -> PlayerQueuePanel(
                    playback = playback,
                    editable = canControl,
                    onPlayIndex = onPlayQueueIndex,
                    onRemove = onRemoveQueueIndex,
                    onMove = onMoveQueueItem,
                    onClearUpcoming = onClearUpcoming,
                    onShuffleUpcoming = onShuffle,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
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

                panel == PlayerPanel.LYRICS -> when (lyrics) {
                    is LoadState.Content -> LyricLines(
                        lines = lyrics.value,
                        playing = playback.isPlaying,
                        onSeek = onSeek,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                        accent = lyricAccent,
                    )

                    LoadState.Loading -> LyricsNotice("Finding lyrics…", isLoading = true)
                    is LoadState.Empty -> LyricsNotice(lyrics.message, icon = Icons.Rounded.MusicNote)
                    is LoadState.Error -> LyricsNotice(lyrics.message, icon = Icons.Rounded.Info)
                    LoadState.Idle -> LyricsNotice("Start a song to see its lyrics.", icon = Icons.Rounded.MusicNote)
                }

                // Motion art fills the backdrop, which follows the same swipe on its own.
                hasRichArtwork -> Unit

                else -> SwipeableCover(
                    swipe = swipe,
                    previous = previous,
                    next = next,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    NowPlayingArtworkCard(
                        track = track,
                        incomingTrack = incomingTrack,
                        outgoingTrack = outgoingTrack,
                        transitionProgress = mixProgress,
                        transitionStyle = transition?.style.orEmpty(),
                        onArtworkBounds = { onCoverBounds?.invoke(it) },
                        instantTrackChange = swipe.handingOff,
                    )
                }
            }
        }

        if (panel == PlayerPanel.NONE) {
            LyricPeek(
                lyrics = lyrics,
                onOpen = onLyricsPanel,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SmartCrossfadeBadge(
                visible = mixProgress in 0.001f..0.999f && transition != null,
                style = transition?.style.orEmpty(),
                incomingTrack = incomingTrack,
                progress = mixProgress,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            Spacer(Modifier.height(12.dp))
            TrackInfoRow(
                track = track,
                liked = liked,
                onLiked = onLiked,
                onMore = onQueuePanel,
                onShare = onShare,
                isDownloaded = isDownloaded,
                onDownload = onDownloadTrack?.let { action -> { action(track) } },
                onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                showMenu = false,
            )
            Spacer(Modifier.height(24.dp))

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
                queueActive = queueOpen,
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

/** Dissolves the queue into the header and title at either end. */
private fun Modifier.queueEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.08f to Color.Black,
                0.88f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
