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

import dev.sfg.orchard.mobile.ui.theme.legibleOnDarkChrome
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LyricWord

@Composable
internal fun lyricTextStyle(adlib: Boolean = false) =
    MaterialTheme.typography.headlineMedium.copy(
        // Desktop's LyricsView metrics: adlibs at 0.68x, 1.12 line height, -0.4 tracking.
        fontSize = if (adlib) 19.sp else 28.sp,
        lineHeight = if (adlib) 23.sp else 31.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = if (adlib) FontWeight.Medium else FontWeight.Bold,
        lineBreak = LineBreak.Heading,
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimedWords(
    words: List<LyricWord>,
    positionMs: State<Float>,
    lineActive: Boolean,
    adlib: Boolean = false,
    isAlternate: Boolean = false,
    accent: Color,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = if (adlib) 4.dp else 0.dp),
        horizontalArrangement = if (isAlternate) Arrangement.spacedBy(if (adlib) 5.dp else 8.dp, Alignment.End)
                                else Arrangement.spacedBy(if (adlib) 5.dp else 8.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        words.forEach { word -> SmoothTimedWord(word, positionMs, lineActive, adlib, accent) }
    }
}

@Composable
private fun SmoothTimedWord(
    word: LyricWord,
    positionMs: State<Float>,
    lineActive: Boolean,
    adlib: Boolean,
    accent: Color,
) {
    // Text owns layout, font loading and accessibility. Its cached layout is painted once;
    // the moving gradient never changes composition, text measurement or FlowRow placement.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val sung = if (adlib) accent.copy(alpha = 0.9f) else Color.White
    // Resting lines draw at full white; the line's layer applies the single rest dim.
    val unsung = if (lineActive) Color.White.copy(alpha = if (adlib) ADLIB_UNSUNG_ALPHA else UNSUNG_ALPHA) else Color.White
    val fill = if (adlib) sung else lerp(Color.White, accent, 0.4f)
    Text(
        text = word.text.trim(),
        style = lyricTextStyle(adlib),
        color = unsung,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithCache {
            val measured = layout
            val glow = Shadow(fill.copy(alpha = 0.45f), blurRadius = 10.dp.toPx())
            val sungGlow = Shadow(accent.copy(alpha = 0.4f), blurRadius = 10.dp.toPx())
            onDrawWithContent {
                if (measured == null || !lineActive) {
                    drawContent()
                } else {
                    val progress = lyricWordProgress(word, positionMs.value + LYRIC_WORD_LEAD_MS)
                    when {
                        progress <= 0f -> drawText(measured, color = unsung)
                        progress >= 1f -> drawText(measured, color = sung, shadow = if (adlib) null else sungGlow)
                        else -> drawText(
                            measured,
                            brush = Brush.horizontalGradient(
                                0f to fill,
                                progress to fill,
                                (progress + HIGHLIGHT_FEATHER).coerceAtMost(1f) to unsung,
                                1f to unsung,
                                endX = size.width,
                            ),
                            shadow = glow,
                        )
                    }
                }
            }
        },
    )
}

internal fun lyricWordProgress(word: LyricWord, positionMs: Float): Float {
    val start = word.startMs.toFloat()
    val end = word.endMs?.toFloat() ?: start
    return if (end > start) ((positionMs - start) / (end - start)).coerceIn(0f, 1f)
    else if (positionMs >= start) 1f else 0f
}

@Composable
internal fun unsyncedLyricStyle() =
    MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)

/** Cover accent tuned for lyric fills. Grey covers keep desktop's warm white instead of the app accent. */
internal fun Color.lyricAccent(): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(toArgb(), hsl)
    return if (hsl[1] < 0.10f) Color(0xFFF0EEE7) else legibleOnDarkChrome()
}
