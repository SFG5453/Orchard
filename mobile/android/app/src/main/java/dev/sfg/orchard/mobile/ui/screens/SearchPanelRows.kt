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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.ui.components.ArtworkTile

/** Result groups the filter chips switch between. */
internal enum class SearchScope(val label: String) {
    All("All"),
    Songs("Songs"),
    Videos("Videos"),
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
}

/** Exact artist name wins (typing "Queen" should not crown a song called Queen); otherwise the first song, then whatever else the catalog returned. */
internal fun topResult(results: SearchResults, query: String): CatalogItem? {
    val needle = query.trim()
    results.artists.firstOrNull { it.name.equals(needle, ignoreCase = true) }
        ?.let { return CatalogItem.Performer(it) }
    results.tracks.firstOrNull()?.let { return CatalogItem.Song(it) }
    results.albums.firstOrNull()?.let { return CatalogItem.Record(it) }
    results.artists.firstOrNull()?.let { return CatalogItem.Performer(it) }
    results.playlists.firstOrNull()?.let { return CatalogItem.Collection(it) }
    return null
}

/** Artwork, two lines and a chevron; shared by albums, artists and playlists. */
@Composable
internal fun ResultRow(item: CatalogItem, subtitle: String, round: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkTile(item.artworkUrl, item.title, Modifier.size(48.dp), radius = if (round) 24 else 8)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = SettingsStyle.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = SettingsStyle.Description,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SettingsStyle.Chevron, modifier = Modifier.size(20.dp))
    }
}

private class BrowseEntry(val label: String, val icon: ImageVector, val id: String)

// Same shelves the old chips opened; browse ids are YouTube Music's own.
private val browseEntries = listOf(
    BrowseEntry("New releases", Icons.Rounded.Flare, "FEmusic_new_releases"),
    BrowseEntry("Charts", Icons.AutoMirrored.Rounded.TrendingUp, "FEmusic_charts"),
    BrowseEntry("Moods", Icons.Rounded.SentimentSatisfied, "FEmusic_moods_and_genres"),
)

@Composable
internal fun BrowseTiles(onOpen: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        browseEntries.forEach { entry ->
            Column(
                Modifier
                    .weight(1f)
                    .height(84.dp)
                    .clip(shape)
                    .background(SettingsStyle.Tile, shape)
                    .border(1.dp, SettingsStyle.TileBorder, shape)
                    .clickable { onOpen(entry.id) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(entry.icon, null, tint = SettingsStyle.TileIcon, modifier = Modifier.size(22.dp))
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = SettingsStyle.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HistoryRow(value: String, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(48.dp).clickable(onClick = onPick).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.History, null, tint = SettingsStyle.Chevron, modifier = Modifier.size(18.dp))
            Text(
                value,
                modifier = Modifier.padding(start = 14.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = SettingsStyle.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, "Remove search", tint = SettingsStyle.Chevron, modifier = Modifier.size(16.dp))
        }
    }
}

/** Icon tile plus a title and subtitle, for empty and error states. */
@Composable
internal fun PanelMessage(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = SettingsStyle.Title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = SettingsStyle.Description)
        }
    }
}
