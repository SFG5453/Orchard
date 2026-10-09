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

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.local.LocalLibraryRepository
import dev.sfg.orchard.mobile.ui.components.NewPlaylistSheet
import dev.sfg.orchard.mobile.ui.components.rememberFolderPicker
import dev.sfg.orchard.mobile.ui.components.rememberSongPicker

/**
 * The library's two creation buttons: a new playlist (YouTube Music or this phone) and a separate
 * one for bringing in files from this phone. Both sit above the filter chips on every tab.
 */
@Composable
internal fun LocalLibraryActions(
    repository: LocalLibraryRepository,
    busy: Boolean,
    youtubeAvailable: Boolean,
    onCreateYouTubePlaylist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val addSongs = rememberSongPicker { uris: List<Uri> -> repository.importUris(uris) }
    val addFolder = rememberFolderPicker { tree: Uri -> repository.importTree(tree) }
    // A freshly made local playlist opens straight into its song picker.
    var addSongsTo by remember { mutableStateOf("") }
    val addSongsToPlaylist = rememberSongPicker { uris: List<Uri> -> repository.importUris(uris, addSongsTo) }

    Row(
        Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { creating = true },
            label = { Text("New playlist") },
            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
        )
        Box {
            AssistChip(
                onClick = { menuOpen = true },
                label = { Text("Add local files") },
                leadingIcon = {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Add songs") }, onClick = { menuOpen = false; addSongs() })
                DropdownMenuItem(text = { Text("Add a folder") }, onClick = { menuOpen = false; addFolder() })
            }
        }
    }

    if (creating) {
        NewPlaylistSheet(
            youtubeAvailable = youtubeAvailable,
            onDismiss = { creating = false },
            onCreate = { title, local, thenAddSongs ->
                creating = false
                if (!local) {
                    onCreateYouTubePlaylist(title)
                } else {
                    val id = repository.createPlaylist(title)
                    if (thenAddSongs) {
                        addSongsTo = id
                        addSongsToPlaylist()
                    } else {
                        onOpenPlaylist(id)
                    }
                }
            },
        )
    }
}
