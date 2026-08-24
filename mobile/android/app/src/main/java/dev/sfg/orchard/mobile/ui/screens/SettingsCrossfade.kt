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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MultipleStop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

/**
 * Crossfade toggle with its length slider folded underneath, so the length only takes up room
 * once it can actually do anything.
 */
@Composable
internal fun CrossfadeRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.MultipleStop)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    "Crossfade",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CanopyColors.Text,
                )
                Text(
                    if (settings.crossfadeEnabled) {
                        "Tracks overlap for ${settings.crossfadeSeconds}s"
                    } else {
                        "Blend the end of a track into the next"
                    },
                    color = CanopyColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                checked = settings.crossfadeEnabled,
                onCheckedChange = { onSettings(settings.copy(crossfadeEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = LocalAccent.current,
                    uncheckedThumbColor = CanopyColors.Muted,
                    uncheckedTrackColor = CanopyColors.Canvas,
                ),
            )
        }
        AnimatedVisibility(visible = settings.crossfadeEnabled) {
            Column {
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = settings.crossfadeSeconds.toFloat(),
                    onValueChange = {
                        onSettings(settings.copy(crossfadeSeconds = it.roundToInt()))
                    },
                    valueRange = OrchardSettings.MIN_CROSSFADE_SECONDS.toFloat()..
                        OrchardSettings.MAX_CROSSFADE_SECONDS.toFloat(),
                    // One stop per whole second between the ends.
                    steps = OrchardSettings.MAX_CROSSFADE_SECONDS - OrchardSettings.MIN_CROSSFADE_SECONDS - 1,
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
                        "${OrchardSettings.MIN_CROSSFADE_SECONDS}s",
                        color = CanopyColors.Eyebrow,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${OrchardSettings.MAX_CROSSFADE_SECONDS}s",
                        color = CanopyColors.Eyebrow,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 14.dp)) {
                        Text(
                            "Smart crossfade",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = CanopyColors.Text,
                        )
                        Text(
                            "Place the overlap on the beat and end it where the music does",
                            color = CanopyColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.smartCrossfade,
                        onCheckedChange = { onSettings(settings.copy(smartCrossfade = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = LocalAccent.current,
                            uncheckedThumbColor = CanopyColors.Muted,
                            uncheckedTrackColor = CanopyColors.Canvas,
                        ),
                    )
                }
            }
        }
    }
}
