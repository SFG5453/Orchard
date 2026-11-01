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
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.SmartCrossfadeBadge
import dev.sfg.orchard.mobile.ui.foldable.TabletopHinge

/**
 * Flip phone folded to about 90 degrees: the upright half shows the cover, lyrics or queue,
 * and the flat half carries title and transport at their natural size.
 */
@Composable
internal fun TabletopPlayerBody(
    hinge: TabletopHinge,
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
    lyricAccent: Color,
    incomingTrack: Track?,
    outgoingTrack: Track,
    onCoverBounds: ((Rect) -> Unit)?,
    transition: TransitionMarker?,
    mixProgress: Float,
    canControl: Boolean,
    localControls: Boolean,
    liked: Boolean,
    panel: PlayerPanel,
    animatedArtworkEnabled: Boolean,
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
    onWatchVideo: (() -> Unit)? = null,
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
    val density = LocalDensity.current
    // Hinge bounds are in window px; the player may not start at the window's top edge.
    var originY by remember { mutableFloatStateOf(0f) }
    val upperHeight = with(density) { (hinge.top - originY).coerceAtLeast(0f).toDp() }
    val lowerTop = with(density) { (hinge.bottom - originY).coerceAtLeast(0f).toDp() }
    var modesOpen by remember { mutableStateOf(false) }
    val sleepActive = sleepTimerRemainingSeconds > 0 || sleepTimerEndOfTrack

    // The backdrop draws tall art itself; the card is only for covers that keep a frame.
    val tallArt = hasTabletopTallArt(track, animatedArtworkEnabled)

    Box(Modifier.fillMaxSize().onGloballyPositioned { originY = it.positionInWindow().y }) {
        Column(
            Modifier.fillMaxWidth().height(upperHeight).windowInsetsPadding(WindowInsets.statusBars),
        ) {
            PlayerTopHandle(onDismiss = onBack, modifier = modifier)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (panel == PlayerPanel.QUEUE) Modifier.queueEdgeFade() else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when (panel) {
                    PlayerPanel.QUEUE -> PlayerQueuePanel(
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
                        showModes = false,
                    )
                    PlayerPanel.LYRICS -> PlayerLyrics(
                        track = track, lyrics = lyrics, playing = playback.isPlaying, onSeek = onSeek,
                        accent = lyricAccent, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    )
                    PlayerPanel.NONE -> if (!tallArt) NowPlayingArtworkCard(
                        track = track,
                        incomingTrack = incomingTrack,
                        outgoingTrack = outgoingTrack,
                        transitionProgress = mixProgress,
                        transitionStyle = transition?.style.orEmpty(),
                        animatedArtworkEnabled = animatedArtworkEnabled,
                        isPlaying = playback.isPlaying,
                        onArtworkBounds = onCoverBounds,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .fillMaxHeight()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true),
                    )
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .offset(y = lowerTop)
                .padding(bottom = lowerTop)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            // Grow the stack a little toward the flat half's height, then share what is left between rows.
            val base = LocalDensity.current
            var naturalPx by remember { mutableIntStateOf(0) }
            val scale = if (naturalPx > 0) {
                (constraints.maxHeight * LOWER_FILL / naturalPx).coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE)
            } else 1f
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .fillToShare(LOWER_FILL) { naturalPx = (it / scale).roundToInt() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(EDGE_WEIGHT))
                    SmartCrossfadeBadge(
                        visible = mixProgress in 0.001f..0.999f && transition != null,
                        style = transition?.style.orEmpty(),
                        incomingTrack = incomingTrack,
                        progress = mixProgress,
                        modifier = Modifier.padding(bottom = 8.dp),
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
                        onWatchVideo = onWatchVideo,
                    )
                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.weight(1f))
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
                        sleepTimerActive = sleepActive,
                        onSleepTimer = onSleepTimer,
                        gap = {
                            Spacer(Modifier.height(it))
                            Spacer(Modifier.weight(1f))
                        },
                        bottomBar = {
                            PlayerDock(
                                panel = panel,
                                source = track.playbackSource,
                                upcomingCount = playback.upcoming.size,
                                shuffle = playback.shuffle,
                                repeatMode = playback.repeatMode,
                                sleepTimerActive = sleepActive,
                                trayOpen = modesOpen,
                                onLyrics = onLyricsPanel,
                                onQueue = onQueuePanel,
                                onToggleTray = { modesOpen = !modesOpen },
                            )
                        },
                    )
                    Spacer(Modifier.weight(EDGE_WEIGHT))
                }
            }

            if (modesOpen) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { modesOpen = false } })
            }
            AnimatedVisibility(
                visible = modesOpen,
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (DockHeight + 12.dp) * scale),
            ) {
                PlayerModesTray(
                    modifier = Modifier,
                    targets = targets,
                    shuffle = playback.shuffle,
                    repeatMode = playback.repeatMode,
                    autoplayEnabled = autoplayEnabled,
                    sleepTimerActive = sleepActive,
                    canControl = canControl,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onAutoplayEnabled = onAutoplayEnabled,
                    onSleepTimer = {
                        modesOpen = false
                        onSleepTimer()
                    },
                    onDevices = {
                        modesOpen = false
                        onDevices()
                    },
                )
            }
        }
    }
}

/** Only Apple's tall motion cover fills the upright half; anything else keeps the framed square. */
internal fun hasTabletopTallArt(track: Track, animatedArtworkEnabled: Boolean): Boolean =
    animatedArtworkEnabled && track.animatedArtworkVerticalUrl.isNotBlank()

/**
 * Fills [fraction] of the incoming height, never less than the content's own height.
 * Reports the natural height in px so the caller can size the content toward the room.
 */
private fun Modifier.fillToShare(fraction: Float, onNatural: (Int) -> Unit): Modifier = layout { measurable, constraints ->
    val natural = measurable.minIntrinsicHeight(constraints.maxWidth)
    onNatural(natural)
    val height = max(natural, (constraints.maxHeight * fraction).roundToInt()).coerceAtMost(constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Share of the flat half the controls span; the rest is margin. */
private const val LOWER_FILL = 0.94f
private const val MIN_CONTROL_SCALE = 0.75f
private const val MAX_CONTROL_SCALE = 1.15f

/** Margins above the title and below the dock, relative to a gap between rows. */
private const val EDGE_WEIGHT = 0.5f
