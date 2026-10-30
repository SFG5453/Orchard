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
import dev.sfg.orchard.mobile.model.ArtworkSource
import dev.sfg.orchard.mobile.model.OrchardSettings

/** Rows of the order list; signed in to Apple Music, m8tec and boidu share one Apple row. */
private fun artworkRows(order: List<ArtworkSource>, apple: Boolean): List<List<ArtworkSource>> {
    if (!apple) return order.map(::listOf)
    val unofficial = order.filter { it != ArtworkSource.SPOTIFY }
    return order.mapNotNull { source ->
        when {
            source == ArtworkSource.SPOTIFY -> listOf(source)
            source == unofficial.first() -> unofficial
            else -> null
        }
    }
}

@Composable
internal fun ArtworkSourceOrder(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    val apple = appleMusicConnectionStatus().connected
    val rows = artworkRows(settings.artworkSourceOrder, apple)
    fun move(from: Int, to: Int) {
        val reordered = rows.toMutableList()
        java.util.Collections.swap(reordered, from, to)
        onSettings(settings.copy(artworkSourceOrder = reordered.flatten()))
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        RowTitle("Artwork source order")
        RowSubtitle(
            if (apple) "Try sources from top to bottom for moving artwork. Apple Music replaces m8tec and boidu while you're signed in"
            else "Try sources from top to bottom for moving artwork",
        )
        Column(Modifier.padding(top = 8.dp)) {
            rows.forEachIndexed { index, row ->
                val label = if (row.size > 1 || (apple && row.single() != ArtworkSource.SPOTIFY)) "Apple Music" else row.single().label
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = SettingsStyle.Title, modifier = Modifier.weight(1f))
                    IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move $label up")
                    }
                    IconButton(onClick = { move(index, index + 1) }, enabled = index < rows.lastIndex) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move $label down")
                    }
                }
            }
        }
    }
}
