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

import android.graphics.Bitmap

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
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
    modifier: Modifier = Modifier,
    /** The pill's bounds in root coordinates; the player collapses into it. */
    collapseBounds: Rect? = null,
    /** The pill's artwork thumbnail, which the player's cover flies into. */
    collapseArtworkBounds: Rect? = null,
    /**
     * The cover's resting bounds — the other end of the flight. Hoisted because it has to
     * outlive the player: measuring it needs the player on screen, but the opening flight
     * needs it before the player has been laid out.
     */
    restingCoverBounds: Rect? = null,
    onRestingCoverBounds: (Rect) -> Unit = {},
    protocolVersion: Int = 1,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker? = null,
    /** Raw overlap progress, including the part after visible identity moves to the incoming song. */
    mixProgress: Float? = null,
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
    autoplayEnabled: Boolean = true,
    autoplayLoading: Boolean = false,
    autoplayError: String = "",
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
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
    val activeMixProgress =
        mixProgress ?: dev.sfg.orchard.mobile.ui.components.transitionProgress(playback, transition)

    // Two panes need room for a square cover and a readable column beside it.
    // Below this a tablet in portrait, or a large phone in landscape, is better
    // served by the stacked layout it already has.
    val wideLayout = LocalConfiguration.current.screenWidthDp >= 840

    // Swipe-down-to-dismiss. The collapse runs 0 (filling the screen) to 1 (sitting exactly on
    // the pill), so the drag, the opening animation and the committed dismiss are all the same
    // motion — the player grows out of the pill and shrinks back into it.
    //
    // The finger writes a plain float and only the settle is animated. Driving the drag through
    // the Animatable instead meant launching a coroutine per delta, and a late one would take
    // the animation mutex back off the spring and leave the player frozen half-collapsed.
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(1f) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val progress = if (dragging) dragProgress else settle.value

    val density = LocalDensity.current
    // How far the finger travels to complete the collapse. Longer than the commit threshold so
    // the shrink reads as gradual rather than snapping shut halfway down.
    val collapseSpan = with(density) { 320.dp.toPx() }
    val dismissVelocity = with(density) { 800.dp.toPx() }
    var playerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) { settle.animateTo(0f, tween(360)) }

    val dismiss: () -> Unit = {
        scope.launch {
            settle.snapTo(progress)
            dragging = false
            settle.animateTo(1f, tween(280))
            onBack()
        }
        Unit
    }
    // Back mirrors the drag rather than cutting straight to the previous screen.
    BackHandler(enabled = panel == PlayerPanel.NONE) { dismiss() }

    val dragHandle = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            dragProgress = (dragProgress + delta / collapseSpan).coerceIn(0f, 1f)
        },
        // Picks up wherever the settle had got to, so grabbing it mid-animation is seamless.
        onDragStarted = {
            dragProgress = settle.value
            dragging = true
        },
        onDragStopped = { velocity ->
            val committed = dragProgress > COMMIT_FRACTION || velocity > dismissVelocity
            // Hand the value back to the Animatable before releasing the drag, so the two
            // never disagree about where the player is for even a frame.
            settle.snapTo(dragProgress)
            dragging = false
            if (committed) {
                settle.animateTo(1f, tween(220))
                onBack()
            } else {
                settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
        },
    )

    // The cover is a shared element: the body fades out early and this outer box keeps
    // the flying artwork clear of that fade, so the cover itself carries the motion all
    // the way onto the pill's thumbnail.
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { playerSize = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (progress <= 0f) return@graphicsLayer
                    if (playerSize.width == 0) {
                        // Not measured yet — stay hidden rather than flashing at full size.
                        alpha = 0f
                        return@graphicsLayer
                    }
                    // The body no longer has to land on the pill; the cover does that. It
                    // just settles back and clears out, which avoids squashing the controls
                    // into a bar on the way down.
                    val target = collapseBounds
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    val shrink = lerp(1f, BODY_SHRINK, progress)
                    scaleX = shrink
                    scaleY = shrink
                    translationY = lerp(0f, (target?.top ?: size.height) * 0.5f, progress)
                    // Early, so the cover finishes its flight over the page underneath
                    // rather than over a ghost of the player.
                    alpha = (1f - progress / BODY_FADE_COMPLETE).coerceIn(0f, 1f)
                    shape = RoundedCornerShape(lerp(0f, 34f, progress).dp)
                    clip = true
                }
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
            // One sample feeds the backdrop and the lyrics, so sung words carry the same
            // colour the artwork bleeds into rather than a second, squarer sample of the cover.
            val verticalVideo = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
            var videoFrame by remember(verticalVideo) { mutableStateOf<Bitmap?>(null) }
            val palette = rememberFullBleedPalette(track, videoFrame)
            val lyricAccent = palette.accent

            FullBleedPlayerBackdrop(
                track = track,
                isPlaying = playback.isPlaying,
                animatedArtworkEnabled = animatedArtworkEnabled,
                palette = palette,
                onVideoFrame = { videoFrame = it },
                onArtworkBounds = { if (!wideLayout && progress == 0f) onRestingCoverBounds(it) },
                transitionProgress = activeMixProgress,
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
                    lyricAccent = lyricAccent,
                    onCoverBounds = { if (progress == 0f) onRestingCoverBounds(it) },
                    animatedArtworkEnabled = animatedArtworkEnabled,
                    liked = liked,
                    panel = panel,
                    canControl = canControl,
                    localControls = localControls,
                    transition = transition,
                    mixProgress = activeMixProgress,
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
                    autoplayEnabled = autoplayEnabled,
                    autoplayLoading = autoplayLoading,
                    autoplayError = autoplayError,
                    onAutoplayEnabled = onAutoplayEnabled,
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
                                autoplayEnabled = autoplayEnabled,
                                autoplayLoading = autoplayLoading,
                                autoplayError = autoplayError,
                                onAutoplayEnabled = onAutoplayEnabled,
                            )
                            return@Box
                        }
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
                        mixProgress = activeMixProgress,
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

        SharedCoverFlight(
            url = track.artworkUrl,
            description = track.title,
            progress = progress,
            source = restingCoverBounds,
            destination = collapseArtworkBounds,
        )
    }
}

