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
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.sfg.orchard.mobile.model.Track

/**
 * Helper to produce the collection download / delete callback based on current download state.
 */
fun collectionDownloadAction(
    tracks: List<Track>,
    downloadedTrackIds: Set<String>,
    onDownloadTracks: ((List<Track>) -> Unit)?,
    onRemoveDownloadTracks: ((List<Track>) -> Unit)?,
): (() -> Unit)? {
    if (onDownloadTracks == null || onRemoveDownloadTracks == null || tracks.isEmpty()) {
        return null
    }
    val allDownloaded = tracks.all { downloadedTrackIds.contains(it.id) }
    return {
        if (allDownloaded) {
            onRemoveDownloadTracks(tracks)
        } else {
            onDownloadTracks(tracks)
        }
    }
}

/** Floating album/playlist chrome: back circle plus a search, share and overflow pill. */
@Composable
fun CollectionTopBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    isSaved: Boolean,
    scrimProgress: State<Float>,
    modifier: Modifier = Modifier,
    onAbout: (() -> Unit)? = null,
    onBestMix: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    isSearching: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    onCloseSearch: (() -> Unit)? = null,
    searchPlaceholder: String = "Find in playlist",
    aboutLabel: String = "About",
    onDownload: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
) {
    if (isSearching && onSearchQueryChange != null && onCloseSearch != null) {
        // Opaque enough that the field never sits on raw artwork.
        CollectionTopSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onClose = onCloseSearch,
            placeholder = searchPlaceholder,
            modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        )
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    val noun = if (aboutLabel.contains("album", true)) "album" else "playlist"

    DetailFloatingChrome(onBack = onBack, scrimProgress = scrimProgress, modifier = modifier) {
        if (onSearch != null) {
            ChromeAction(Icons.Rounded.Search, "Search in collection", onSearch)
        }
        ChromeAction(Icons.Rounded.IosShare, "Share", onShare)
        Box {
            ChromeAction(Icons.Rounded.MoreHoriz, "More options", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onDownload != null) {
                    DropdownMenuItem(
                        text = { Text(if (isDownloaded) "Remove download" else "Download $noun") },
                        onClick = {
                            menuOpen = false
                            onDownload()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (isSaved) "Remove from library" else "Add to library") },
                    onClick = {
                        menuOpen = false
                        onSave()
                    },
                )
                if (onAbout != null) {
                    DropdownMenuItem(
                        text = { Text(aboutLabel) },
                        onClick = {
                            menuOpen = false
                            onAbout()
                        },
                    )
                }
                if (onBestMix != null) {
                    DropdownMenuItem(
                        text = { Text("Play with Best Mix") },
                        onClick = {
                            menuOpen = false
                            onBestMix()
                        },
                    )
                }
            }
        }
    }
}
