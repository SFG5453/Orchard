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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
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

    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.Storage)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    "Cached audio",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CanopyColors.Text,
                )
                Text(
                    "Keep up to ${formatCacheSize(steps[index])} of played tracks for instant replay",
                    color = CanopyColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                formatCacheSize(steps[index]),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = LocalAccent.current,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = index.toFloat(),
            onValueChange = { onSettings(settings.copy(cacheSizeMb = steps[it.roundToInt()])) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = steps.size - 2,
            colors = SliderDefaults.colors(
                thumbColor = LocalAccent.current,
                activeTrackColor = LocalAccent.current,
                inactiveTrackColor = CanopyColors.Canvas,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatCacheSize(steps.first()),
                color = CanopyColors.Eyebrow,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatCacheSize(steps.last()),
                color = CanopyColors.Eyebrow,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            "A new limit applies next time playback starts",
            color = CanopyColors.Eyebrow,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
