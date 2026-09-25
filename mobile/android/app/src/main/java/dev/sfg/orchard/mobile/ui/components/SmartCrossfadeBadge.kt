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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Returns a human-friendly description for the Smart Crossfade transition style.
 */
fun transitionStyleLabel(style: String): String {
    return when (style.lowercase().trim()) {
        "dj_blend" -> "Beat Matched"
        "dj_filter" -> "Filtered Blend"
        "equal_power" -> "Equal Power"
        "gapless" -> "Seamless Handoff"
        "tempo_matched" -> "Tempo Matched"
        "bass_first" -> "Bass Swap"
        "smart" -> "Smart Mix"
        else -> if (style.isNotBlank()) {
            style.replace('_', ' ')
                .split(' ')
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        } else {
            "Smart Mix"
        }
    }
}

/**
 * Mini animated live equalizer graphic with 3 vertical undulating bars.
 */
@Composable
fun SmartCrossfadeWaveform(
    color: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "SmartCrossfadeWaveform")
    val phase1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Bar1",
    )
    val phase2 by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(440, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Bar2",
    )
    val phase3 by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(610, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Bar3",
    )

    Row(
        modifier = modifier.height(13.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.5.dp)
                .height(13.dp * phase1)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .width(2.5.dp)
                .height(13.dp * phase2)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .width(2.5.dp)
                .height(13.dp * phase3)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Premium frosted-glass indicator chip showing that Smart Crossfade is actively blending
 * into the upcoming song, complete with live animated waveforms, style chips, and track preview.
 */
@Composable
fun SmartCrossfadeBadge(
    visible: Boolean,
    style: String,
    incomingTrack: Track? = null,
    progress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(360)) + expandVertically(tween(360, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(280)) + shrinkVertically(tween(280)),
        modifier = modifier,
    ) {
        val rainbow = rememberRainbowBrush(spanPx = 360f, periodMs = 4_000)
        val styleLabel = transitionStyleLabel(style)
        val incomingTitle = incomingTrack?.title.orEmpty()

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.18f),
                        ),
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                SmartCrossfadeWaveform(color = Color.White.copy(alpha = 0.92f))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = styleLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                        brush = rainbow,
                    ),
                )
                if (incomingTitle.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.40f)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (progress >= 0.5f) "From previous track" else "Into $incomingTitle",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = CanopyColors.Text.copy(alpha = 0.72f),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
