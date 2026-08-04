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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.TrackActionsPopup
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

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
    audioQuality: dev.sfg.orchard.mobile.model.AudioQuality = dev.sfg.orchard.mobile.model.AudioQuality.HIGH,
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
                audioQuality = audioQuality,
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
                    audioQuality = audioQuality,
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

/**
 * The tablet player: cover on the left, everything that reads as text on the right.
 *
 * The phone treats lyrics and the queue as modes that take the screen away from
 * the artwork. Here they are just the right column's content, so a glance still
 * lands on the cover and the transport never moves between modes.
 */
@Composable
private fun TabletPlayerBody(
    track: Track,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
    animatedArtworkEnabled: Boolean,
    liked: Boolean,
    panel: PlayerPanel,
    canControl: Boolean,
    localControls: Boolean,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker?,
    showBitrate: Boolean,
    bitrateKbps: Int,
    audioQuality: dev.sfg.orchard.mobile.model.AudioQuality,
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
    onShare: (() -> Unit)?,
    onOpenCollection: ((String) -> Unit)?,
    onLyricsPanel: () -> Unit,
    onQueuePanel: () -> Unit,
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
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                val motion = track.animatedArtworkUrl.ifBlank { track.animatedArtworkVerticalUrl }
                if (animatedArtworkEnabled && motion.isNotBlank()) {
                    AnimatedArtworkVideo(motion, playback.isPlaying, Modifier.fillMaxSize())
                } else {
                    RemoteArtwork(track.artworkUrl, track.title, Modifier.fillMaxSize())
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
                            )
                        } else {
                            val palette = rememberArtworkPalette(track.artworkUrl)
                            val lyricAccent = remember(palette.accent) { palette.accent.readableOnDarkBackdrop() }
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
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    audioQuality = audioQuality,
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
                )
            }
        }
    }
}

/** Which mode, if any, has taken over the middle of the player. */
private enum class PlayerPanel { NONE, LYRICS, QUEUE }

/**
 * Everything below the track title: scrubber, transport, volume, destinations.
 *
 * Shared by both layouts. The phone stacks it under the artwork; the tablet puts
 * it in the right column beside the cover, and neither should drift from the
 * other as controls are added.
 */
@Composable
private fun PlayerControlStack(
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    canControl: Boolean,
    localControls: Boolean,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker?,
    showBitrate: Boolean,
    bitrateKbps: Int,
    audioQuality: dev.sfg.orchard.mobile.model.AudioQuality,
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
        audioQuality = audioQuality,
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
private fun PanelTrackHeader(
    track: Track,
    liked: Boolean,
    onLiked: () -> Unit,
    onMore: () -> Unit,
    onShare: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkTile(track.artworkUrl, "Artwork for ${track.title}", Modifier.size(44.dp), 8)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        var menuOpen by remember { mutableStateOf(false) }
        if (menuOpen) {
            TrackActionsPopup(
                track = track,
                onDismiss = { menuOpen = false },
                onViewQueue = onMore,
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
private fun LyricsNotice(message: String) {
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
private fun Color.readableOnDarkBackdrop(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    if (hsl[1] < 0.12f) return Color.White
    hsl[1] = hsl[1].coerceIn(0.55f, 0.95f)
    hsl[2] = hsl[2].coerceIn(0.62f, 0.80f)
    return Color(ColorUtils.HSLToColor(hsl))
}
