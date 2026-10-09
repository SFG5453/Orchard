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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.HomeBackdrop
import dev.sfg.orchard.mobile.ui.components.TrackRow
import dev.sfg.orchard.mobile.ui.components.TrackRowShimmer
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn

// Light enough that the panes have something to blur.
private val GlassScrim = Color(0x59080B09)
private const val PanelMaxWidthDp = 640
private const val SongsOnAll = 4
private const val OthersOnAll = 3

/** Everything the overlay can ask of its host. Closing is the host's call. */
internal class SearchActions(
    val onQueryChange: (String) -> Unit,
    val onSubmit: (String) -> Unit,
    val onClearHistory: () -> Unit,
    val onRemoveHistoryItem: (String) -> Unit,
    val onPlay: (Track) -> Unit,
    val onPlayVideo: (Track) -> Unit,
    val onPlayNext: ((Track) -> Unit)?,
    val onAddToQueue: ((Track) -> Unit)?,
    val onAddToPlaylist: ((Track) -> Unit)?,
    val onDownloadTrack: ((Track) -> Unit)?,
    val onRemoveDownloadTrack: ((String) -> Unit)?,
    val onShare: ((Track) -> Unit)?,
    val onOpenDetail: (String) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Spotlight-style search: a bar and one panel over whatever screen is underneath. It has no route,
 * so there is no idle page to land on and nothing to pop when it closes.
 */
@Composable
internal fun SearchOverlay(
    open: Boolean,
    query: String,
    state: LoadState<SearchResults>,
    history: List<String>,
    downloadedTrackIds: Set<String>,
    downloadingTrackIds: Set<String>,
    actions: SearchActions,
    backdropArtworkUrl: String,
    backdropPlaying: Boolean,
    showBackdrop: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(open, modifier, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        BackHandler(onBack = actions.onClose)
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = remember { FocusRequester() }
        // Focus first, ask questions never: the field is the only thing here worth tapping.
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
        // The IME outlives the composable otherwise and hovers over the screen we just revealed.
        DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

        Box(Modifier.fillMaxSize()) {
            if (showBackdrop) HomeBackdrop(true, backdropArtworkUrl, backdropPlaying)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GlassScrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions.onClose),
            )
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = PanelMaxWidthDp.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                SearchBar(query, actions, focus)
                Spacer(Modifier.height(8.dp))
                // Wraps its rows and stops at the keyboard; the empty remainder falls through to the scrim.
                SearchPanel(
                    query, state, history, downloadedTrackIds, downloadingTrackIds, actions,
                    Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, actions: SearchActions, focus: FocusRequester) {
    val shape = RoundedCornerShape(16.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(shape)
                .glassPane(shape, GlassTone.CONTROL)
                .border(1.dp, SettingsStyle.Sage.copy(alpha = 0.55f), shape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = SettingsStyle.Sage, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(color = SettingsStyle.Title)
                if (query.isEmpty()) {
                    Text("Search songs, artists, links", style = textStyle, color = SettingsStyle.Description)
                }
                BasicTextField(
                    value = query,
                    onValueChange = actions.onQueryChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(SettingsStyle.Sage),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { actions.onSubmit(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { actions.onQueryChange("") }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, "Clear search", tint = SettingsStyle.Chevron, modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(
            Modifier.height(52.dp).clickable(onClick = actions.onClose).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Cancel", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = SettingsStyle.Sage)
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    state: LoadState<SearchResults>,
    history: List<String>,
    downloadedTrackIds: Set<String>,
    downloadingTrackIds: Set<String>,
    actions: SearchActions,
    modifier: Modifier,
) {
    var scope by rememberSaveable { mutableStateOf(SearchScope.All) }
    Surface(
        color = Color.Transparent,
        shape = SettingsStyle.PanelShape,
        border = BorderStroke(1.dp, SettingsStyle.PanelBorder),
        // Swallows taps on the panel's own padding so they do not dismiss through to the scrim.
        modifier = modifier.fillMaxWidth().glassPane(SettingsStyle.PanelShape, GlassTone.OVERLAY).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 6.dp)) {
            when (state) {
                LoadState.Idle -> idle(history, actions)
                LoadState.Loading -> items(4) { TrackRowShimmer() }
                is LoadState.Content -> results(state.value, query, scope, { scope = it }, downloadedTrackIds, downloadingTrackIds, actions)
                is LoadState.Empty -> item {
                    PanelMessage(Icons.Rounded.SearchOff, state.message, "Check the spelling, or try an artist or album name.")
                }
                is LoadState.Error -> {
                    item { PanelMessage(Icons.Rounded.CloudOff, "Search is unavailable", state.message) }
                    item {
                        Text(
                            "Try again",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = SettingsStyle.Sage,
                            modifier = Modifier.fillMaxWidth().clickable { actions.onSubmit(query) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.idle(history: List<String>, actions: SearchActions) {
    item { PanelCaption("Browse") }
    item { BrowseTiles(actions.onOpenDetail) }
    if (history.isEmpty()) return
    item { Column { PanelDivider(); PanelCaption("Recent", "Clear all", actions.onClearHistory) } }
    items(history.size, key = { history[it] }) { index ->
        val value = history[index]
        HistoryRow(
            value = value,
            onPick = { actions.onQueryChange(value); actions.onSubmit(value) },
            onRemove = { actions.onRemoveHistoryItem(value) },
        )
    }
}

private fun LazyListScope.results(
    results: SearchResults,
    query: String,
    scope: SearchScope,
    onScope: (SearchScope) -> Unit,
    downloadedTrackIds: Set<String>,
    downloadingTrackIds: Set<String>,
    actions: SearchActions,
) {
    val all = scope == SearchScope.All
    item { ScopeChips(scope, onScope) }
    val top = if (all) topResult(results, query) else null
    if (top != null) {
        item {
            Column {
                PanelDivider()
                TopResultRow(
                    item = top,
                    onOpen = { actions.onOpenDetail(top.stableId) },
                    onPlay = { (top as? CatalogItem.Song)?.let { actions.onPlay(it.track) } },
                )
            }
        }
    }
    val songs = results.tracks.filterNot { top is CatalogItem.Song && it.id == top.track.id }
    if ((all || scope == SearchScope.Songs) && songs.isNotEmpty()) {
        item { Column { PanelDivider(); PanelCaption("Songs", "See all".takeIf { all && songs.size > SongsOnAll }) { onScope(SearchScope.Songs) } } }
        itemsIndexed(if (all) songs.take(SongsOnAll) else songs, key = { index, it -> "track:${it.id}_$index" }) { _, track ->
            TrackRow(
                track = track,
                onPlay = { actions.onPlay(track) },
                modifier = Modifier.padding(horizontal = 4.dp),
                onPlayNext = actions.onPlayNext?.let { act -> { act(track) } },
                onAddToQueue = actions.onAddToQueue?.let { act -> { act(track) } },
                onAddToPlaylist = actions.onAddToPlaylist?.let { act -> { act(track) } },
                onDownload = actions.onDownloadTrack?.let { act -> { act(track) } },
                onRemoveDownload = actions.onRemoveDownloadTrack?.let { act -> { act(track.id) } },
                isDownloaded = track.id in downloadedTrackIds,
                isDownloading = track.id in downloadingTrackIds,
                onShare = actions.onShare?.let { act -> { act(track) } },
                onViewAlbum = if (track.albumId.isNotBlank()) {{ actions.onOpenDetail(track.albumId) }} else null,
                onViewArtist = if (track.artistId.isNotBlank()) {{ actions.onOpenDetail(track.artistId) }} else null,
            )
        }
    }
    videoResults(results.videos, scope, onScope, OthersOnAll, actions.onPlayVideo)
    shelf("Albums", SearchScope.Albums, scope, onScope, results.albums.map { CatalogItem.Record(it) }, { it.album.artist }, false, actions)
    shelf("Artists", SearchScope.Artists, scope, onScope, results.artists.map { CatalogItem.Performer(it) }, { it.artist.subtitle }, true, actions)
    shelf("Playlists", SearchScope.Playlists, scope, onScope, results.playlists.map { CatalogItem.Collection(it) }, { it.playlist.author }, false, actions)
}

private fun <T : CatalogItem> LazyListScope.shelf(
    title: String,
    own: SearchScope,
    scope: SearchScope,
    onScope: (SearchScope) -> Unit,
    entries: List<T>,
    subtitle: (T) -> String,
    round: Boolean,
    actions: SearchActions,
) {
    val all = scope == SearchScope.All
    if (entries.isEmpty() || !(all || scope == own)) return
    item { Column { PanelDivider(); PanelCaption(title, "See all".takeIf { all && entries.size > OthersOnAll }) { onScope(own) } } }
    itemsIndexed(if (all) entries.take(OthersOnAll) else entries, key = { index, it -> "$title:${it.stableId}_$index" }) { _, entry ->
        ResultRow(entry, subtitle(entry), round) { actions.onOpenDetail(entry.stableId) }
    }
}
