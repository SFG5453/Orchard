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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.sfg.orchard.mobile.model.PlaybackSnapshot

@Composable
fun CanopyReadout(
    playback: PlaybackSnapshot,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    transition: dev.sfg.orchard.mobile.model.TransitionMarker? = null,
    mixProgress: Float? = null,
    onArtworkBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    onClear: () -> Unit = {},
) {
    // Keeps the last track on screen while the pill animates out after the queue clears.
    val shown = remember { arrayOfNulls<PlaybackSnapshot>(1) }
    if (playback.currentTrack != null) shown[0] = playback
    AnimatedVisibility(
        visible = playback.currentTrack != null,
        enter = slideInVertically(spring(dampingRatio = 0.62f, stiffness = 380f)) { it } +
            fadeIn() + scaleIn(initialScale = 0.85f),
        exit = slideOutVertically { it / 2 } + fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
        ReadoutBody(
            playback = shown[0] ?: playback,
            onOpen = onOpen,
            onToggle = onToggle,
            onNext = onNext,
            modifier = modifier,
            onPrevious = onPrevious,
            transition = transition,
            mixProgress = mixProgress,
            onArtworkBounds = onArtworkBounds,
            onClear = onClear,
        )
    }
}

@Composable
private fun ReadoutBody(
    playback: PlaybackSnapshot,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
    onPrevious: () -> Unit,
    transition: dev.sfg.orchard.mobile.model.TransitionMarker?,
    mixProgress: Float?,
    onArtworkBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)?,
    onClear: () -> Unit,
) {
    MiniPlayer(
        playback = playback,
        onTogglePlay = onToggle,
        onNext = onNext,
        onPrevious = onPrevious,
        onClick = onOpen,
        modifier = modifier,
        transition = transition,
        mixProgress = mixProgress,
        onArtworkBounds = onArtworkBounds,
        onClear = onClear,
    )
}
