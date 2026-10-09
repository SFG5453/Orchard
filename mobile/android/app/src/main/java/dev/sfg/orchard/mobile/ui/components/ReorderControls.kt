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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Edit-mode buttons beside a playlist row: up, down and remove. A null action greys its button, so
 * the first row cannot move up and the last cannot move down.
 */
@Composable
internal fun ReorderControls(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ControlButton(Icons.Rounded.KeyboardArrowUp, "Move up", onMoveUp)
        ControlButton(Icons.Rounded.KeyboardArrowDown, "Move down", onMoveDown)
        ControlButton(Icons.Rounded.Close, "Remove from playlist", onRemove)
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, action: (() -> Unit)?) {
    IconButton(onClick = { action?.invoke() }, enabled = action != null, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (action != null) CanopyColors.Text else CanopyColors.Muted.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}
