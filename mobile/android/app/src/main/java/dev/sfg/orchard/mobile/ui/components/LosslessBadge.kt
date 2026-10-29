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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.sfg.orchard.mobile.model.LocalStreamDetail
import dev.sfg.orchard.mobile.model.StreamDetail
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun LosslessBadge(
    showBitrate: Boolean,
    bitrateKbps: Int,
    modifier: Modifier = Modifier,
) {
    val detail = LocalStreamDetail.current
    if (detail.codec == "Atmos") {
        val label = if (bitrateKbps > 0) "Atmos · $bitrateKbps kbps" else "Atmos"
        Text(
            text = label,
            modifier = modifier,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            ),
        )
        return
    }
    if (!showBitrate || bitrateKbps <= 0) return

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = listOf("$bitrateKbps kbps", detail.codec).filter { it.isNotBlank() }.joinToString(" · "),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
