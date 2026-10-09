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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.settings.CacheManager
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlin.math.roundToInt

/** Renders a megabyte count the way a listener thinks about storage. */
internal fun formatCacheSize(megabytes: Int): String =
    if (megabytes >= 1024) {
        val gigabytes = megabytes / 1024f
        if (gigabytes == gigabytes.toInt().toFloat()) "${gigabytes.toInt()} GB" else "%.1f GB".format(gigabytes)
    } else {
        "$megabytes MB"
    }

/**
 * Ceiling on the on-disk stream cache.
 *
 * Orchard keeps whole tracks rather than only what is ahead of the playhead, so this is worth
 * exposing: it decides how much of a listening session survives to be replayed instantly, and it
 * is the difference between a few albums and a library.
 */
@Composable
internal fun CacheSizeRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    val steps = OrchardSettings.CACHE_SIZE_STEPS_MB
    // The slider moves between stops rather than over megabytes, so the value is always a round
    // size and the control has somewhere obvious to land.
    val index = steps.indexOfFirst { it >= settings.cacheSizeMb }.takeIf { it >= 0 } ?: steps.lastIndex

    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(Icons.Rounded.Inventory2)
            Column(Modifier.weight(1f)) {
                RowTitle("Cached audio")
                RowSubtitle("Keep up to ${formatCacheSize(steps[index])} of played tracks for instant replay")
            }
            Text(
                formatCacheSize(steps[index]),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = SettingsStyle.SageSoft,
            )
        }
        Column(Modifier.padding(start = 52.dp)) {
            Slider(
                value = index.toFloat(),
                onValueChange = { onSettings(settings.copy(cacheSizeMb = steps[it.roundToInt()])) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = steps.size - 2,
                colors = settingsSliderColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatCacheSize(steps.first()),
                    color = SettingsStyle.Caption,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatCacheSize(steps.last()),
                    color = SettingsStyle.Caption,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                "A new limit applies next time playback starts",
                color = SettingsStyle.Caption,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Row displaying current cache size with an action to clear all temporary caches.
 * Prompts with an alert dialog before wiping stream cache, Coil image cache, and temporary files.
 */
@Composable
internal fun ClearCacheRow(
    cacheSizeBytes: Long,
    isClearing: Boolean,
    onClear: () -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val formattedSize = remember(cacheSizeBytes) { CacheManager.formatStorageSize(cacheSizeBytes) }

    SettingsRow(
        title = "Clear cache",
        subtitle = "Downloads and your library stay intact ($formattedSize in cache)",
        icon = Icons.Rounded.Delete,
        titleColor = CanopyColors.Danger,
        iconTint = CanopyColors.Danger,
        enabled = !isClearing,
        modifier = Modifier.clickable(enabled = !isClearing) { showConfirmDialog = true },
    ) {
        if (isClearing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = SettingsStyle.Sage,
            )
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    "Clear cache?",
                    color = CanopyColors.Text,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This will free up $formattedSize of cached audio streams, album artwork, and temporary files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CanopyColors.Text,
                    )
                    Text(
                        "Your downloaded offline songs and library will remain intact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CanopyColors.Muted,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onClear()
                    },
                ) {
                    Text(
                        "Clear",
                        color = CanopyColors.Danger,
                        fontWeight = FontWeight.Bold,
                    )
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