/** How far down the collapse must have travelled on release for the dismiss to commit. */
private const val COMMIT_FRACTION = 0.5f

/** Fraction of the collapse over which the player body fades out entirely. */
private const val BODY_FADE_COMPLETE = 0.55f

/** How far the body draws back as it goes, so it recedes rather than merely fading. */
private const val BODY_SHRINK = 0.90f

/**
 * The cover, flying between the player and the pill's thumbnail.
 *
 * Drawn outside the body's fade so it stays fully opaque the whole way down: the cover is
 * the one thing both ends of the transition have in common, so it is what carries the eye.
 * It is laid out at [source] and transformed towards [destination] rather than being
 * re-measured, which keeps the flight off the layout pass.
 */
@Composable
private fun SharedCoverFlight(
    url: String,
    description: String,
    progress: Float,
    source: Rect?,
    destination: Rect?,
) {
    if (progress <= 0f || source == null || destination == null || source.width <= 0f) return
    val density = LocalDensity.current
    // The player's cover is a tall crop and the thumbnail is square, so the flight scales by
    // width and lets the clip take up the difference in height.
    val scale = destination.width / source.width
    val height = source.height * scale
    // Aim at the middle of the thumbnail: the crop keeps the subject centred, and the two
    // shapes only agree on their centre, not their edges.
    val centreY = destination.center.y - height / 2f
    Box(
        Modifier
            .offset {
                IntOffset(
                    lerp(source.left, destination.left, progress).roundToInt(),
                    lerp(source.top, centreY, progress).roundToInt(),
                )
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = lerp(1f, scale, progress)
                scaleY = lerp(1f, scale, progress)
            }
            .size(
                width = with(density) { source.width.toDp() },
                height = with(density) { source.height.toDp() },
            )
            .graphicsLayer {
                // Squares off into the thumbnail's own rounding as it lands.
                shape = RoundedCornerShape((lerp(0f, 10f, progress) / scale.coerceAtLeast(0.01f)).dp)
                clip = true
                // Only the last moment, handing over to the real thumbnail underneath.
                alpha = ((1f - progress) / (1f - COVER_HANDOFF)).coerceIn(0f, 1f)
            },
    ) {
        RemoteArtwork(url = url, description = description, modifier = Modifier.fillMaxSize())
    }
}

/** Where the flying cover starts giving way to the pill's real thumbnail. */
private const val COVER_HANDOFF = 0.88f
