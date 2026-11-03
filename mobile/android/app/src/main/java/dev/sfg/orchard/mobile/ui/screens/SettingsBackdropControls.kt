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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.OrchardSettings
import kotlin.math.roundToInt

/** Speed, intensity, saturation and brightness of the animated background, shown while it is on. */
@Composable
internal fun BackdropControls(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    AnimatedVisibility(visible = settings.animatedBackground) {
        Column {
            PanelDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                BackdropSlider("Speed", settings.backdropSpeed, 0f..3f) { onSettings(settings.copy(backdropSpeed = it)) }
                BackdropSlider("Intensity", settings.backdropIntensity, 0f..2f) { onSettings(settings.copy(backdropIntensity = it)) }
                BackdropSlider("Saturation", settings.backdropSaturation, 0f..3f) { onSettings(settings.copy(backdropSaturation = it)) }
                BackdropSlider("Brightness", settings.backdropBrightness, 0f..2f) { onSettings(settings.copy(backdropBrightness = it)) }
            }
            PanelDivider()
            SettingsRow(
                title = "Reset controls",
                subtitle = "Restore the default background look",
                modifier = Modifier.clickable {
                    onSettings(
                        settings.copy(
                            backdropSpeed = OrchardSettings.DEFAULT_BACKDROP_SPEED,
                            backdropIntensity = OrchardSettings.DEFAULT_BACKDROP_INTENSITY,
                            backdropSaturation = OrchardSettings.DEFAULT_BACKDROP_SATURATION,
                            backdropBrightness = OrchardSettings.DEFAULT_BACKDROP_BRIGHTNESS,
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun BackdropSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = SettingsStyle.Caption, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", color = SettingsStyle.Caption, style = MaterialTheme.typography.labelMedium)
    }
    Slider(
        value = value,
        onValueChange = onValue,
        valueRange = range,
        colors = settingsSliderColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
