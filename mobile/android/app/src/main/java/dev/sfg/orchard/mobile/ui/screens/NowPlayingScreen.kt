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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * State-of-the-art Now Playing experience featuring a full-bleed artwork backdrop, marquee typography, interactive scrubber,
 * direct-access transport controls, and output routing.
 */
@Composable
fun NowPlayingScreen(
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
    animatedArtworkEnabled: Boolean,
    liked: Boolean,
    protocolVersion: Int = 1,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker? = null,
    showBitrate: Boolean = false,
    bitrateKbps: Int = 0,
    remoteVolume: Float = 1f,
    onRemoteVolumeChange: (Float) -> Unit = {},
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
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
    onShare: (() -> Unit)? = null,
    onOpenCollection: ((String) -> Unit)? = null,
) {
    // Lyrics and the queue are modes of the player, not destinations, so their state lives here.
    // Only one can hold the panel at a time.
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    val lyricsOpen = panel == PlayerPanel.LYRICS
    val queueOpen = panel == PlayerPanel.QUEUE
    val onQueue = { panel = if (queueOpen) PlayerPanel.NONE else PlayerPanel.QUEUE }
    // The queue used to be its own page, so back must still close it rather than the player.
    BackHandler(enabled = panel != PlayerPanel.NONE) { panel = PlayerPanel.NONE }
    val track = playback.currentTrack
    if (track == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CanopyColors.Chrome)
                .padding(16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ExpandMore, "Close player", tint = Color.White)
            }
            MessagePanel("Nothing playing", "Choose a song from Home, Search, or Library.")
        }
        return
    }

    val localControls = targets.selected is PlaybackTarget.LocalPhone
    val canControl = localControls || protocolVersion >= 2

    // Two panes need room for a square cover and a readable column beside it.
    // Below this a tablet in portrait, or a large phone in landscape, is better
    // served by the stacked layout it already has.
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 840

    // Swipe-down-to-dismiss: the whole player tracks the finger, then either commits to the
    // dismiss or springs back. Upward drag is clamped so the sheet cannot be pulled past its top.
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissDistance = with(density) { 160.dp.toPx() }
    val dismissVelocity = with(density) { 800.dp.toPx() }

    val dragHandle = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
        },
        onDragStopped = { velocity ->
            if (dragOffset.value > dismissDistance || velocity > dismissVelocity) {
                onBack()
                dragOffset.snapTo(0f)
            } else {
                dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .background(CanopyColors.PlayerBackdrop),
    ) {
        // Full-bleed artwork background. Lyrics push it out of focus rather than
        // replacing it, so the song's colour still carries the screen.
        // The phone puts its controls over the foot of the cover, where the
        // gradient already protects them, and only blurs when a panel opens.
        // The tablet's right column sits over the middle of the image, so the
        // backdrop stays out of focus there the whole time.
        val backdropBlur by animateDpAsState(
            targetValue = when {
                wideLayout -> 44.dp
                panel != PlayerPanel.NONE -> 34.dp
                else -> 0.dp
            },
            animationSpec = tween(420),
            label = "LyricsBackdropBlur",
        )
        FullBleedPlayerBackdrop(
            track = track,
            isPlaying = playback.isPlaying,
            animatedArtworkEnabled = animatedArtworkEnabled,
            transitionProgress = dev.sfg.orchard.mobile.ui.components.transitionProgress(playback, transition),
            modifier = Modifier.blur(backdropBlur),
        )
        // Blur is a no-op below API 31, so darken as well to keep lyrics legible everywhere.
        if (wideLayout || panel != PlayerPanel.NONE) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (wideLayout) 0.42f else 0.28f)))
        }

        // A tablet has room to stop trading one thing for another: the cover
        // keeps its own frame on the left while lyrics or the queue occupy the
        // right, instead of displacing the artwork the way the phone must.
        if (wideLayout) {
            TabletPlayerBody(
                track = track,
                playback = playback,
                targets = targets,
                lyrics = lyrics,
                animatedArtworkEnabled = animatedArtworkEnabled,
                liked = liked,
                panel = panel,
                canControl = canControl,
                localControls = localControls,
                transition = transition,
                showBitrate = showBitrate,
                bitrateKbps = bitrateKbps,
                remoteVolume = remoteVolume,
                dragHandle = dragHandle,
                onRemoteVolumeChange = onRemoteVolumeChange,
                onBack = onBack,
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
                onLyricsPanel = { panel = if (lyricsOpen) PlayerPanel.NONE else PlayerPanel.LYRICS },
                onQueuePanel = onQueue,
            )
            return@Box
        }

        // Main Player Content overlaid on the artwork
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top drag handle
            PlayerTopHandle(onDismiss = onBack, modifier = dragHandle)

            if (panel != PlayerPanel.NONE) {
                // Compact header stands in for the big title row, which the panel displaces.
                PanelTrackHeader(
                    track = track,
                    liked = liked,
                    onLiked = onLiked,
                    onMore = onQueue,
                    onShare = onShare,
                    isDownloaded = downloadedTrackIds.contains(track.id),
                    onDownload = onDownloadTrack?.let { action -> { action(track) } },
                    onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                    onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Lines dissolve at both ends instead of being sliced off by the
                        // header above and the scrubber below.
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
                        },
                ) {
                    if (queueOpen) {
                        PlayerQueuePanel(
                            playback = playback,
                            editable = canControl,
                            onPlayIndex = onPlayQueueIndex,
                            onRemove = onRemoveQueueIndex,
                            onMove = onMoveQueueItem,
                            onClearUpcoming = onClearUpcoming,
                            onShuffleUpcoming = onShuffle,
                        )
                        return@Box
                    }
                    // Sung words take the cover's own accent. Artwork accents are often too dark
                    // to read on the dimmed backdrop, so lift the colour until it carries alone.
                    val palette = rememberArtworkPalette(track.artworkUrl)
                    val lyricAccent = remember(palette.accent) { palette.accent.readableOnDarkBackdrop() }
                    when (lyrics) {
                        is LoadState.Content -> LyricLines(
                            lines = lyrics.value,
                            positionMs = playback.positionMs,
                            playing = playback.isPlaying,
                            onSeek = onSeek,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                            accent = lyricAccent,
                        )

                        LoadState.Loading -> LyricsNotice("Finding lyrics…")
                        is LoadState.Empty -> LyricsNotice(lyrics.message)
                        is LoadState.Error -> LyricsNotice(lyrics.message)
                        LoadState.Idle -> LyricsNotice("Start a song to see its lyrics.")
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            // Lower Controls Section with consistent horizontal padding
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {

                // Track Title, Artist, Explicit Badge, Star & More Buttons. The lyrics header
                // carries this information while lyrics are open.
                if (panel == PlayerPanel.NONE) {
                    TrackInfoRow(
                        track = track,
                        liked = liked,
                        onLiked = onLiked,
                        onMore = onQueue,
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
                    Spacer(Modifier.height(18.dp))
                }

                PlayerControlStack(
                    playback = playback,
                    targets = targets,
                    canControl = canControl,
                    localControls = localControls,
                    transition = transition,
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    remoteVolume = remoteVolume,
                    lyricsActive = lyricsOpen,
                    queueActive = queueOpen,
                    onRemoteVolumeChange = onRemoteVolumeChange,
                    onSeek = onSeek,
                    onToggle = onToggle,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLyrics = { panel = if (lyricsOpen) PlayerPanel.NONE else PlayerPanel.LYRICS },
                    onDevices = onDevices,
                    onQueue = onQueue,
                )
            }
        }
    }
}
