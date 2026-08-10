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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
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
        if (activeIndex < 0) return@LaunchedEffect
        // Desktop parks the active line just above centre via its 38% scroll padding.
        val viewport = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem(activeIndex, if (viewport > 0) -(viewport * 0.34f).toInt() else -220)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .verticalEdgeFade(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == activeIndex
            Column(
                Modifier.fillMaxWidth()
                    .clickable(enabled = line.startMs != null) { line.startMs?.let(onSeek) }
                    .padding(vertical = LINE_SPACING),
            ) {
                if (line.words.isNotEmpty()) {
                    TimedWords(line.words, smoothPosition, active, accent = accent)
                } else {
                    Text(
                        line.text,
                        color = if (active) Color.White else Color.White.copy(alpha = INACTIVE_ALPHA),
                        style = lyricTextStyle(),
                    )
                }
                if (line.adlibs.isNotEmpty()) {
                    TimedWords(line.adlibs, smoothPosition, active, adlib = true, accent = accent)
                }
            }
        }
    }
}

/** Matches the desktop lyric mask: `linear-gradient(transparent, #000 13%, #000 82%, transparent)`. */
private fun Modifier.verticalEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.13f to Color.Black,
                    0.82f to Color.Black,
                    1f to Color.Transparent,
                ),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun lyricTextStyle(adlib: Boolean = false) =
    MaterialTheme.typography.headlineMedium.copy(
        fontSize = if (adlib) 22.sp else 28.sp,
        lineHeight = if (adlib) 28.sp else 35.sp,
        fontWeight = FontWeight.Bold,
        fontStyle = if (adlib) FontStyle.Italic else FontStyle.Normal,
    )

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
    val style = lyricTextStyle(adlib)
    val text = word.text.trim()
    // Desktop keeps sung words white and only tints the sweep with the accent, so the
    // artwork colour reads as a highlight rather than recolouring the whole line.
    val sung = if (adlib) accent.copy(alpha = 0.76f) else Color.White
    val unsung = Color.White.copy(
        alpha = when {
            !lineActive -> INACTIVE_ALPHA
            adlib -> 0.48f
            else -> UNSUNG_ALPHA
        },
    )
    val fill = if (adlib) sung else lerp(Color.White, accent, 0.4f)
    Box(Modifier.padding(end = 8.dp, bottom = 3.dp)) {
        Text(
            text = text,
            color = if (completed) sung else unsung,
            style = style,
        )
        if (progress in 0.0001f..0.9999f) {
            val fadeEnd = (progress + 0.10f).coerceAtMost(1f)
            Text(
                text = text,
                style = style.copy(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to fill,
                            progress to fill,
                            fadeEnd to unsung,
                            1f to unsung,
                        ),
                    ),
                    shadow = Shadow(fill.copy(alpha = 0.50f), blurRadius = 12f),
                ),
            )
        }
    }
}

private const val PLAYER_POSITION_TICK_MS = 500f

/** Colour ramp mirrored from the desktop `.lyrics-line` rules. */
private const val INACTIVE_ALPHA = 0.18f
private const val UNSUNG_ALPHA = 0.35f
private val LINE_SPACING = 10.dp
