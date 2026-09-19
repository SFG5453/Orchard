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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.screens.LyricLines
import dev.sfg.orchard.mobile.ui.screens.LyricsNotice
import dev.sfg.orchard.mobile.ui.screens.PlayerControlStack
import dev.sfg.orchard.mobile.ui.screens.PlayerPanel
import dev.sfg.orchard.mobile.ui.screens.PlayerQueuePanel
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
        PlayerTopHandle(onDismiss = onBack, modifier = dragHandle)

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
                        .heightIn(max = 480.dp)
                        .onGloballyPositioned { onCoverBounds?.invoke(it.boundsInRoot()) }
                        .shadow(
                            elevation = 28.dp,
                            shape = RoundedCornerShape(26.dp),
                            spotColor = Color.Black.copy(alpha = 0.65f),
                            ambientColor = lyricAccent.copy(alpha = 0.32f),
                        )
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF141618)),
                ) {
                    AnimatedContent(
                        targetState = track,
                        transitionSpec = {
                            (fadeIn(tween(450)) + scaleIn(initialScale = 0.94f, animationSpec = tween(450)))
                                .togetherWith(fadeOut(tween(350)) + scaleOut(targetScale = 1.04f, animationSpec = tween(350)))
                        },
                        label = "FoldableArtworkHeroTransition",
                        modifier = Modifier.fillMaxSize(),
                    ) { currentTrack ->
                        RemoteArtwork(currentTrack.artworkUrl, currentTrack.title, Modifier.fillMaxSize())
                    }
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

/** Dual-pane split presentation showing prominent artwork on the left and lyrics/queue on the right. */
@Composable
private fun FoldableNowPlayingSplit(
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
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
    val motion = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
    val hasRichArtwork = animatedArtworkEnabled && motion.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Column: Sizable artwork + controls
        Column(
            modifier = Modifier
                .weight(1.05f)
                .fillMaxHeight()
                .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!hasRichArtwork) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(0.92f)
                            .aspectRatio(1f)
                            .widthIn(max = 380.dp)
                            .heightIn(max = 380.dp)
                            .onGloballyPositioned { onCoverBounds?.invoke(it.boundsInRoot()) }
                            .shadow(
                                elevation = 20.dp,
                                shape = RoundedCornerShape(22.dp),
                                spotColor = Color.Black.copy(alpha = 0.55f),
                                ambientColor = lyricAccent.copy(alpha = 0.25f),
                            )
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF141618)),
                    ) {
                        AnimatedContent(
                            targetState = track,
                            transitionSpec = {
                                (fadeIn(tween(400)) + scaleIn(initialScale = 0.94f, animationSpec = tween(400)))
                                    .togetherWith(fadeOut(tween(300)) + scaleOut(targetScale = 1.04f, animationSpec = tween(300)))
                            },
                            label = "FoldableArtworkSplitTransition",
                            modifier = Modifier.fillMaxSize(),
                        ) { currentTrack ->
                            RemoteArtwork(currentTrack.artworkUrl, currentTrack.title, Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                // Full bleed motion artwork in background
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

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

        // Right Column: Full-bleed Lyrics or Queue
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF0F1011))
                .background(lyricAccent.copy(alpha = 0.15f)),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
                // Header Bar: Quick tabs between Lyrics & Queue, plus Close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FoldablePanelTab(
                            title = "Lyrics",
                            icon = Icons.Rounded.MusicNote,
                            selected = panel == PlayerPanel.LYRICS,
                            accent = lyricAccent,
                            onClick = { if (panel != PlayerPanel.LYRICS) onLyricsPanel() },
                        )
                        FoldablePanelTab(
                            title = "Queue",
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            selected = panel == PlayerPanel.QUEUE,
                            badge = playback.upcoming.size.takeIf { it > 0 }?.toString(),
                            accent = lyricAccent,
                            onClick = { if (panel != PlayerPanel.QUEUE) onQueuePanel() },
                        )
                    }

                    IconButton(
                        onClick = {
                            if (panel == PlayerPanel.LYRICS) onLyricsPanel()
                            else if (panel == PlayerPanel.QUEUE) onQueuePanel()
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close panel",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Panel Body: Synced Lyrics or Interactive Queue
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (panel == PlayerPanel.QUEUE) {
                                Modifier
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                0f to Color.Transparent,
                                                0.06f to Color.Black,
                                                0.92f to Color.Black,
                                                1f to Color.Transparent,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                            } else Modifier
                        ),
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
                                contentPadding = PaddingValues(vertical = 16.dp),
                                accent = lyricAccent,
                            )
                            LoadState.Loading -> LyricsNotice("Finding lyrics…", isLoading = true)
                            is LoadState.Empty -> LyricsNotice(lyrics.message, icon = Icons.Rounded.MusicNote)
                            is LoadState.Error -> LyricsNotice(lyrics.message, icon = Icons.Rounded.Info)
                            LoadState.Idle -> LyricsNotice("Start a song to see its lyrics.", icon = Icons.Rounded.MusicNote)
                        }
                    }
                }
            }
        }
    }
}

/** Interactive selection pill for switching between Lyrics and Queue in Split Mode. */
@Composable
private fun FoldablePanelTab(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    badge: String? = null,
    accent: Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f)
    val fg = if (selected) Color.White else Color.White.copy(alpha = 0.65f)
    val border = if (selected) accent.copy(alpha = 0.55f) else Color.Transparent

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
            color = fg,
        )
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) accent else Color.White.copy(alpha = 0.25f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}
