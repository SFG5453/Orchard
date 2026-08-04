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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.LyricWord
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.ui.components.AmbientArtworkHaze
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.MessagePanel
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Auto-scrolling, word-timed lyric list. Shared by the player's inline lyrics mode. */
@Composable
internal fun LyricLines(
    lines: List<LyricLine>,
    positionMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
    accent: Color = CanopyColors.LyricActive,
) {
    val smoothPosition by animateFloatAsState(
        targetValue = positionMs.toFloat() + if (playing) PLAYER_POSITION_TICK_MS else 0f,
        animationSpec = if (playing) tween(PLAYER_POSITION_TICK_MS.toInt(), easing = LinearEasing) else snap(),
        label = "smooth lyric position",
    )
    val activeIndex = lines.indexOfLast { line -> line.startMs != null && line.startMs.toFloat() <= smoothPosition }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex, -220)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == activeIndex
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = line.startMs != null) { line.startMs?.let(onSeek) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    Box(
                        Modifier
                            .size(width = 5.dp, height = 38.dp)
                            .background(accent, RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (line.words.isNotEmpty()) {
                        TimedWords(line.words, smoothPosition, active, accent = accent)
                    } else {
                        Text(
                            line.text,
                            color = if (active) accent else Color.White.copy(alpha = if (activeIndex < 0) 0.85f else 0.35f),
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                    if (line.adlibs.isNotEmpty()) {
                        TimedWords(line.adlibs, smoothPosition, active, adlib = true, accent = accent)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimedWords(
    words: List<LyricWord>,
    positionMs: Float,
    lineActive: Boolean,
    adlib: Boolean = false,
    accent: Color,
) {
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        words.forEach { word ->
            SmoothTimedWord(word, positionMs, lineActive, adlib, accent)
        }
    }
}

@Composable
private fun SmoothTimedWord(
    word: LyricWord,
    positionMs: Float,
    lineActive: Boolean,
    adlib: Boolean,
    accent: Color,
) {
    val start = word.startMs.toFloat()
    val end = word.endMs?.toFloat() ?: word.startMs.toFloat()
    val progress = if (lineActive && end > start) {
        ((positionMs - start) / (end - start)).coerceIn(0f, 1f)
    } else if (lineActive && positionMs >= start) 1f else 0f
    val completed = lineActive && progress >= 1f
    val style = if (adlib) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp)
    val text = word.text.trim()
    Box(Modifier.padding(end = 7.dp, bottom = 3.dp)) {
        Text(
            text = text,
            color = if (completed) accent else Color.White.copy(alpha = if (lineActive) 0.50f else 0.35f),
            style = style,
            fontWeight = FontWeight.SemiBold,
            fontStyle = if (adlib) FontStyle.Italic else FontStyle.Normal,
        )
        if (progress in 0.0001f..0.9999f) {
            val fill = accent
            val fadeEnd = (progress + 0.10f).coerceAtMost(1f)
            Text(
                text = text,
                style = style.copy(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to fill,
                            progress to fill,
                            fadeEnd to fill.copy(alpha = 0.20f),
                            1f to Color.Transparent,
                        ),
                    ),
                    shadow = Shadow(fill.copy(alpha = 0.40f), blurRadius = 8f),
                ),
                fontWeight = FontWeight.Bold,
                fontStyle = if (adlib) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

private const val PLAYER_POSITION_TICK_MS = 500f
