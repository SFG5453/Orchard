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

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.settings.CacheManager

@Composable
internal fun PlaybackPage(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
) {
    val atmosAvailable = appleAtmosQualityAvailable(settings)
    SectionLabel("Quality")
    SettingsPanel(index = 1) {
        QualityRow(settings.audioQuality, { onSettings(settings.copy(audioQuality = it)) }, atmosAvailable = atmosAvailable)
        PanelDivider()
        QualityRow(
            settings.cellularAudioQuality,
            { onSettings(settings.copy(cellularAudioQuality = it)) },
            atmosAvailable = atmosAvailable,
            title = "Mobile data quality",
            icon = Icons.Rounded.SignalCellularAlt,
        )
        PanelDivider()
        ToggleRow(
            title = "Show audio bitrate",
            subtitle = "Display streaming bitrate under the scrubber",
            icon = Icons.Rounded.Speed,
            checked = settings.showBitrate,
            onChecked = { onSettings(settings.copy(showBitrate = it)) },
        )
    }

    SectionLabel("Sound", 2)
    SettingsPanel(index = 3) {
        EqualizerRow(settings, onSettings)
        PanelDivider()
        ToggleRow(
            title = "Exponential volume",
            subtitle = "Finer control at low volumes with the media-volume buttons",
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            checked = settings.exponentialVolumeEnabled,
            onChecked = { onSettings(settings.copy(exponentialVolumeEnabled = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = "Volume normalization",
            subtitle = "Even out volume differences between songs",
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            checked = settings.volumeNormalizationEnabled,
            onChecked = { onSettings(settings.copy(volumeNormalizationEnabled = it)) },
        )
    }

    SectionLabel("Queue", 4)
    SettingsPanel(index = 5) {
        ToggleRow(
            title = "Autoplay",
            subtitle = "Keep playing related music when the queue runs out",
            icon = Icons.Rounded.Loop,
            checked = settings.autoplayEnabled,
            onChecked = { enabled ->
                onAutoplayEnabled?.invoke(enabled) ?: onSettings(settings.copy(autoplayEnabled = enabled))
            },
        )
    }

    SectionLabel("Devices", 6)
    SettingsPanel(index = 7) { ChromecastSettingsRow() }

    SectionLabel("Listening history", 8)
    SettingsPanel(index = 9) {
        ToggleRow(
            title = "Save plays to YouTube Music history",
            subtitle = "Add songs you play in Orchard to your YouTube Music history",
            icon = Icons.Rounded.History,
            checked = settings.sendYouTubeHistory,
            onChecked = { onSettings(settings.copy(sendYouTubeHistory = it)) },
        )
    }
}

@Composable
internal fun AppearancePage(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    onHomeLayout: () -> Unit,
) {
    SectionLabel("Colours")
    SettingsPanel(index = 1) { ColourSourceRow(settings, onSettings) }

    SectionLabel("Home", 2)
    SettingsPanel(index = 3) {
        ActionRow(
            title = "Home screen layout",
            subtitle = "Reorder and hide sections on Home",
            icon = Icons.Rounded.GridView,
            onClick = onHomeLayout,
        )
    }

    SectionLabel("Navigation", 4)
    SettingsPanel(index = 5) {
        ToggleRow(
            title = "Settings tab",
            subtitle = "Show Settings in the bottom bar",
            icon = Icons.Rounded.Settings,
            checked = settings.showSettingsTab,
            onChecked = { onSettings(settings.copy(showSettingsTab = it)) },
        )
    }

    SectionLabel("Player", 6)
    SettingsPanel(index = 7) {
        ToggleRow(
            title = "Player gestures",
            subtitle = "Swipe to skip and tap to like on artwork",
            icon = Icons.Rounded.TouchApp,
            checked = settings.playerGesturesEnabled,
            onChecked = { onSettings(settings.copy(playerGesturesEnabled = it)) },
        )
    }

    SectionLabel("Background", 8)
    SettingsPanel(index = 9) {
        ToggleRow(
            title = "Animated background",
            subtitle = "Let the cover's colours drift behind the app",
            icon = Icons.Rounded.AutoAwesome,
            checked = settings.animatedBackground,
            onChecked = { onSettings(settings.copy(animatedBackground = it)) },
        )
        BackdropControls(settings, onSettings)
    }
}

/** Orchard palette or the wallpaper palette, with a swatch strip previewing the choice. */
@Composable
private fun ColourSourceRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    val context = LocalContext.current
    val system = settings.useSystemColors
    val swatches = remember(system) {
        if (!system) {
            orchardSwatches
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(context).let {
                listOf(it.primary, it.secondary, it.tertiary, it.primaryContainer, it.secondaryContainer)
            }
        } else {
            emptyList()
        }
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(Icons.Rounded.Palette)
            Column {
                RowTitle("Colour source")
                RowSubtitle(if (system) "Match your wallpaper" else "The Orchard green palette")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
            SettingsSegmented(
                options = listOf(false to "Orchard green", true to "System colours"),
                selected = system,
                onSelect = { onSettings(settings.copy(useSystemColors = it)) },
            )
            if (swatches.isEmpty()) {
                Text("System colours need Android 12 or newer", color = SettingsStyle.Description, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    swatches.forEach {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(it, RoundedCornerShape(12.dp))
                                .border(1.dp, SettingsStyle.TileBorder, RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }
    }
}

private val orchardSwatches = listOf(
    Color(0xFF44604F), Color(0xFF5F9A76), Color(0xFF8CC4A0), Color(0xFFA0D6B4), Color(0xFFC4E0CB),
)

@Composable
internal fun StoragePage(
    settings: OrchardSettings,
    cacheSizeBytes: Long,
    isClearingCache: Boolean,
    onSettings: (OrchardSettings) -> Unit,
    onClearCache: () -> Unit,
    downloadedBytes: Long,
    onDeleteAllDownloads: () -> Unit,
) {
    SectionLabel("Cache")
    SettingsPanel(index = 1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                CacheManager.formatStorageSize(cacheSizeBytes),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = SettingsStyle.Title,
            )
            RowSubtitle("Temporary audio, artwork and network cache")
        }
        PanelDivider()
        CacheSizeRow(settings, onSettings)
        PanelDivider()
        ClearCacheRow(cacheSizeBytes = cacheSizeBytes, isClearing = isClearingCache, onClear = onClearCache)
    }

    SectionLabel("Downloads", 2)
    SettingsPanel(index = 3) {
        SdCardDownloadsRow(settings, onSettings)
        PanelDivider()
        DeleteDownloadsRow(downloadedBytes = downloadedBytes, onDelete = onDeleteAllDownloads)
    }
}
