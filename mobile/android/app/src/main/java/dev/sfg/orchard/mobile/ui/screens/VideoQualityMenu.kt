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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.app.MusicVideoState
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Quality chip over the picture. The menu lists the heights this video offers. */
@Composable
internal fun VideoQualityButton(state: MusicVideoState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(modifier) {
        Row(
            Modifier
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                .clickable(onClickLabel = "Video quality") { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tune, null, tint = Color.White, modifier = Modifier.padding(end = 6.dp).size(14.dp))
            Text(
                if (state.height > 0) heightLabel(state.height) else "Quality",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = CanopyColors.Surface) {
            QualityItem("Best available", state.maxHeight == 0) { open = false; onSelect(0) }
            // Before the stream resolves there is no list yet; offer the common steps.
            state.heights.ifEmpty { CommonHeights }.distinct().sortedDescending().forEach { height ->
                QualityItem(heightLabel(height), state.maxHeight == height) { open = false; onSelect(height) }
            }
        }
    }
}

@Composable
private fun QualityItem(label: String, chosen: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = CanopyColors.Text, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal) },
        trailingIcon = { if (chosen) Icon(Icons.Rounded.Check, "Selected", tint = CanopyColors.Accent) },
        onClick = onClick,
    )
}

private fun heightLabel(height: Int): String = if (height >= 2160) "4K" else "${height}p"

private val CommonHeights = listOf(2160, 1440, 1080, 720, 480, 360)
