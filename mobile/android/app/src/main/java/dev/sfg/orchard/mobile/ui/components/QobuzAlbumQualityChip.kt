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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.QobuzAlbumQuality
import dev.sfg.orchard.mobile.qobuz.formatPlaybackQualityLabel

/** Gold tier tag for an album Qobuz plays, e.g. "Qobuz Hi-Res · 24-bit / 96 kHz". */
@Composable
fun QobuzAlbumQualityChip(quality: QobuzAlbumQuality, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = formatPlaybackQualityLabel("qobuz", quality.hiRes, quality.bitDepth, quality.sampleRate),
        color = LosslessGold,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        modifier = modifier
            .clip(shape)
            .background(LosslessGold.copy(alpha = 0.16f))
            .border(1.dp, LosslessGold.copy(alpha = 0.42f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
