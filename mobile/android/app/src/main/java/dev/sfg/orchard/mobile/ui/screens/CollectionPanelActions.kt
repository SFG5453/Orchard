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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

private val ActionShape = RoundedCornerShape(6.dp)

/** Desktop-style action strip: light Play pill followed by outlined square icon buttons. */
@Composable
internal fun CollectionPanelActions(
    onPlay: () -> Unit,
    playEnabled: Boolean,
    playFill: Color,
    playInk: Color,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onPlay,
            enabled = playEnabled,
            shape = ActionShape,
            color = if (playEnabled) playFill else playFill.copy(alpha = 0.4f),
            modifier = Modifier.height(40.dp),
        ) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = playInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = playInk)
            }
        }
        actions()
    }
}

/** Outlined 40dp square icon button. */
@Composable
internal fun PanelIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = CanopyColors.Text,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ActionShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.padding(10.dp),
        )
    }
}
