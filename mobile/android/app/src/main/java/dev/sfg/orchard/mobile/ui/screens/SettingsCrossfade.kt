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
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LocalMaxActive
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.playback.smart.npu.NpuSupport
import dev.sfg.orchard.mobile.ui.components.NpuPrompt
import kotlin.math.roundToInt

/**
 * Crossfade toggle with its length slider folded underneath, so the length only takes up room
 * once it can actually do anything.
 */
@Composable
internal fun CrossfadeRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    val maxActive = LocalMaxActive.current
    val npu by NpuSupport.state.collectAsStateWithLifecycle()
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
                    onChecked = { on ->
                        // A supported NPU that is not set up yet is offered first; the offer turns adaptive mix on.
                        if (on && npu.supported && !npu.ready) NpuPrompt.requested = true
                        else onSettings(settings.copy(smartCrossfade = on))
                    },
                    enabled = !maxActive,
                )
                AnimatedVisibility(visible = npu.supported && npu.ready) {
                    Column {
                        PanelDivider()
                        ToggleRow(
                            title = "NPU Support",
                            subtitle = when {
                                npu.compiling -> "Optimizing for your ${npu.device?.vendor?.brand} NPU…"
                                npu.enabled -> "Adaptive mix runs on your ${npu.device?.vendor?.brand} NPU"
                                else -> "Run adaptive mix on your ${npu.device?.vendor?.brand} NPU instead of the CPU"
                            },
                            icon = Icons.Rounded.Memory,
                            checked = npu.enabled,
                            onChecked = NpuSupport::setEnabled,
                            enabled = !npu.compiling,
                        )
                    }
                }
            }
        }
    }
}
