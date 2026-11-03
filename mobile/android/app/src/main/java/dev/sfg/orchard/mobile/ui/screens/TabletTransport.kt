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

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

private val InkColor = Color(0xFF0B0D0A)
private val PrimaryInk = Color(0xFFF7F5F0)
private val DisabledInk = Color(0xFF6B6F68)

/** Shuffle, previous, a solid play disc, next and repeat as bare glyphs, with no pill behind them. */
@Composable
fun TabletTransportControls(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(Icons.Rounded.Shuffle, "Shuffle", 40.dp, 18.dp, enabled, shuffle, accent, onShuffle)
        TransportGlyph(Icons.Rounded.SkipPrevious, "Previous track", 52.dp, 28.dp, enabled, false, accent, onPrevious)
        PlayDisc(isPlaying, enabled, onToggle)
        TransportGlyph(Icons.Rounded.SkipNext, "Next track", 52.dp, 28.dp, enabled, false, accent, onNext)
        TransportGlyph(
            if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat", 40.dp, 18.dp, enabled, repeatMode != RepeatMode.OFF, accent, onRepeat,
        )
    }
}

@Composable
private fun pressedScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "TransportPress")
    return scale
}

@Composable
private fun TransportGlyph(
    icon: ImageVector,
    label: String,
    size: Dp,
    glyph: Dp,
    enabled: Boolean,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .scale(pressedScale(source))
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (!enabled) DisabledInk else if (active) accent else PrimaryInk,
            modifier = Modifier.size(glyph),
        )
    }
}

@Composable
private fun PlayDisc(isPlaying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(68.dp)
            .scale(pressedScale(source))
            .clip(CircleShape)
            .background(PrimaryInk.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(source, null, enabled = enabled, role = Role.Button, onClickLabel = if (isPlaying) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(isPlaying, label = "PlayGlyph") { playing ->
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = InkColor,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
