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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState
import dev.sfg.orchard.mobile.model.OrchardSettings

@Composable
internal fun MixingPage(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    SectionLabel("Transitions")
    SettingsPanel(index = 1) { CrossfadeRow(settings, onSettings) }

    SectionLabel("Non-music", 2)
    SettingsPanel(index = 3) {
        NonMusicSkipRow(settings.nonMusicSkip) { onSettings(settings.copy(nonMusicSkip = it)) }
    }

    SectionLabel("AI-generated music", 4)
    SettingsPanel(index = 5) { SlopSettingsRow(settings, onSettings) }
}

@Composable
internal fun ArtworkPage(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    SectionLabel("Motion")
    SettingsPanel(index = 1) {
        ToggleRow(
            title = "Animated artwork",
            subtitle = "Move artwork while music plays",
            icon = Icons.Rounded.Image,
            checked = settings.animatedArtwork,
            onChecked = { onSettings(settings.copy(animatedArtwork = it)) },
        )
        PanelDivider()
        ToggleRow(
            title = "Download animated artwork",
            subtitle = "Save motion covers with new downloads for offline playback",
            icon = Icons.Rounded.Download,
            checked = settings.downloadAnimatedArtwork,
            onChecked = { onSettings(settings.copy(downloadAnimatedArtwork = it)) },
        )
    }

    SectionLabel("Quality", 2)
    SettingsPanel(index = 3) { ArtworkQualityRows(settings, onSettings) }

    SectionLabel("Sources", 4)
    SettingsPanel(index = 5) { ArtworkSourceOrder(settings, onSettings) }
}

@Composable
internal fun LyricsPage(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    LyricsMotionSection(settings, onSettings, labelIndex = 0)
    LyricsProvidersSection(settings, onSettings, labelIndex = 2)
    LyricTranslationSection(settings, onSettings, labelIndex = 4)
}

@Composable
internal fun AboutPage(
    settings: OrchardSettings,
    updateState: UpdateState,
    onSettings: (OrchardSettings) -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: (MobileUpdateMetadata) -> Unit,
    onShowNotes: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, top = 20.dp)) {
        Text(
            "Orchard ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            color = SettingsStyle.Title,
        )
        if (BuildConfig.CODENAME.isNotBlank()) RowSubtitle("\"${BuildConfig.CODENAME}\"")
    }

    SectionLabel("Updates", 1)
    SettingsPanel(index = 2) {
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
                subtitle = "Get beta builds from GitHub releases. Beta builds may be less stable.",
                icon = Icons.Rounded.Science,
                checked = settings.betaChannelEnabled,
                onChecked = { onSettings(settings.copy(betaChannelEnabled = it)) },
            )
        }
    }
}

private fun updateSubtitle(settings: OrchardSettings): String = when {
    !BuildConfig.UPDATER_ENABLED -> "Updates disabled"
    settings.betaChannelEnabled -> "Beta channel • Up to date"
    else -> "Up to date"
}
