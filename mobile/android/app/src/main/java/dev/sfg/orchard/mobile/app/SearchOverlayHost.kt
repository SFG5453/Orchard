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

package dev.sfg.orchard.mobile.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.PlaylistPickerSheet
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.screens.SearchActions
import dev.sfg.orchard.mobile.ui.screens.SearchOverlay

/** Wires [SearchOverlay] to the view model. Playing or opening a result closes it. */
@Composable
internal fun SearchOverlayHost(
    open: Boolean,
    onClose: () -> Unit,
    nav: NavHostController,
    viewModel: OrchardViewModel,
    library: LibrarySnapshot,
    backdropArtworkUrl: String,
    backdropPlaying: Boolean,
    showBackdrop: Boolean,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val downloadedTrackIds by viewModel.downloadedTrackIds.collectAsStateWithLifecycle()
    val downloadingTrackIds by viewModel.downloadingTrackIds.collectAsStateWithLifecycle()
    var pickerTrack by remember { mutableStateOf<Track?>(null) }

    // An empty bar on the next open; the debounce flips results back to idle by itself.
    val close = {
        viewModel.updateQuery("")
        onClose()
    }
    // A result the listener acted on counts as a search worth remembering.
    val commit = { if (query.isNotBlank()) viewModel.runSearch(query) }

    val actions = SearchActions(
        onQueryChange = viewModel::updateQuery,
        onSubmit = viewModel::runSearch,
        onClearHistory = viewModel::clearSearchHistory,
        onRemoveHistoryItem = viewModel::removeSearchHistoryItem,
        onPlay = { commit(); viewModel.play(it, "Search"); close() },
        onPlayVideo = { commit(); viewModel.playMusicVideo(it, "Search"); onOpenPlayer(); close() },
        onPlayNext = viewModel::playNext,
        onAddToQueue = viewModel::addToQueue,
        onAddToPlaylist = { pickerTrack = it },
        onDownloadTrack = viewModel::downloadTrack,
        onRemoveDownloadTrack = viewModel::removeDownload,
        onShare = viewModel::shareTrack,
        onOpenDetail = { id ->
            commit()
            viewModel.openDetail(id)
            nav.navigate(Routes.detail(id))
            close()
        },
        onClose = close,
    )

    SearchOverlay(
        open = open,
        query = query,
        state = search,
        history = history,
        downloadedTrackIds = downloadedTrackIds,
        downloadingTrackIds = downloadingTrackIds,
        actions = actions,
        backdropArtworkUrl = backdropArtworkUrl,
        backdropPlaying = backdropPlaying,
        showBackdrop = showBackdrop,
        modifier = modifier,
    )

    pickerTrack?.let { track ->
        PlaylistPickerSheet(
            track = track,
            playlists = viewModel.playlistChoices(track, library.savedPlaylists),
            onDismiss = { pickerTrack = null },
            onSelect = { playlist ->
                pickerTrack = null
                viewModel.addTrackToPlaylist(playlist.id, track)
            },
        )
    }
}
