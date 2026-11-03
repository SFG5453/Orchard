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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.setValue
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

/** Wide player: a centred column that slides left to share the screen with lyrics or the queue. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    onWatchVideo: (() -> Unit)? = null,
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
    /** Book foldable: the pane starts at the crease and the side art ends there. */
    crease: Boolean = false,
) {
    val activeProgress = mixProgress ?: 0f
    val incomingTrack = remember(playback.queue, transition?.incomingTrackId) {
        val id = transition?.incomingTrackId
        if (id.isNullOrBlank()) null else playback.queue.firstOrNull { it.id == id }
    }
    val outgoingTrack = remember(playback.queue, transition?.trackId, track) {
        val id = transition?.trackId
        if (id.isNullOrBlank()) track else playback.queue.firstOrNull { it.id == id } ?: track
    }
    // 0 centres the player column; 1 moves it left to make room for the pane.
    val split by animateFloatAsState(
        targetValue = if (panel != PlayerPanel.NONE) 1f else 0f,
        animationSpec = tween(560, easing = FastOutSlowInEasing),
        label = "TabletSplit",
    )

    // Controls fade after a few idle seconds while playing; any touch brings them back.
    var touches by remember { mutableIntStateOf(0) }
    var chromeShown by remember { mutableStateOf(true) }
    val holdChrome = !playback.isPlaying || panel == PlayerPanel.QUEUE
    LaunchedEffect(holdChrome) {
        if (holdChrome) {
            chromeShown = true
            return@LaunchedEffect
        }
        snapshotFlow { touches }.collectLatest {
            chromeShown = true
            delay(IdleHideMs)
            chromeShown = false
        }
    }
    val chrome by animateFloatAsState(
        targetValue = if (chromeShown) 1f else 0f,
        animationSpec = tween(if (chromeShown) 260 else 900, easing = FastOutSlowInEasing),
        label = "TabletChrome",
    )
    val idle = 1f - chrome
    // An idle lyrics pane widens and the cover steps back; a crease pins both halves.
    val focus = if (panel == PlayerPanel.LYRICS && !crease) idle else 0f

    // With a pane open, portrait loops take the whole player side; otherwise the square cover shows.
    val stageUrl = track.animatedArtworkVerticalUrl.takeIf {
        animatedArtworkEnabled && it.isNotBlank() && panel != PlayerPanel.NONE && activeProgress !in 0.001f..0.999f
    }.orEmpty()
    val staged = stageUrl.isNotEmpty()
    val stageAlpha by animateFloatAsState(if (staged) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "TabletStage")
    var stageAspect by remember(stageUrl) { mutableStateOf(9f / 16f) }

    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    touches++
                }
            }
        },
    ) {
        if (stageAlpha > 0.001f) {
            TabletStage(
                url = stageUrl,
                stillUrl = track.artworkUrl,
                playing = playback.isPlaying,
                aspect = stageAspect,
                onAspect = { stageAspect = it.coerceIn(0.5f, 1f) },
                split = split,
                focus = focus,
                crease = crease,
                modifier = Modifier.graphicsLayer { alpha = stageAlpha },
            )
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            PlayerTopHandle(onDismiss = onBack, modifier = modifier.graphicsLayer { alpha = chrome })
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val margin = maxOf(40.dp, maxWidth * 0.05f)
                val solo = maxWidth - margin * 2
                val left = maxWidth * (0.46f - 0.12f * focus) - margin
                val column = minOf(PlayerColumnMax, solo + (left - solo) * split)
                val center = (maxWidth - column) / 2
                val columnX = center + (margin + (left - column) / 2 - center) * split
                val paneX = maxWidth * (0.5f - 0.12f * focus)
                // On a fold the cover keeps its size through autohide; resizing it there looks jumpy.
                val grow = if (crease) 0f else idle
                val coverMax = PlayerCoverMax + 120.dp * grow * (1f - split) - 140.dp * focus * split
                val vertical = minOf(40.dp, maxHeight * 0.06f)

                Column(
                    modifier = Modifier.offset(x = columnX).width(column).fillMaxHeight().padding(vertical = vertical),
                    verticalArrangement = if (staged) Arrangement.Bottom else Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Cover takes whatever height the controls leave, up to its cap.
                    if (!staged) Box(
                        modifier = Modifier.weight(1f, fill = false).requiredWidthIn(max = coverMax).aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        NowPlayingArtworkCard(
                            track = track,
                            incomingTrack = incomingTrack,
                            outgoingTrack = outgoingTrack,
                            transitionProgress = activeProgress,
                            transitionStyle = transition?.style.orEmpty(),
                            animatedArtworkEnabled = animatedArtworkEnabled,
                            isPlaying = playback.isPlaying,
                            onArtworkBounds = onCoverBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (!staged) Spacer(Modifier.height(24.dp))
                    ShrinkToFit {
                        Column(Modifier.fillMaxWidth()) {
                            dev.sfg.orchard.mobile.ui.components.SmartCrossfadeBadge(
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
                                onWatchVideo = onWatchVideo,
                                glass = false,
                            )
                            Spacer(Modifier.height(16.dp))
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
                                bottomBar = {
                                    CollapseWithChrome(chrome, keepHeight = crease) {
                                        PlayerBottomDestinations(
                                            targets = targets,
                                            upcomingCount = playback.upcoming.size,
                                            onLyrics = onLyricsPanel,
                                            lyricsActive = panel == PlayerPanel.LYRICS,
                                            onDevices = onDevices,
                                            onQueue = onQueuePanel,
                                            queueActive = panel == PlayerPanel.QUEUE,
                                        )
                                    }
                                },
                                transport = {
                                    CollapseWithChrome(chrome, keepHeight = crease) {
                                        TabletTransportControls(
                                            isPlaying = playback.isPlaying,
                                            shuffle = playback.shuffle,
                                            repeatMode = playback.repeatMode,
                                            enabled = canControl,
                                            onToggle = onToggle,
                                            onPrevious = onPrevious,
                                            onNext = onNext,
                                            onShuffle = onShuffle,
                                            onRepeat = onRepeat,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                if (split > 0.001f) {
                    Box(
                        modifier = Modifier
                            .offset(x = paneX + 70.dp * (1f - split))
                            .width(maxWidth - paneX - margin)
                            .fillMaxHeight()
                            .padding(vertical = vertical)
                            .padding(start = if (crease) CreaseGap else 0.dp)
                            .graphicsLayer { alpha = split }
                            .then(if (panel == PlayerPanel.QUEUE) Modifier.fadedEdges() else Modifier),
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
                        } else {
                            PlayerLyrics(
                                track = track, lyrics = lyrics, playing = playback.isPlaying,
                                onSeek = onSeek, contentPadding = PaddingValues(vertical = 24.dp), accent = lyricAccent,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PlayerColumnMax = 560.dp
private val PlayerCoverMax = 520.dp
private val CreaseGap = 28.dp

/** Dissolves the top and bottom edges of a scrolling panel. */
private fun Modifier.fadedEdges(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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
        }

/** Scales its content down to the height on offer when it would overflow, as the desktop controls do. */
@Composable
private fun ShrinkToFit(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val scale = if (constraints.hasBoundedHeight) {
            minOf(1f, constraints.maxHeight / placeable.height.toFloat().coerceAtLeast(1f))
        } else {
            1f
        }
        layout(placeable.width, (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}

/**
 * Fades its content and collapses its height as [chrome] falls to 0, so hidden controls take no touches.
 * With [keepHeight] the space stays and hidden controls swallow touches instead, so nothing above moves.
 */
@Composable
private fun CollapseWithChrome(chrome: Float, keepHeight: Boolean = false, content: @Composable () -> Unit) {
    val hidden = keepHeight && chrome < 0.01f
    val block = if (hidden) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    } else {
        Modifier
    }
    Layout(content = { Box(block) { content() } }) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val height = if (keepHeight) placeable.height else (placeable.height * chrome).roundToInt()
        layout(placeable.width, height) {
            placeable.placeWithLayer(0, 0) { alpha = chrome }
        }
    }
}

private const val IdleHideMs = 3000L
