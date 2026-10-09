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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.ui.theme.legibleOnDarkChrome

/**
 * Shuffle, previous, play, next, repeat. The three transport pills share the middle by weight,
 * and the one under the finger widens while its neighbours give way.
 */
@Composable
fun PlayerTransportControls(
    isPlaying: Boolean,
    status: PlaybackStatus,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    localControls: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previousSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    val nextSource = remember { MutableInteractionSource() }
    val previousPressed by previousSource.collectIsPressedAsState()
    val playPressed by playSource.collectIsPressedAsState()
    val nextPressed by nextSource.collectIsPressedAsState()

    val previousWeight by pillWeight(previousPressed, playPressed || nextPressed, SIDE_WEIGHT, "Previous")
    val playWeight by pillWeight(playPressed, previousPressed || nextPressed, PLAY_WEIGHT, "Play")
    val nextWeight by pillWeight(nextPressed, previousPressed || playPressed, SIDE_WEIGHT, "Next")

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportToggleOption(
            icon = Icons.Rounded.Shuffle,
            description = if (shuffle) "Turn shuffle off" else "Turn shuffle on",
            active = shuffle,
            enabled = localControls,
            onClick = onShuffle,
        )
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportPill(
                icon = Icons.Rounded.SkipPrevious,
                description = "Previous track",
                enabled = localControls,
                interactionSource = previousSource,
                onClick = onPrevious,
                modifier = Modifier.weight(previousWeight),
            )
            PlayPill(
                isPlaying = isPlaying,
                isBuffering = status == PlaybackStatus.BUFFERING || status == PlaybackStatus.LOADING,
                enabled = localControls,
                interactionSource = playSource,
                onClick = onToggle,
                modifier = Modifier.weight(playWeight),
            )
            TransportPill(
                icon = Icons.Rounded.SkipNext,
                description = "Next track",
                enabled = localControls,
                interactionSource = nextSource,
                onClick = onNext,
                modifier = Modifier.weight(nextWeight),
            )
        }
        Spacer(Modifier.width(10.dp))
        TransportToggleOption(
            icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description = when (repeatMode) {
                RepeatMode.OFF -> "Repeat all"
                RepeatMode.ALL -> "Repeat one"
                RepeatMode.ONE -> "Turn repeat off"
            },
            active = repeatMode != RepeatMode.OFF,
            enabled = localControls,
            onClick = onRepeat,
        )
    }
}

@Composable
private fun pillWeight(pressed: Boolean, neighbourPressed: Boolean, rest: Float, label: String) =
    animateFloatAsState(
        targetValue = when {
            pressed -> rest * PRESSED_GROWTH
            neighbourPressed -> rest * NEIGHBOUR_SHRINK
            else -> rest
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "${label}PillWeight",
    )

@Composable
private fun TransportPill(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        color = Color.White.copy(alpha = if (enabled) 0.14f else 0.06f),
        contentColor = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
        interactionSource = interactionSource,
        modifier = modifier.height(PILL_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
        }
    }
}

/** Solid white so the one control that matters most never sinks into the artwork behind it. */
// Every other button on this screen is a suggestion. This one is a lifestyle.
@Composable
private fun PlayPill(
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
        contentColor = PlayGlyph,
        interactionSource = interactionSource,
        modifier = modifier.height(PILL_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = PlayGlyph,
                    strokeWidth = 3.dp,
                )
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(160)) + scaleIn(tween(240), initialScale = 0.5f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(tween(180), targetScale = 0.5f))
                    },
                    label = "PlayPauseIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

private val PillShape = RoundedCornerShape(percent = 50)
private val PILL_HEIGHT = 56.dp
private val PlayGlyph = Color(0xFF111214)
private const val SIDE_WEIGHT = 1f
private const val PLAY_WEIGHT = 1.4f
private const val PRESSED_GROWTH = 1.3f
private const val NEIGHBOUR_SHRINK = 0.85f

@Composable
private fun TransportToggleOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val activeColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.25f)
            active -> LocalAccent.current.legibleOnDarkChrome()
            else -> Color.White.copy(alpha = 0.60f)
        },
        label = "ToggleActiveColor",
    )

    // The dot hangs inside the button's own box, so the icon stays on the pills' centre line.
    Box(contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = activeColor,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active && enabled) activeColor else Color.Transparent),
        )
    }
}
