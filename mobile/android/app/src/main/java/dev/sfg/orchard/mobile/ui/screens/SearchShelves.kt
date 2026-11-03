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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.ArtworkTile

// Desktop SearchView colours: light pill for the active filter, faint glass for cards.
private val PillOn = Color(0xFFF1F0EC)
private val PillOnText = Color(0xFF171B17)
private val PillOff = Color(0x14FFFFFF)
private val CardFill = Color(0x10FFFFFF)
private val CardBorder = Color(0x14FFFFFF)
private val CardShape = RoundedCornerShape(16.dp)
private const val CoverDp = 140
private const val PortraitDp = 104

/** Home-style section heading with an optional "See all" pill. */
@Composable
internal fun SectionHeading(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            color = SettingsStyle.Title,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = SettingsStyle.Title,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, SettingsStyle.ButtonBorder, CircleShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
internal fun ScopeChips(selected: SearchScope, onSelect: (SearchScope) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SearchScope.entries) { scope ->
            val on = scope == selected
            Text(
                scope.label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = if (on) PillOnText else SettingsStyle.SegmentIdle,
                modifier = Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(if (on) PillOn else PillOff)
                    .clickable { onSelect(scope) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Best match as a card: big artwork, kind, and a play button for songs. */
@Composable
internal fun TopResultCard(item: CatalogItem, onOpen: () -> Unit, onPlay: () -> Unit) {
    val isSong = item is CatalogItem.Song
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardFill)
            .border(1.dp, CardBorder, CardShape)
            .clickable(onClick = if (isSong) onPlay else onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artist = item is CatalogItem.Performer
        ArtworkTile(item.artworkUrl, item.title, Modifier.size(96.dp), radius = if (artist) 48 else 10)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Top result", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = SettingsStyle.Caption)
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
                color = SettingsStyle.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                item.kindLabel(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = SettingsStyle.Description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (isSong) {
            IconButton(onClick = onPlay, modifier = Modifier.size(48.dp).background(PillOn, CircleShape)) {
                Icon(Icons.Rounded.PlayArrow, "Play", tint = PillOnText, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Sideways shelf of covers; [round] draws artist portraits with centred names. */
@Composable
internal fun <T : CatalogItem> CoverShelf(entries: List<T>, subtitle: (T) -> String, round: Boolean, onOpen: (T) -> Unit) {
    val size = if (round) PortraitDp else CoverDp
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.stableId }) { entry ->
            Column(
                Modifier.width(size.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpen(entry) },
                horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                ArtworkTile(entry.artworkUrl, entry.title, Modifier.size(size.dp), radius = if (round) size / 2 else 10)
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = SettingsStyle.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (round) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val sub = subtitle(entry)
                if (!round && sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = SettingsStyle.Description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun CatalogItem.kindLabel(): String = when (this) {
    is CatalogItem.Song -> "Song · ${track.artist}"
    is CatalogItem.Record -> "Album · ${album.artist}"
    is CatalogItem.Performer -> "Artist"
    is CatalogItem.Collection -> "Playlist · ${playlist.author}"
    is CatalogItem.Category -> ""
}
