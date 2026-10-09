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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.local.LocalLibraryRepository
import dev.sfg.orchard.mobile.ui.components.rememberCoverPicker
import dev.sfg.orchard.mobile.ui.components.rememberFolderPicker
import dev.sfg.orchard.mobile.ui.components.rememberSongPicker

/** What a playlist kept on this phone can do beyond what an online one can. */
class LocalPlaylistEdit(
    val onAddSongs: () -> Unit,
    val onAddFolder: () -> Unit,
    val onPickCover: () -> Unit,
    /** Null when the playlist already shows its generated collage. */
    val onClearCover: (() -> Unit)?,
    val onRename: (String) -> Unit,
    val onDelete: () -> Unit,
)

/** Wires the pickers and repository calls for one local playlist. */
@Composable
internal fun rememberLocalPlaylistEdit(
    repository: LocalLibraryRepository,
    playlistId: String,
    hasCustomCover: Boolean,
    onDeleted: () -> Unit,
): LocalPlaylistEdit {
    val addSongs = rememberSongPicker { uris: List<Uri> -> repository.importUris(uris, playlistId) }
    val addFolder = rememberFolderPicker { tree: Uri -> repository.importTree(tree, playlistId) }
    val pickCover = rememberCoverPicker { uri: Uri -> repository.setPlaylistCover(playlistId, uri) }
    return remember(playlistId, hasCustomCover, addSongs, addFolder, pickCover) {
        LocalPlaylistEdit(
            onAddSongs = addSongs,
            onAddFolder = addFolder,
            onPickCover = pickCover,
            onClearCover = if (hasCustomCover) ({ repository.clearPlaylistCover(playlistId) }) else null,
            onRename = { repository.renamePlaylist(playlistId, it) },
            onDelete = {
                repository.deletePlaylist(playlistId)
                onDeleted()
            },
        )
    }
}

/**
 * Edit mode for a playlist. While editing, rows show move and remove buttons; a playlist on this
 * phone also offers its songs, cover, name and deletion here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistEditBar(
    title: String,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    local: LocalPlaylistEdit?,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = editing,
            onClick = { onEditingChange(!editing) },
            label = { Text(if (editing) "Done" else "Edit") },
        )
        if (local != null && editing) {
            AssistChip(onClick = local.onAddSongs, label = { Text("Add songs") })
            AssistChip(onClick = local.onAddFolder, label = { Text("Add folder") })
            AssistChip(onClick = local.onPickCover, label = { Text("Cover") })
            local.onClearCover?.let { AssistChip(onClick = it, label = { Text("Auto cover") }) }
            AssistChip(onClick = { renaming = true }, label = { Text("Rename") })
            AssistChip(onClick = { confirmingDelete = true }, label = { Text("Delete") })
        }
    }
    if (local != null && renaming) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(150) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { local.onRename(name); renaming = false }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (local != null && confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete “$title”?") },
            text = { Text("Your songs stay on your phone. Only the playlist goes away.") },
            confirmButton = { TextButton(onClick = { confirmingDelete = false; local.onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}
