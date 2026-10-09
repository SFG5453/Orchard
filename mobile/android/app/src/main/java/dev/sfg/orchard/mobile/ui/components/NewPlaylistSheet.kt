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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Names a new playlist, on YouTube Music or on this phone. [youtubeAvailable] is false when no
 * account is signed in, which leaves only the local choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewPlaylistSheet(
    youtubeAvailable: Boolean,
    onDismiss: () -> Unit,
    /** [local] picks where it lives; [thenAddSongs] opens the file picker once a local playlist exists. */
    onCreate: (title: String, local: Boolean, thenAddSongs: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var local by remember { mutableStateOf(!youtubeAvailable) }
    val canCreate = title.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CanopyColors.Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CanopyColors.Muted.copy(alpha = 0.4f)) },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("New playlist", style = MaterialTheme.typography.titleLarge, color = CanopyColors.Text)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !local,
                    enabled = youtubeAvailable,
                    onClick = { local = false },
                    label = { Text("YouTube Music") },
                )
                FilterChip(selected = local, onClick = { local = true }, label = { Text("On this phone") })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (local) "Songs stay where they are on your phone. Orchard only remembers the list."
                else "Created on YouTube Music, private until you share it.",
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(150) },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (local) {
                    OutlinedButton(enabled = canCreate, onClick = { onCreate(title, true, true) }) { Text("Create and add songs") }
                }
                Button(enabled = canCreate, onClick = { onCreate(title, local, false) }) { Text("Create") }
            }
        }
    }
}
