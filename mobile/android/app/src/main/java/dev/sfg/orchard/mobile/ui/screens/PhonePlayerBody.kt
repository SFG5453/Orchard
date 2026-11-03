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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.OrchardGraph
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
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
    onSkipBack: () -> Unit,
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
    /** Where the controls start over short motion art, in the body's px; they spread below it. */
    artworkSeam: Float? = null,
    /** Reports the px above the controls at their natural height, for the art to grow into. */
    onArtworkRoom: (Float?) -> Unit = {},
    /** Non-null when the track has a music video to switch to. */
    onWatchVideo: (() -> Unit)? = null,
) {
    val queueOpen = panel == PlayerPanel.QUEUE
    var modesOpen by remember { mutableStateOf(false) }
    val isDownloaded = downloadedTrackIds.contains(track.id)
    val onOpenAlbum = track.albumId.takeIf { it.isNotBlank() }
        ?.let { id -> onOpenCollection?.let { open -> { open(id) } } }
    val (previous, next) = playback.neighbours()
    val swipeEnabled = gesturesEnabled && canControl && panel == PlayerPanel.NONE
    var bodyHeight by remember { mutableIntStateOf(0) }
    val bottomInset = WindowInsets.systemBars.getBottom(LocalDensity.current) +
        with(LocalDensity.current) { BODY_BOTTOM_PADDING.roundToPx() }
    val spreadTarget = artworkSeam
        ?.takeIf { hasRichArtwork && panel == PlayerPanel.NONE && bodyHeight > 0 }
        ?.let { bodyHeight - it - bottomInset }
        ?: 0f
    val spreadHeight by animateFloatAsState(spreadTarget, tween(SPREAD_MS), label = "ControlsSpread")
    val spreading = spreadHeight > 0f
    // A synced lyric line takes all the slack between title and scrubber; otherwise every gap shares it.
    val underTitle by OrchardGraph.from(LocalContext.current).settings.settings.collectAsStateWithLifecycle()
    val peekLyrics = if (underTitle.lyricsUnderTitle) lyrics else LoadState.Idle
    val peekFills = spreading && panel == PlayerPanel.NONE &&
        (peekLyrics as? LoadState.Content)?.value?.any { it.startMs != null } == true
    // Measured with no panel open, so opening lyrics or the queue never resizes the art.
    var controlsHeight by remember { mutableIntStateOf(0) }
    val artworkRoom = if (hasRichArtwork && bodyHeight > 0 && controlsHeight > 0) {
        (bodyHeight - controlsHeight - bottomInset).toFloat()
    } else null
    LaunchedEffect(artworkRoom) { onArtworkRoom(artworkRoom) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { bodyHeight = it.height }
                .systemBarsPadding()
                .padding(bottom = BODY_BOTTOM_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (panel == PlayerPanel.NONE) {
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
                    onWatchVideo = onWatchVideo,
                )
            } else {
                PlayerPanelHeader(
                    track = track,
                    liked = liked,
                    showLike = panel == PlayerPanel.LYRICS,
                    onCollapse = onBack,
                    handleModifier = modifier,
                    onViewQueue = onQueuePanel,
                    onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
                    onShare = onShare,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                    isDownloaded = isDownloaded,
                    onDownload = onDownloadTrack?.let { action -> { action(track) } },
                    onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                    onWatchVideo = onWatchVideo,
                    onLiked = onLiked,
                )
            }

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
                        onPrevious = onSkipBack,
                        onNext = onNext,
                    )
                    // This box sits over the backdrop and takes its touches, so it owns double-tap too.
                    .then(
                        if (gesturesEnabled && panel == PlayerPanel.NONE) {
                            Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onLiked() }) }
                        } else Modifier,
                    )
                    .holdToSwitch(onWatchVideo != null && panel == PlayerPanel.NONE) { onWatchVideo?.invoke() }
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
                        showModes = false,
                    )

                    panel == PlayerPanel.LYRICS -> PlayerLyrics(
                        track = track, lyrics = lyrics, playing = playback.isPlaying,
                        onSeek = onSeek, accent = lyricAccent,
                    )

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

            // Rows share whatever the art leaves equally; never less than their own height.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .spreadTo(target = { spreadHeight }, onNatural = { if (panel == PlayerPanel.NONE) controlsHeight = it })
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val gap: @Composable (Dp) -> Unit = { min ->
                    Spacer(Modifier.height(min))
                    if (spreading && !peekFills) Spacer(Modifier.weight(1f))
                }
                if (spreading && !peekFills) Spacer(Modifier.weight(1f))
                SmartCrossfadeBadge(
                    visible = mixProgress in 0.001f..0.999f && transition != null,
                    style = transition?.style.orEmpty(),
                    incomingTrack = incomingTrack,
                    progress = mixProgress,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                if (panel == PlayerPanel.NONE) {
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
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                if (peekFills) {
                    LyricPeek(lyrics = peekLyrics, onOpen = onLyricsPanel, modifier = Modifier.weight(1f))
                } else {
                    if (panel == PlayerPanel.NONE) LyricPeek(lyrics = peekLyrics, onOpen = onLyricsPanel)
                    gap(24.dp)
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
                    gap = gap,
                    bottomBar = {
                        PlayerDock(
                            panel = panel,
                            source = track.playbackSource,
                            upcomingCount = playback.upcoming.size,
                            shuffle = playback.shuffle,
                            repeatMode = playback.repeatMode,
                            sleepTimerActive = sleepTimerRemainingSeconds > 0 || sleepTimerEndOfTrack,
                            trayOpen = modesOpen,
                            onLyrics = onLyricsPanel,
                            onQueue = onQueuePanel,
                            onToggleTray = { modesOpen = !modesOpen },
                        )
                    },
                )
            }
        }

        if (modesOpen) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { modesOpen = false } })
        }
        AnimatedVisibility(
            visible = modesOpen,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = BODY_BOTTOM_PADDING + DockHeight + 12.dp),
        ) {
            PlayerModesTray(
                modifier = Modifier,
                targets = targets,
                shuffle = playback.shuffle,
                repeatMode = playback.repeatMode,
                autoplayEnabled = autoplayEnabled,
                sleepTimerActive = sleepTimerRemainingSeconds > 0 || sleepTimerEndOfTrack,
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

/** Takes at least [target] px, measuring the content's own height first so it never clips. */
private fun Modifier.spreadTo(target: () -> Float, onNatural: (Int) -> Unit): Modifier = layout { measurable, constraints ->
    val wanted = target()
    val natural = measurable.minIntrinsicHeight(constraints.maxWidth)
    onNatural(natural)
    val placeable = if (wanted <= 0f) {
        measurable.measure(constraints)
    } else {
        val height = max(wanted.roundToInt(), natural).coerceIn(constraints.minHeight, constraints.maxHeight)
        measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    }
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private val BODY_BOTTOM_PADDING = 20.dp
private const val SPREAD_MS = 420

/** Dissolves the queue into the header and title at either end. */
internal fun Modifier.queueEdgeFade(): Modifier = this
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
