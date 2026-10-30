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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.AnimatedArtworkQuality
import dev.sfg.orchard.mobile.model.OrchardSettings

@Composable
internal fun ArtworkQualityRows(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    ArtworkQualityRow("Wi-Fi artwork quality", Icons.Rounded.Image, settings.animatedArtworkQuality) {
        onSettings(settings.copy(animatedArtworkQuality = it))
    }
    PanelDivider()
    ArtworkQualityRow("Mobile data artwork quality", Icons.Rounded.SignalCellularAlt, settings.cellularAnimatedArtworkQuality) {
        onSettings(settings.copy(cellularAnimatedArtworkQuality = it))
    }
    Text(
        "Quality choices apply when multiple qualities are available. Saved covers keep their downloaded quality. " +
            "Metered Wi-Fi uses the mobile data setting.",
        color = SettingsStyle.Description,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
    )
}

@Composable
private fun ArtworkQualityRow(
    title: String,
    icon: ImageVector,
    value: AnimatedArtworkQuality,
    onChange: (AnimatedArtworkQuality) -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(icon)
            Column {
                RowTitle(title)
                RowSubtitle(value.description)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Two rows leave room for the full labels on narrow phones and at larger font sizes.
        AnimatedArtworkQuality.entries.chunked(2).forEachIndexed { index, qualities ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            SettingsSegmented(
                options = qualities.map { it to it.label },
                selected = value,
                onSelect = onChange,
            )
        }
    }
}
