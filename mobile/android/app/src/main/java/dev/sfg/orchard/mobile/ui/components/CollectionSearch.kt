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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import java.text.Normalizer

/**
 * Normalizes input string for search comparisons by stripping diacritics / accents
 * and converting to lowercase trimmed text.
 */
fun normalizeSearchText(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .lowercase()
        .trim()
}

/**
 * Filters a list of tracks against a search query across title, artist, and album.
 */
fun filterTracks(tracks: List<Track>, query: String): List<Track> {
    val normalizedQuery = normalizeSearchText(query)
    if (normalizedQuery.isBlank()) return tracks

    return tracks.filter { track ->
        val title = normalizeSearchText(track.title)
        val artist = normalizeSearchText(track.artist)
        val album = normalizeSearchText(track.album)

        title.contains(normalizedQuery) ||
            artist.contains(normalizedQuery) ||
            album.contains(normalizedQuery)
    }
}

/**
 * Filters a list of catalog items against a search query across title, artist, author, and subtitle.
 */
fun filterCatalogItems(items: List<CatalogItem>, query: String): List<CatalogItem> {
    val normalizedQuery = normalizeSearchText(query)
    if (normalizedQuery.isBlank()) return items

    return items.filter { item ->
        when (item) {
            is CatalogItem.Song -> {
                normalizeSearchText(item.track.title).contains(normalizedQuery) ||
                    normalizeSearchText(item.track.artist).contains(normalizedQuery) ||
                    normalizeSearchText(item.track.album).contains(normalizedQuery)
            }
            is CatalogItem.Record -> {
                normalizeSearchText(item.album.title).contains(normalizedQuery) ||
                    normalizeSearchText(item.album.artist).contains(normalizedQuery)
            }
            is CatalogItem.Performer -> {
                normalizeSearchText(item.artist.name).contains(normalizedQuery) ||
                    normalizeSearchText(item.artist.subtitle).contains(normalizedQuery)
            }
            is CatalogItem.Collection -> {
                normalizeSearchText(item.playlist.title).contains(normalizedQuery) ||
                    normalizeSearchText(item.playlist.author).contains(normalizedQuery) ||
                    normalizeSearchText(item.playlist.description).contains(normalizedQuery)
            }
            is CatalogItem.Category -> {
                normalizeSearchText(item.title).contains(normalizedQuery)
            }
        }
    }
}

/**
 * Frosted top search bar for collections (playlists, albums).
 */
@Composable
fun CollectionTopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String = "Find in playlist",
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Back / Dismiss button
        Surface(
            onClick = onClose,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.16f),
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Exit search",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Frosted Search input container
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.White.copy(alpha = 0.14f),
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White.copy(alpha = 0.50f),
                            ),
                            maxLines = 1,
                        )
                    }

                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        ),
                        cursorBrush = SolidColor(LocalAccent.current),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }

                if (query.isNotEmpty()) {
                    Surface(
                        onClick = { onQueryChange("") },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.20f),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
