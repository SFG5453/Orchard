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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.settings.CacheManager
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Deletes every offline track and its saved motion cover after a confirmation. */
@Composable
internal fun DeleteDownloadsRow(downloadedBytes: Long, onDelete: () -> Unit) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val formattedSize = remember(downloadedBytes) { CacheManager.formatStorageSize(downloadedBytes) }

    SettingsRow(
        title = "Delete all downloads",
        subtitle = "Removes offline songs and motion covers ($formattedSize used)",
        icon = Icons.Rounded.DeleteForever,
        titleColor = CanopyColors.Danger,
        iconTint = CanopyColors.Danger,
        modifier = Modifier.clickable { showConfirmDialog = true },
    )

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text("Delete all downloads?", color = CanopyColors.Text, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This frees $formattedSize and cancels downloads in progress.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CanopyColors.Text,
                    )
                    Text(
                        "Songs remain in your library and can be downloaded again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = CanopyColors.Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel", color = CanopyColors.Muted)
                }
            },
            containerColor = CanopyColors.Surface,
            shape = RoundedCornerShape(16.dp),
        )
    }
}
