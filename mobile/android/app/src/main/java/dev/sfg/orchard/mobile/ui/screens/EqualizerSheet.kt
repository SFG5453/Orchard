package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.EQ_BANDS
import dev.sfg.orchard.mobile.model.EQ_PRESETS
import dev.sfg.orchard.mobile.model.EqualizerConfig
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqualizerRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showSheet = true }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        RowIcon(Icons.Rounded.Equalizer)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                "Audio Equalizer",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
            )
            Text(
                if (settings.equalizerConfig.enabled) {
                    "Enabled (${EQ_PRESETS.find { it.id == settings.equalizerConfig.presetId }?.label ?: "Custom"})"
                } else {
                    "Disabled"
                },
                color = CanopyColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(
            checked = settings.equalizerConfig.enabled,
            onCheckedChange = { 
                onSettings(settings.copy(
                    equalizerConfig = settings.equalizerConfig.copy(enabled = it)
                )) 
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = LocalAccent.current,
                uncheckedThumbColor = CanopyColors.Muted,
                uncheckedTrackColor = CanopyColors.Canvas,
            ),
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = CanopyColors.Surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            EqualizerContent(
                config = settings.equalizerConfig,
                onConfigChange = { newConfig ->
                    onSettings(settings.copy(equalizerConfig = newConfig))
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerContent(config: EqualizerConfig, onConfigChange: (EqualizerConfig) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Equalizer",
            style = MaterialTheme.typography.headlineMedium,
            color = CanopyColors.Text,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Presets Dropdown
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            val currentLabel = EQ_PRESETS.find { it.id == config.presetId }?.label ?: "Custom"
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    focusedTextColor = CanopyColors.Text,
                    unfocusedTextColor = CanopyColors.Text,
                ),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                EQ_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            onConfigChange(
                                config.copy(
                                    presetId = preset.id,
                                    gains = preset.gains
                                )
                            )
                            expanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Preamp Slider
        Text("Preamp: ${config.preampDb.roundToInt()} dB", color = CanopyColors.Text)
        Slider(
            value = config.preampDb,
            onValueChange = { 
                onConfigChange(config.copy(preampDb = it, presetId = "custom"))
            },
            valueRange = EqualizerConfig.MIN_PREAMP_DB..EqualizerConfig.MAX_PREAMP_DB,
            colors = SliderDefaults.colors(
                thumbColor = LocalAccent.current,
                activeTrackColor = LocalAccent.current,
                inactiveTrackColor = CanopyColors.Canvas
            )
        )
        Spacer(Modifier.height(16.dp))
        
        // Bass Boost Slider
        Text("Bass Boost: ${(config.bassBoost * 100).roundToInt()}%", color = CanopyColors.Text)
        Slider(
            value = config.bassBoost,
            onValueChange = { 
                onConfigChange(config.copy(bassBoost = it, presetId = "custom"))
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = LocalAccent.current,
                activeTrackColor = LocalAccent.current,
                inactiveTrackColor = CanopyColors.Canvas
            )
        )
        Spacer(Modifier.height(24.dp))

        // 10 Band EQ Sliders
        EQ_BANDS.forEachIndexed { index, band ->
            val gain = config.clampedGains.getOrNull(index) ?: 0f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = band.label,
                    color = CanopyColors.Text,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = gain,
                    onValueChange = { newGain ->
                        val newGains = config.gains.toMutableList()
                        if (newGains.size < 10) {
                            newGains.addAll(List(10 - newGains.size) { 0f })
                        }
                        newGains[index] = newGain
                        onConfigChange(config.copy(gains = newGains, presetId = "custom"))
                    },
                    valueRange = EqualizerConfig.MIN_GAIN_DB..EqualizerConfig.MAX_GAIN_DB,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = LocalAccent.current,
                        activeTrackColor = LocalAccent.current,
                        inactiveTrackColor = CanopyColors.Canvas
                    )
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "${if (gain > 0) "+" else ""}${gain.roundToInt()} dB",
                    color = CanopyColors.Muted,
                    modifier = Modifier.width(52.dp)
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
