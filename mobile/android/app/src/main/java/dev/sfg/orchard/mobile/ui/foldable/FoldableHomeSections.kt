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

package dev.sfg.orchard.mobile.ui.foldable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.ExplicitBadge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

private val ShelfGap = 36.dp
private val SongRowHeight = 60.dp

/** Section header plus a rail of catalog cards. [sectionKey] keys both list items when set. */
internal fun LazyListScope.foldableRail(
    title: String,
    items: List<CatalogItem>,
    onSeeAll: () -> Unit,
    onOpen: (CatalogItem) -> Unit,
    sectionKey: String? = null,
) {
    // Playlists get a wider card than albums and artists.
    val cardWidth = if (items.firstOrNull() is CatalogItem.Collection) 178.dp else 158.dp
    item(key = sectionKey?.let { "fold_head_$it" }) {
        Box(Modifier.riseIn(distance = 16f)) { FoldableSectionHeader(title = title, onSeeAll = onSeeAll) }
    }
    item(key = sectionKey?.let { "fold_rail_$it" }) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.stableId }) { index, item ->
                CatalogCard(
                    item = item,
                    onClick = { onOpen(item) },
                    modifier = Modifier.width(cardWidth).riseIn(index + 1, fromScale = 0.9f, cascadeOnScroll = true),
                )
            }
        }
        Spacer(Modifier.height(ShelfGap))
    }
}

/** Song shelf: columns of up to four rows with hairlines, three columns wide on large windows. */
internal fun LazyListScope.foldableSongShelf(
    title: String,
    songs: List<CatalogItem.Song>,
    available: Dp,
    onSeeAll: () -> Unit,
    onOpen: (CatalogItem) -> Unit,
    sectionKey: String? = null,
) {
    val columnWidth = (if (available >= 1000.dp) (available - 48.dp) / 3 - 20.dp else (available - 24.dp) / 2 - 28.dp)
        .coerceAtLeast(240.dp)
    item(key = sectionKey?.let { "fold_head_$it" }) {
        Box(Modifier.riseIn(distance = 16f)) { FoldableSectionHeader(title = title, onSeeAll = onSeeAll) }
    }
    item(key = sectionKey?.let { "fold_rail_$it" }) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(songs.chunked(4)) { column, rows ->
                Column(Modifier.width(columnWidth)) {
                    rows.forEachIndexed { row, song ->
                        FoldableSongRow(song, separator = row != rows.lastIndex, onClick = { onOpen(song) })
                    }
                }
            }
        }
        Spacer(Modifier.height(ShelfGap))
    }
}

@Composable
private fun FoldableSongRow(song: CatalogItem.Song, separator: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(SongRowHeight).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtworkTile(song.artworkUrl, song.title, Modifier.size(44.dp), radius = 6)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = CanopyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (song.explicit) ExplicitBadge()
                }
                Text(
                    song.track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (separator) {
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        }
    }
}

@Composable
internal fun FoldableSectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CanopyColors.Text)
        Row(
            modifier = Modifier.clip(CircleShape).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("See all", style = MaterialTheme.typography.labelLarge, color = CanopyColors.Muted)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = CanopyColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun FoldableOfflineBanner(downloadedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = CanopyColors.Surface,
        border = BorderStroke(1.dp, LocalAccent.current.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Offline Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CanopyColors.Text,
                )
                Text(
                    if (downloadedCount > 0)
                        "Showing downloaded music ($downloadedCount ${if (downloadedCount == 1) "song" else "songs"})"
                    else "No internet connection detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Muted,
                )
            }
        }
    }
}
