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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Glass Song/Video switch. The thumb springs across and stretches while it travels. */
@Composable
internal fun SongVideoSwitch(
    video: Boolean,
    onSelect: (video: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
) {
    val travel by animateDpAsState(
        targetValue = if (video) SegmentWidth else 0.dp,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "switch thumb",
    )
    Box(
        modifier
            .shadow(14.dp, PillShape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.6f))
            .background(Color.Black.copy(alpha = 0.30f), PillShape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))), PillShape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))), PillShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = travel)
                .size(SegmentWidth, SegmentHeight)
                .graphicsLayer {
                    // Widest at mid-travel, back to round at rest; the spring overshoot adds a wobble.
                    val progress = (travel / SegmentWidth).coerceIn(0f, 1f)
                    scaleX = 1f + 0.22f * sin(progress * PI).toFloat()
                }
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.36f), Color.White.copy(alpha = 0.16f))), PillShape)
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f))), PillShape),
        )
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Segment("Song", selected = !video, enabled = true) { if (video) onSelect(false) }
            Segment("Video", selected = video, enabled = video || !checking, busy = checking && !video) {
                if (!video) onSelect(true)
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = if (enabled) 0.66f else 0.4f),
        label = "segment label",
    )
    Row(
        Modifier
            .size(SegmentWidth, SegmentHeight)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 6.dp).size(11.dp),
                color = color,
                strokeWidth = 1.5.dp,
            )
        }
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val PillShape = RoundedCornerShape(50)
private val SegmentWidth = 76.dp
private val SegmentHeight = 34.dp

/** Full height of the switch, for callers that centre it on a row. */
internal val SongVideoSwitchHeight = SegmentHeight + 6.dp
