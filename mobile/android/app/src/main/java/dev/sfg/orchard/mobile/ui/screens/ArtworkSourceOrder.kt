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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.OrchardSettings

@Composable
internal fun ArtworkSourceOrder(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        RowTitle("Artwork source order")
        RowSubtitle("Try sources from top to bottom for moving artwork")
        Column(Modifier.padding(top = 8.dp)) {
            settings.artworkSourceOrder.forEachIndexed { index, source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source.label, color = SettingsStyle.Title, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val reordered = settings.artworkSourceOrder.toMutableList()
                        java.util.Collections.swap(reordered, index, index - 1)
                        onSettings(settings.copy(artworkSourceOrder = reordered))
                    }, enabled = index > 0) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move ${source.label} up")
                    }
                    IconButton(onClick = {
                        val reordered = settings.artworkSourceOrder.toMutableList()
                        java.util.Collections.swap(reordered, index, index + 1)
                        onSettings(settings.copy(artworkSourceOrder = reordered))
                    }, enabled = index < settings.artworkSourceOrder.lastIndex) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move ${source.label} down")
                    }
                }
            }
        }
    }
}
