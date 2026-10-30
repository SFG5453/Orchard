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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.playback.slop.SlopAction

@Composable
internal fun SlopSettingsRow(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(Icons.Rounded.AutoAwesome)
            Column {
                RowTitle("AI-generated music")
                RowSubtitle("Uses the \"Made with AI\" label YouTube shows on the playing song and the next three.")
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsSegmented(
            options = listOf(SlopAction.OFF to "Off", SlopAction.MARK to "Mark",
                SlopAction.SKIP to "Skip", SlopAction.REMOVE to "Remove"),
            selected = settings.slopAction,
            onSelect = { onSettings(settings.copy(slopAction = it)) },
        )
        Spacer(Modifier.height(8.dp))
        RowSubtitle(when (settings.slopAction) {
            SlopAction.OFF -> "Do not check songs."
            SlopAction.MARK -> "Show an AI badge on flagged songs."
            SlopAction.SKIP -> "Mark and skip flagged songs."
            SlopAction.REMOVE -> "Mark and remove flagged songs from the queue."
        })
    }
    PanelDivider()
    ToggleRow(
        title = "Audio AI analysis (deprecated)",
        subtitle = "Also download songs and analyse the audio for AI-generation artifacts. This detector misses newer AI music.",
        icon = Icons.Rounded.GraphicEq,
        checked = settings.slopAudioScan,
        enabled = settings.slopAction != SlopAction.OFF,
        onChecked = { onSettings(settings.copy(slopAudioScan = it)) },
    )
}
