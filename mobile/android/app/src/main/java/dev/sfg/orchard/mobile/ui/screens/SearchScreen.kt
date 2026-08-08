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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.components.OrchardSectionHeader
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.components.TrackRowShimmer
import dev.sfg.orchard.mobile.ui.components.OrchardChromeHeight
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun SearchScreen(
    query: String,
    state: LoadState<SearchResults>,
    history: List<String>,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClearHistory: () -> Unit,
    downloadedTrackIds: Set<String> = emptySet(),
    downloadingTrackIds: Set<String> = emptySet(),
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onOpenDetail: (String) -> Unit,
    onShare: ((Track) -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = OrchardChromeHeight)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
                Text("Search", style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(14.dp))
                SearchField(query, onQueryChange, onSubmit)
                Spacer(Modifier.height(18.dp))
            }
        }
        when (state) {
            LoadState.Idle -> history(history, onClearHistory, onSubmit)
            LoadState.Loading -> item {
                Column {
                    repeat(5) { TrackRowShimmer() }
                }
            }
            is LoadState.Content -> results(
                results = state.value,
                downloadedTrackIds = downloadedTrackIds,
                downloadingTrackIds = downloadingTrackIds,
                onPlay = onPlay,
                onPlayNext = onPlayNext,
                onAdd = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onDownloadTrack = onDownloadTrack,
                onRemoveDownloadTrack = onRemoveDownloadTrack,
                onOpen = onOpenDetail,
                onShare = onShare,
            )
            is LoadState.Empty -> item { MessagePanel("No matches", state.message) }
            is LoadState.Error -> item { MessagePanel("Search is unavailable", state.message, "Try again") { onSubmit(query) } }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSubmit: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search songs, artists, albums, playlists…") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = LocalAccent.current) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = CanopyColors.Muted)
            }
        },
        shape = CircleShape,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CanopyColors.Surface,
            unfocusedContainerColor = CanopyColors.Surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = CanopyColors.Text,
            unfocusedTextColor = CanopyColors.Text,
            disabledTextColor = CanopyColors.Muted,
        ),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.history(
    history: List<String>,
    onClear: () -> Unit,
    onSearch: (String) -> Unit,
) {
    if (history.isEmpty()) {
        item { MessagePanel("Start searching", "Find your favorite songs, artists, albums, or playlists.") }
        return
    }
    item { OrchardSectionHeader("Recent searches", action = "Clear all", onAction = onClear) }
    items(history, key = { it }) { value ->
        Surface(
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().clickable { onSearch(value) },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = CanopyColors.Muted)
                Text(value, modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge, color = CanopyColors.Text)
            }
        }
    }
}

private fun LazyListScope.results(
    results: SearchResults,
    downloadedTrackIds: Set<String>,
    downloadingTrackIds: Set<String> = emptySet(),
    onPlay: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAdd: ((Track) -> Unit)?,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onOpen: (String) -> Unit,
    onShare: ((Track) -> Unit)? = null,
) {
    if (results.tracks.isNotEmpty()) {
        item { OrchardSectionHeader("Songs") }
        itemsIndexed(results.tracks, key = { index, it -> "track:${it.id}_$index" }) { _, track ->
            val isDownloaded = downloadedTrackIds.contains(track.id)
            val isDownloading = downloadingTrackIds.contains(track.id)
            TrackRow(
                track = track,
                onPlay = { onPlay(track) },
                modifier = Modifier.padding(horizontal = 8.dp),
                onPlayNext = onPlayNext?.let { action -> { action(track) } },
                onAddToQueue = onAdd?.let { action -> { action(track) } },
                onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
                onDownload = onDownloadTrack?.let { action -> { action(track) } },
                onRemoveDownload = onRemoveDownloadTrack?.let { action -> { action(track.id) } },
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                onShare = onShare?.let { action -> { action(track) } },
                onViewAlbum = if (track.albumId.isNotBlank()) {{ onOpen(track.albumId) }} else null,
                onViewArtist = if (track.artistId.isNotBlank()) {{ onOpen(track.artistId) }} else null,
            )
        }
    }
    val shelves = listOf(
        "Albums" to results.albums.map { CatalogItem.Record(it) },
        "Artists" to results.artists.map { CatalogItem.Performer(it) },
        "Playlists" to results.playlists.map { CatalogItem.Collection(it) },
    )
    shelves.filter { it.second.isNotEmpty() }.forEach { (title, values) ->
        item { OrchardSectionHeader(title) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(values, key = { index, it -> "$title:${it.stableId}_$index" }) { _, item ->
                    CatalogCard(item, { onOpen(item.stableId) })
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
