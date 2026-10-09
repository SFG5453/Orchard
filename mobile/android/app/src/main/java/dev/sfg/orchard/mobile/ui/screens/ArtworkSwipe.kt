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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Horizontal skip gesture whose artwork follows the finger. Same split as [PlayerCollapse]:
 * the finger writes a plain float and only the settle animates.
 */
@Stable
class ArtworkSwipe internal constructor(private val scope: CoroutineScope) {
    private val settle = Animatable(0f)
    private var dragOffset by mutableFloatStateOf(0f)
    private var dragging by mutableStateOf(false)

    /** Set between a committed fling and the queue catching up, so the card skips its own fade. */
    var handingOff by mutableStateOf(false)
        private set

    /** Cover width plus the gap to its neighbour: where a committed skip parks the cover. */
    var span by mutableFloatStateOf(1f)
    var flingVelocity = 1f

    /** Read from graphicsLayer only, or the whole player recomposes per pointer event. */
    val offset: Float get() = if (dragging) dragOffset else settle.value

    fun drag(delta: Float, hasPrevious: Boolean, hasNext: Boolean) {
        val next = dragOffset + delta
        // Rubber-band towards a missing neighbour; there is nothing to skip to.
        val blocked = (next > 0 && !hasPrevious) || (next < 0 && !hasNext)
        dragOffset = if (blocked) dragOffset + delta * EDGE_RESISTANCE else next
    }

    fun start() {
        dragOffset = settle.value
        dragging = true
    }

    fun stop(velocity: Float, hasPrevious: Boolean, hasNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
        val travelled = dragOffset
        val direction = when {
            travelled < -span * COMMIT_FRACTION || velocity < -flingVelocity -> -1f
            travelled > span * COMMIT_FRACTION || velocity > flingVelocity -> 1f
            else -> 0f
        }.let { if ((it < 0 && !hasNext) || (it > 0 && !hasPrevious)) 0f else it }
        scope.launch {
            settle.snapTo(travelled)
            dragging = false
            if (direction == 0f) {
                settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                return@launch
            }
            settle.animateTo(direction * span, tween(200))
            handingOff = true
            if (direction < 0) onNext() else onPrevious()
            // A skip the engine refuses must not leave the cover parked off-screen.
            delay(HANDOFF_TIMEOUT_MS)
            if (handingOff) land()
        }
    }

    /** The queue has moved and the neighbour on screen is the current track, so recentre. */
    suspend fun land() {
        settle.snapTo(0f)
        handingOff = false
    }
}

@Composable
internal fun rememberArtworkSwipe(trackId: String?): ArtworkSwipe {
    val scope = rememberCoroutineScope()
    val swipe = remember { ArtworkSwipe(scope) }
    swipe.flingVelocity = with(LocalDensity.current) { 900.dp.toPx() }
    LaunchedEffect(trackId) { if (swipe.handingOff) swipe.land() }
    return swipe
}

@Composable
internal fun Modifier.artworkSwipe(
    swipe: ArtworkSwipe,
    enabled: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = if (!enabled) this else draggable(
    orientation = Orientation.Horizontal,
    state = rememberDraggableState { delta -> swipe.drag(delta, hasPrevious, hasNext) },
    onDragStarted = { swipe.start() },
    onDragStopped = { velocity -> swipe.stop(velocity, hasPrevious, hasNext, onPrevious, onNext) },
)

/** Queue neighbours of the visible track, for the covers waiting either side of it. */
internal fun PlaybackSnapshot.neighbours(): Pair<Track?, Track?> {
    val index = currentIndex
    if (index !in queue.indices) return null to null
    return queue.getOrNull(index - 1) to queue.getOrNull(index + 1)
}

/**
 * The current cover with its neighbours parked a gap away on either side, all riding the swipe.
 * Neighbours are composed even at rest so their artwork is already decoded when a swipe starts.
 */
@Composable
internal fun SwipeableCover(
    swipe: ArtworkSwipe,
    previous: Track?,
    next: Track?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val gap = with(LocalDensity.current) { NEIGHBOUR_GAP.toPx() }
    Box(modifier.onSizeChanged { swipe.span = it.width + gap }) {
        listOf(previous to -1f, next to 1f).forEach { (track, side) ->
            if (track == null) return@forEach
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val shift = swipe.offset + side * (size.width + gap)
                        translationX = shift
                        // Skip the layer entirely while it sits off-screen at rest.
                        alpha = if (abs(swipe.offset) < 0.5f) 0f else 1f
                        val distance = (abs(shift) / (size.width + gap)).coerceIn(0f, 1f)
                        scaleX = 1f - NEIGHBOUR_SHRINK * distance
                        scaleY = scaleX
                    }
                    .clip(CoverShape),
            ) {
                RemoteArtwork(
                    url = track.artworkUrl,
                    description = "Artwork for ${track.title}",
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Box(
            Modifier.graphicsLayer {
                translationX = swipe.offset
                val distance = (abs(swipe.offset) / (size.width + gap)).coerceIn(0f, 1f)
                scaleX = 1f - NEIGHBOUR_SHRINK * distance
                scaleY = scaleX
                rotationZ = sign(swipe.offset) * TILT_DEGREES * distance
            },
        ) { content() }
    }
}

private val CoverShape = RoundedCornerShape(22.dp)
private val NEIGHBOUR_GAP = 28.dp
private const val NEIGHBOUR_SHRINK = 0.08f
private const val TILT_DEGREES = 2f
private const val COMMIT_FRACTION = 0.35f
private const val EDGE_RESISTANCE = 0.25f
private const val HANDOFF_TIMEOUT_MS = 1_500L
