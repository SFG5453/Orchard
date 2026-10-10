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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.History
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
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.settings.CacheManager

@Composable
internal fun AudioPage(
    settings: OrchardSettings,
    onSettings: (OrchardSettings) -> Unit,
    onAutoplayEnabled: ((Boolean) -> Unit)?,
) {
    SectionLabel("Quality")
    SettingsPanel(index = 1) {
        QualityRow(settings.audioQuality) { onSettings(settings.copy(audioQuality = it)) }
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
        PanelDivider()
        CrossfadeRow(settings, onSettings)
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

    SectionLabel("Non-music", 6)
    SettingsPanel(index = 7) {
        NonMusicSkipRow(settings.nonMusicSkip) { onSettings(settings.copy(nonMusicSkip = it)) }
    }

    SectionLabel("AI-generated music", 8)
    SettingsPanel(index = 9) { SlopSettingsRow(settings, onSettings) }

    SectionLabel("Devices", 10)
    SettingsPanel(index = 11) { ChromecastSettingsRow() }

    SectionLabel("Listening history", 12)
    SettingsPanel(index = 13) {
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

    SectionLabel("Player", 4)
    SettingsPanel(index = 5) {
        ToggleRow(
            title = "Player gestures",
            subtitle = "Swipe to skip and tap to like on artwork",
            icon = Icons.Rounded.TouchApp,
            checked = settings.playerGesturesEnabled,
            onChecked = { onSettings(settings.copy(playerGesturesEnabled = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = "Animated artwork",
            subtitle = "Move artwork while music plays",
            icon = Icons.Rounded.Image,
            checked = settings.animatedArtwork,
            onChecked = { onSettings(settings.copy(animatedArtwork = it)) },
        )
        PanelDivider()
        ArtworkSourceOrder(settings, onSettings)
        PanelDivider()
        ToggleRow(
            title = "Download animated artwork",
            subtitle = "Save motion covers with new downloads for offline playback",
            icon = Icons.Rounded.Download,
            checked = settings.downloadAnimatedArtwork,
            onChecked = { onSettings(settings.copy(downloadAnimatedArtwork = it)) },
        )
    }

    SectionLabel("Background", 6)
    SettingsPanel(index = 7) {
        ToggleRow(
            title = "Animated background",
            subtitle = "Let the cover's colours drift behind the app",
            icon = Icons.Rounded.AutoAwesome,
            checked = settings.animatedBackground,
            onChecked = { onSettings(settings.copy(animatedBackground = it)) },
        )
    }

    LyricTranslationSection(settings, onSettings, labelIndex = 8)
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
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
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
    updateState: UpdateState,
    cacheSizeBytes: Long,
    isClearingCache: Boolean,
    onSettings: (OrchardSettings) -> Unit,
    onClearCache: () -> Unit,
    downloadedBytes: Long,
    onDeleteAllDownloads: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: (MobileUpdateMetadata) -> Unit,
    onShowNotes: () -> Unit,
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

    SectionLabel("Updates", 4)
    SettingsPanel(index = 5) {
        val available = (updateState as? UpdateState.Available)?.metadata
        if (available != null) {
            ActionRow(
                title = "Update available (${available.version})",
                subtitle = if (available.codename.isNotBlank()) "\"${available.codename}\" • Tap to install" else "Tap to install",
                icon = Icons.Rounded.SystemUpdate,
                onClick = { onInstallUpdate(available) },
            )
            PanelDivider()
            ActionRow(
                title = "Release notes",
                subtitle = "View changes in Orchard ${available.version}",
                icon = Icons.Rounded.Description,
                onClick = onShowNotes,
            )
        } else {
            ActionRow(
                title = "Release notes",
                subtitle = updateSubtitle(settings),
                icon = Icons.Rounded.Description,
                onClick = onShowNotes,
            )
            if (BuildConfig.UPDATER_ENABLED) {
                PanelDivider()
                ActionRow(
                    title = "Check for updates",
                    subtitle = "Check for newer Orchard releases",
                    icon = Icons.Rounded.SystemUpdate,
                    onClick = onCheckForUpdates,
                )
            }
        }
        if (BuildConfig.UPDATER_ENABLED) {
            PanelDivider()
            ToggleRow(
                title = "Beta channel",
                subtitle = "Get beta builds from GitHub releases instead of the regular channel. Beta builds may be less stable.",
                icon = Icons.Rounded.Science,
                checked = settings.betaChannelEnabled,
                onChecked = { onSettings(settings.copy(betaChannelEnabled = it)) },
            )
        }
    }
}

private fun updateSubtitle(settings: OrchardSettings): String = when {
    !BuildConfig.UPDATER_ENABLED -> "Orchard ${BuildConfig.VERSION_NAME} • Updates disabled"
    settings.betaChannelEnabled -> "Orchard ${BuildConfig.VERSION_NAME} • Beta channel • Up to date"
    else -> "Orchard ${BuildConfig.VERSION_NAME} • Up to date"
}
