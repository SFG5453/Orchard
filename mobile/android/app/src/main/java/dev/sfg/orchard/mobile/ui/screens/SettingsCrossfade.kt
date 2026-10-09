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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LocalMaxActive
import dev.sfg.orchard.mobile.model.OrchardSettings
import kotlin.math.roundToInt

/**
 * Crossfade toggle with its length slider folded underneath, so the length only takes up room
 * once it can actually do anything.
 */
@Composable
internal fun CrossfadeRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    val maxActive = LocalMaxActive.current
    Column {
        ToggleRow(
            title = "Crossfade",
            subtitle = if (settings.crossfadeEnabled) {
                "Tracks overlap for ${settings.crossfadeSeconds}s"
            } else {
                "Blend the end of a track into the next"
            },
            icon = Icons.Rounded.Waves,
            checked = settings.crossfadeEnabled,
            onChecked = { onSettings(settings.copy(crossfadeEnabled = it)) },
        )
        AnimatedVisibility(visible = settings.crossfadeEnabled) {
            Column {
                // Indented to sit under the title, past the icon tile.
                Column(Modifier.padding(start = 72.dp, end = 20.dp, bottom = 12.dp)) {
                    Slider(
                        value = settings.crossfadeSeconds.toFloat(),
                        onValueChange = { onSettings(settings.copy(crossfadeSeconds = it.roundToInt())) },
                        valueRange = OrchardSettings.MIN_CROSSFADE_SECONDS.toFloat()..
                            OrchardSettings.MAX_CROSSFADE_SECONDS.toFloat(),
                        // One stop per whole second between the ends.
                        steps = OrchardSettings.MAX_CROSSFADE_SECONDS - OrchardSettings.MIN_CROSSFADE_SECONDS - 1,
                        colors = settingsSliderColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "${OrchardSettings.MIN_CROSSFADE_SECONDS}s",
                            color = SettingsStyle.Caption,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${OrchardSettings.MAX_CROSSFADE_SECONDS}s",
                            color = SettingsStyle.Caption,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                PanelDivider()
                ToggleRow(
                    title = "Adaptive mix",
                    subtitle = if (maxActive) {
                        "Unavailable while audio quality is Max"
                    } else {
                        "Place the overlap on the beat and end it where the music does"
                    },
                    icon = Icons.Rounded.AutoAwesome,
                    checked = settings.smartCrossfade && !maxActive,
                    onChecked = { onSettings(settings.copy(smartCrossfade = it)) },
                    enabled = !maxActive,
                )
            }
        }
    }
}
