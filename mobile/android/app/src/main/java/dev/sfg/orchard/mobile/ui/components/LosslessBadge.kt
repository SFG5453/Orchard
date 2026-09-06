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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LosslessGold = Color(0xFFDFB15B)

/**
 * Bitrate readout badge under the player scrubber.
 *
 * For standard streams, renders "$bitrate kbps".
 * For Qobuz streams, renders "Lossless" in gold; clicking it smoothly animates
 * between the word "Lossless" and the numeric bitrate readout.
 */
@Composable
fun LosslessBadge(
    showBitrate: Boolean,
    bitrateKbps: Int,
    isQobuz: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!showBitrate || bitrateKbps <= 0) return

    val shape = RoundedCornerShape(6.dp)

    if (!isQobuz) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$bitrateKbps kbps",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    } else {
        var showNumeric by remember { mutableStateOf(false) }

        Box(
            modifier = modifier
                .clip(shape)
                .background(LosslessGold.copy(alpha = 0.16f))
                .border(1.dp, LosslessGold.copy(alpha = 0.42f), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    showNumeric = !showNumeric
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = showNumeric,
                transitionSpec = {
                    val slideDirection = if (targetState) 1 else -1
                    (fadeIn(animationSpec = tween(200)) + slideInVertically(animationSpec = tween(200)) { height -> slideDirection * height / 2 })
                        .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutVertically(animationSpec = tween(200)) { height -> -slideDirection * height / 2 })
                },
                label = "LosslessBitrateTransition",
            ) { numeric ->
                Text(
                    text = if (numeric) "$bitrateKbps kbps" else "Lossless",
                    color = LosslessGold,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}
