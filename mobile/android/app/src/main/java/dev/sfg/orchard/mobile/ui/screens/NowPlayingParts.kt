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

import dev.kawarp.KawarpEngine
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** How far down the collapse must have travelled on release for the dismiss to commit. */
private const val COMMIT_FRACTION = 0.5f

/**
 * Swipe-down-to-dismiss. Progress runs 0 (filling the screen) to 1 (sitting exactly on the pill),
 * so the drag, the opening animation and the committed dismiss are all the same motion.
 *
 * The finger writes a plain float and only the settle is animated. Driving the drag through the
 * Animatable would launch a coroutine per delta, and a late one could take the animation mutex
 * back off the spring and leave the player frozen half-collapsed.
 */
@Stable
internal class PlayerCollapse(
    private val scope: CoroutineScope,
    private val onBack: State<() -> Unit>,
) {
    private val settle = Animatable(1f)
    private var dragProgress by mutableFloatStateOf(0f)
    private var dragging by mutableStateOf(false)

    /** Finger travel that completes the collapse; longer than the commit threshold so it reads as gradual. */
    var collapseSpan = 1f
    var dismissVelocity = 1f

    val progress: Float get() = if (dragging) dragProgress else settle.value

    suspend fun open() = settle.animateTo(0f, tween(360))

    fun dismiss() {
        scope.launch {
            settle.snapTo(progress)
            dragging = false
            settle.animateTo(1f, tween(280))
            onBack.value()
        }
    }

    fun drag(delta: Float) {
        dragProgress = (dragProgress + delta / collapseSpan).coerceIn(0f, 1f)
    }

    /** Picks up wherever the settle had got to, so grabbing it mid-animation is seamless. */
    fun dragStarted() {
        dragProgress = settle.value
        dragging = true
    }

    suspend fun dragStopped(velocity: Float) {
        val committed = dragProgress > COMMIT_FRACTION || velocity > dismissVelocity
        // Hand the value back before releasing the drag so the two never disagree for a frame.
        settle.snapTo(dragProgress)
        dragging = false
        if (committed) {
            settle.animateTo(1f, tween(220))
            onBack.value()
        } else {
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
}

/** Opens the player out of the pill on first composition. */
@Composable
internal fun rememberPlayerCollapse(onBack: () -> Unit): PlayerCollapse {
    val scope = rememberCoroutineScope()
    val currentOnBack = rememberUpdatedState(onBack)
    val collapse = remember { PlayerCollapse(scope, currentOnBack) }
    with(LocalDensity.current) {
        collapse.collapseSpan = 320.dp.toPx()
        collapse.dismissVelocity = 800.dp.toPx()
    }
    LaunchedEffect(Unit) { collapse.open() }
    return collapse
}

@Composable
internal fun Modifier.playerDragHandle(collapse: PlayerCollapse): Modifier = this.draggable(
    orientation = Orientation.Vertical,
    state = rememberDraggableState { delta -> collapse.drag(delta) },
    onDragStarted = { collapse.dragStarted() },
    onDragStopped = { velocity -> collapse.dragStopped(velocity) },
)

@Composable
internal fun SleepTimerDialog(
    remainingSeconds: Long,
    endOfTrack: Boolean,
    onStart: (Int) -> Unit,
    onStartAtEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                val status = when {
                    endOfTrack -> "Playback will pause at the end of this track."
                    remainingSeconds > 0 -> "Playback will pause in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}."
                    else -> "Choose when playback should pause."
                }
                Text(status)
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    TextButton(onClick = { onStart(minutes); onDismiss() }) {
                        Text("$minutes minutes")
                    }
                }
                TextButton(onClick = { onStartAtEndOfTrack(); onDismiss() }) {
                    Text("End of current track")
                }
            }
        },
        confirmButton = {
            if (remainingSeconds > 0 || endOfTrack) {
                TextButton(onClick = { onCancel(); onDismiss() }) { Text("Cancel timer") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

internal fun selectableTrackArtists(track: Track) = track.artists
    .filter { it.id.isNotBlank() }
    .distinctBy { it.id }

internal fun artistOpenAction(
    track: Track,
    onOpenCollection: ((String) -> Unit)?,
    onMultipleArtists: () -> Unit,
): (() -> Unit)? {
    val artists = selectableTrackArtists(track)
    val openCollection = onOpenCollection ?: return null
    return when {
        artists.size > 1 -> onMultipleArtists
        artists.size == 1 -> ({ openCollection(artists.single().id) })
        track.artistId.isNotBlank() -> ({ openCollection(track.artistId) })
        else -> null
    }
}

/**
 * The cover, flying between the player and the pill's thumbnail.
 *
 * Drawn outside the body's fade so it stays fully opaque the whole way down: the cover is
 * the one thing both ends of the transition have in common, so it is what carries the eye.
 * It is laid out at [source] and transformed towards [destination] rather than being
 * re-measured, which keeps the flight off the layout pass.
 */

@Composable
internal fun SharedCoverFlight(
    url: String,
    description: String,
    /** Read only in layout and draw, so the flight never recomposes the player. */
    progress: () -> Float,
    source: Rect?,
    destination: Rect?,
) {
    if (source == null || destination == null || source.width <= 0f) return
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
                val progress = progress()
                IntOffset(
                    lerp(source.left, destination.left, progress).roundToInt(),
                    lerp(source.top, centreY, progress).roundToInt(),
                )
            }
            .graphicsLayer {
                val progress = progress()
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = lerp(1f, scale, progress)
                scaleY = lerp(1f, scale, progress)
            }
            .size(
                width = with(density) { source.width.toDp() },
                height = with(density) { source.height.toDp() },
            )
            .graphicsLayer {
                val progress = progress()
                // Squares off into the thumbnail's own rounding as it lands.
                // Fully transparent at rest, which also lets the layer skip drawing entirely.
                if (progress <= 0f) {
                    alpha = 0f
                    return@graphicsLayer
                }
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

/** Full-bleed artwork behind every player layout, with the blur and scrim each panel needs. */
@Composable
internal fun PlayerBackdropLayer(
    track: Track,
    isPlaying: Boolean,
    panel: PlayerPanel,
    wideLayout: Boolean,
    isFoldable: Boolean,
    hasRichArtwork: Boolean,
    palette: ArtworkPalette,
    incomingPalette: ArtworkPalette?,
    animatedArtworkEnabled: Boolean,
    animatedBackgroundEnabled: Boolean,
    gesturesEnabled: Boolean,
    transitionProgress: Float,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onLiked: () -> Unit,
    onVideoFrame: (Bitmap?) -> Unit,
    onArtworkBounds: (Rect) -> Unit,
    /** Phone only: the body owns the gesture and the motion art follows it. */
    swipe: ArtworkSwipe? = null,
) {
    // Full-bleed artwork background. Lyrics push it out of focus rather than
    // replacing it, so the song's colour still carries the screen.
    // The phone puts its controls over the foot of the cover, where the
    // gradient already protects them, and only blurs when a panel opens.
    // Tablets use a pre-blurred 128px Kawarp source instead of applying a large live
    // RenderEffect over decoded video. Phones still blur only when lyrics need it.
    val panelObscuresArtwork = panel != PlayerPanel.NONE && !wideLayout && !isFoldable
    // Phone lyrics sit on the warped cover where the shader exists; elsewhere they keep the blur.
    val warpSupported = remember { KawarpEngine.isSupported() }
    val lyricsRoom = panelObscuresArtwork && panel == PlayerPanel.LYRICS && warpSupported
    val backdropBlur by animateDpAsState(
        targetValue = when {
            isFoldable || lyricsRoom -> 0.dp
            !wideLayout && panel == PlayerPanel.LYRICS -> 44.dp
            else -> 0.dp
        },
        animationSpec = tween(420),
        label = "LyricsBackdropBlur",
    )
    val artworkAlpha by animateFloatAsState(
        targetValue = when {
            isFoldable -> 1f
            !panelObscuresArtwork -> 1f
            panel == PlayerPanel.LYRICS && !lyricsRoom -> 0.35f
            else -> 0f
        },
        animationSpec = tween(420),
        label = "ArtworkAlpha",
    )

    val isSplitModeFoldable = isFoldable && panel != PlayerPanel.NONE
    FullBleedPlayerBackdrop(
        track = track,
        // Fully covered by the lyrics room, so its video can rest.
        isPlaying = isPlaying && !lyricsRoom && (panel == PlayerPanel.LYRICS || !panelObscuresArtwork),
        // A tablet's real motion cover belongs only in the framed square. Its full-screen
        // layer is either the tiny Kawarp source or the static palette fallback.
        animatedArtworkEnabled = animatedArtworkEnabled && !wideLayout,
        warpedArtworkEnabled = wideLayout && animatedBackgroundEnabled,
        ambientGlowEnabled = !wideLayout,
        gesturesEnabled = gesturesEnabled || (isFoldable && hasRichArtwork),
        onNext = onNext,
        onPrevious = onPrevious,
        onLiked = onLiked,
        palette = palette,
        onVideoFrame = onVideoFrame,
        incomingPalette = incomingPalette,
        onArtworkBounds = onArtworkBounds,
        transitionProgress = transitionProgress,
        artworkAlpha = artworkAlpha,
        swipe = swipe,
        modifier = Modifier
            .blur(backdropBlur)
            .then(if (isSplitModeFoldable) Modifier.fillMaxWidth(0.512f) else Modifier.fillMaxWidth()),
    )
    val roomAlpha = animateFloatAsState(if (lyricsRoom) 1f else 0f, tween(420), label = "LyricsRoomAlpha")
    if (lyricsRoom || roomAlpha.value > 0.001f) {
        LyricsRoom(track.artworkUrl, isPlaying, Modifier.graphicsLayer { alpha = roomAlpha.value })
    }
    // Blur is a no-op below API 31, so darken as well to keep lyrics legible everywhere.
    if (!lyricsRoom && (wideLayout || isFoldable || panel != PlayerPanel.NONE)) {
        val scrimAlpha = when {
            isFoldable && panel == PlayerPanel.NONE && hasRichArtwork -> 0.05f
            isFoldable && panel == PlayerPanel.NONE -> 0.15f
            isFoldable -> 0.18f
            panel == PlayerPanel.LYRICS -> 0.38f
            wideLayout -> 0.25f
            else -> 0.15f
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
    }
}

@Composable
internal fun NothingPlaying(onBack: () -> Unit) {
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
}
