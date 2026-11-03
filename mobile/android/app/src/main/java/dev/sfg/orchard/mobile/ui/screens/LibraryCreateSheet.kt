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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.local.LocalLibraryRepository
import dev.sfg.orchard.mobile.ui.components.NewPlaylistSheet
import dev.sfg.orchard.mobile.ui.components.rememberFolderPicker
import dev.sfg.orchard.mobile.ui.components.rememberSongPicker
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

private enum class CreateStep { NONE, MENU, PLAYLIST }

/**
 * The library's Create button: one sheet with New playlist (the streaming service or this phone), Add songs
 * and Add a folder. Stays composed while closed so picker results still arrive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryCreateSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    repository: LocalLibraryRepository,
    service: String?,
    onCreateOnlinePlaylist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    val step = when {
        naming -> CreateStep.PLAYLIST
        open -> CreateStep.MENU
        else -> CreateStep.NONE
    }
    val addSongs = rememberSongPicker { uris: List<Uri> -> repository.importUris(uris) }
    val addFolder = rememberFolderPicker { tree: Uri -> repository.importTree(tree) }
    // A freshly made local playlist opens straight into its song picker.
    var addSongsTo by remember { mutableStateOf("") }
    val addSongsToPlaylist = rememberSongPicker { uris: List<Uri> -> repository.importUris(uris, addSongsTo) }

    if (step == CreateStep.MENU) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CanopyColors.Surface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = CanopyColors.Muted.copy(alpha = 0.4f)) },
        ) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 28.dp)) {
                Text(
                    "Create",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                CreateRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "New playlist",
                    if (service != null) "On $service or this phone" else "Kept on this phone", accent = true) {
                    naming = true
                    onDismiss()
                }
                CreateRow(Icons.Rounded.AudioFile, "Add songs", "Pick audio files from this phone") {
                    onDismiss()
                    addSongs()
                }
                CreateRow(Icons.Rounded.CreateNewFolder, "Add a folder", "Import every song in a folder") {
                    onDismiss()
                    addFolder()
                }
            }
        }
    }

    if (step == CreateStep.PLAYLIST) {
        NewPlaylistSheet(
            service = service,
            onDismiss = { naming = false },
            onCreate = { title, local, thenAddSongs ->
                naming = false
                if (!local) {
                    onCreateOnlinePlaylist(title)
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

@Composable
private fun CreateRow(icon: ImageVector, title: String, subtitle: String, accent: Boolean = false, onClick: () -> Unit) {
    val tile = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(tile).background(if (accent) SettingsStyle.Sage else Color(0x14FFFFFF), tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (accent) OnSage else CanopyColors.Text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = CanopyColors.Text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = CanopyColors.Muted)
        }
    }
}
