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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.LyricWord
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Auto-scrolling, word-timed lyric list matching desktop fullscreen lyrics fidelity. */
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
    // Playback snapshots arrive twice a second. Keep one frame-clock coroutine alive between
    // them and fold small clock corrections in gently; restarting the animation at every
    // snapshot makes the highlight visibly hop backwards on some devices.
    val latestPosition by rememberUpdatedState(positionMs)
    val latestPlaying by rememberUpdatedState(playing)
    val synced = remember(lines) { lines.any { it.startMs != null } }
    val smoothPosition = produceState(positionMs.toFloat(), lines) {
        var observedPosition = latestPosition
        var wasPlaying = latestPlaying
        var anchorPosition = observedPosition.toFloat()
        var anchorFrame = withFrameNanos { it }
        while (true) {
            if (!latestPlaying || !synced) {
                val pausedAt = latestPosition
                value = pausedAt.toFloat()
                snapshotFlow { latestPosition to latestPlaying }
                    .first { it.first != pausedAt || (it.second && synced) }
                observedPosition = latestPosition
                wasPlaying = latestPlaying
                anchorPosition = observedPosition.toFloat()
                anchorFrame = withFrameNanos { it }
            }
            withFrameNanos { frame ->
                val reportedPosition = latestPosition
                val isPlaying = latestPlaying
                var projectedPosition = anchorPosition + if (wasPlaying) {
                    (frame - anchorFrame) / NANOS_PER_MILLISECOND
                } else {
                    0f
                }

                if (reportedPosition != observedPosition || isPlaying != wasPlaying) {
                    val drift = reportedPosition - projectedPosition
                    projectedPosition = when {
                        !isPlaying || !wasPlaying -> reportedPosition.toFloat()
                        abs(drift) >= SEEK_SNAP_THRESHOLD_MS -> reportedPosition.toFloat()
                        else -> projectedPosition + drift * CLOCK_CORRECTION_FACTOR
                    }
                    observedPosition = reportedPosition
                    wasPlaying = isPlaying
                    anchorPosition = projectedPosition
                    anchorFrame = frame
                }

                value = projectedPosition
            }
        }
    }

    val displayItems = remember(lines, synced) {
        if (!synced) {
            lines.mapIndexed { index, line -> LyricDisplayItem.Line(line, index) }
        } else {
            buildLyricDisplayItems(lines)
        }
    }

    // Frame-clock reads stay in drawing. Only an item change invalidates the list composition.
    val activeDisplayIndex by remember(displayItems, synced) {
        derivedStateOf {
            if (!synced) return@derivedStateOf -1
            val pos = smoothPosition.value
            val activePauseIdx = displayItems.indexOfFirst { item ->
                item is LyricDisplayItem.Pause && pos >= item.startMs && pos < item.endMs
            }
            if (activePauseIdx >= 0) {
                activePauseIdx
            } else {
                displayItems.indexOfLast { item ->
                    item is LyricDisplayItem.Line &&
                        item.line.startMs != null &&
                        item.line.startMs.toFloat() <= pos + LYRIC_LINE_LEAD_MS
                }
            }
        }
    }

    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var browsing by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        if (dragging) browsing = true
        else if (browsing) {
            delay(4_000)
            browsing = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Optical center scroll positioning: resting active line sits around 40-42% from top.
        val inset = if (synced) maxHeight * 0.40f else contentPadding.calculateTopPadding()
        val bottomInset = if (synced) maxHeight * 0.60f else contentPadding.calculateBottomPadding()

        LaunchedEffect(activeDisplayIndex, browsing, inset) {
            if (activeDisplayIndex >= 0 && !browsing && !dragging) {
                listState.animateScrollToItem(activeDisplayIndex)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().verticalEdgeFade(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = inset,
                bottom = bottomInset,
            ),
        ) {
            if (!synced) item {
                Box(
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(999.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "These lyrics aren’t synced to the music.",
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            itemsIndexed(displayItems, key = { _, item -> item.key }) { displayIndex, item ->
                when (item) {
                    is LyricDisplayItem.Pause -> {
                        val active = displayIndex == activeDisplayIndex
                        val isAlternate = lines.getOrNull(item.afterLineIndex)?.agentLane == "alternate"
                        InstrumentalPauseRow(
                            active = active,
                            accent = accent,
                            onClick = {
                                browsing = false
                                onSeek(item.startMs)
                            },
                            alignment = if (isAlternate) Alignment.End else Alignment.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (isAlternate) 48.dp else 0.dp),
                        )
                    }

                    is LyricDisplayItem.Line -> {
                        val line = item.line
                        val active = displayIndex == activeDisplayIndex
                        val isAlternate = line.agentLane == "alternate"
                        val emphasis = animateFloatAsState(
                            targetValue = if (active) 1.015f else 1f,
                            animationSpec = tween(240),
                            label = "Lyric emphasis",
                        )

                        // Graduated depth-of-field focus: active line gleams at 1.0f, immediate
                        // neighbors fade gently, and distant lines softly recede. In browsing mode,
                        // all lines elevate to readable brightness.
                        val distance = if (activeDisplayIndex >= 0) abs(displayIndex - activeDisplayIndex) else 0
                        val targetAlpha = when {
                            !synced -> 0.85f
                            browsing -> 0.70f
                            active -> 1.0f
                            distance == 1 -> 0.44f
                            distance == 2 -> 0.26f
                            else -> 0.16f
                        }
                        val animatedAlpha by animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec = tween(280),
                            label = "LyricAlpha",
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (isAlternate) 48.dp else 0.dp)
                                .graphicsLayer {
                                    scaleX = emphasis.value
                                    scaleY = emphasis.value
                                    alpha = animatedAlpha
                                    transformOrigin = if (isAlternate) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
                                }
                                .clickable(enabled = line.startMs != null) {
                                    browsing = false
                                    line.startMs?.let(onSeek)
                                }
                                .padding(vertical = LINE_SPACING),
                            horizontalAlignment = if (isAlternate) Alignment.End else Alignment.Start,
                        ) {
                            if (line.words.isNotEmpty()) {
                                TimedWords(
                                    words = line.words,
                                    positionMs = smoothPosition,
                                    lineActive = active,
                                    isAlternate = isAlternate,
                                    accent = accent,
                                )
                            } else {
                                Text(
                                    text = line.text,
                                    color = if (active) Color.White else Color.White.copy(alpha = if (synced) 0.85f else 0.78f),
                                    textAlign = if (isAlternate) TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = lyricTextStyle().copy(
                                        shadow = if (active) Shadow(accent.copy(alpha = 0.45f), blurRadius = 24f) else null,
                                    ),
                                )
                            }
                            if (line.adlibs.isNotEmpty()) {
                                TimedWords(
                                    words = line.adlibs,
                                    positionMs = smoothPosition,
                                    lineActive = active,
                                    adlib = true,
                                    isAlternate = isAlternate,
                                    accent = accent,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Center-aligned, glassmorphic "Resume lyrics" pill button when browsing away from live position.
        if (browsing && synced) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(
                        color = Color(0xFF1E1E24).copy(alpha = 0.90f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .clickable { browsing = false }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Resume lyrics",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/** 3 cascading glowing dots signaling instrumental breaks (>= 7 seconds). */
@Composable
private fun InstrumentalPauseRow(
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PausePulse")
    val basePulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "BasePulse",
    )

    val inactiveColor = Color.White.copy(alpha = 0.24f)

    Box(
        modifier = modifier
            .padding(vertical = 12.dp)
            .clickable(onClick = onClick),
        contentAlignment = if (alignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val phaseOffset = index * (200f / 1400f)
                val dotProgress = (basePulse + phaseOffset) % 1f
                val wave = kotlin.math.sin(dotProgress * Math.PI).toFloat().coerceIn(0f, 1f)
                val scale = if (active) 0.85f + 0.40f * wave else 0.85f
                val alpha = if (active) 0.45f + 0.55f * wave else 0.24f
                val dotColor = if (active) accent else inactiveColor

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .drawWithCache {
                            val radius = size.minDimension / 2f
                            onDrawBehind {
                                if (active) {
                                    drawCircle(
                                        color = dotColor.copy(alpha = 0.40f * alpha),
                                        radius = radius * 2.2f,
                                    )
                                }
                                drawCircle(
                                    color = dotColor.copy(alpha = alpha),
                                    radius = radius,
                                )
                            }
                        },
                )
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
        fontSize = if (adlib) 21.sp else 32.sp,
        lineHeight = if (adlib) 26.sp else 41.sp,
        letterSpacing = if (adlib) (-0.2).sp else (-0.64).sp,
        fontWeight = if (adlib) FontWeight.Medium else FontWeight.Bold,
        lineBreak = LineBreak.Heading,
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimedWords(
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
    val sung = if (adlib) accent.copy(alpha = 0.76f) else Color.White
    val unsung = Color.White.copy(alpha = if (lineActive) UNSUNG_ALPHA else INACTIVE_ALPHA)
    val fill = if (adlib) sung else lerp(Color.White, accent, 0.4f)
    Text(
        text = word.text.trim(),
        style = lyricTextStyle(adlib),
        color = unsung,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithCache {
            val measured = layout
            val glow = Shadow(fill.copy(alpha = 0.45f), blurRadius = 10.dp.toPx())
            val sungGlow = Shadow(Color.White.copy(alpha = 0.25f), blurRadius = 4.dp.toPx())
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

internal sealed interface LyricDisplayItem {
    val key: String

    data class Line(
        val line: LyricLine,
        val originalIndex: Int,
    ) : LyricDisplayItem {
        override val key: String get() = "line-$originalIndex"
    }

    data class Pause(
        val afterLineIndex: Int,
        val startMs: Long,
        val endMs: Long,
    ) : LyricDisplayItem {
        override val key: String get() = "pause-$afterLineIndex"
    }
}

private const val LYRIC_PAUSE_MIN_MS = 7_000L
private const val LYRIC_PAUSE_LINE_TAIL_ACCURATE_MS = 400L
private const val LYRIC_PAUSE_LINE_TAIL_FALLBACK_MS = 2_400L

internal fun lyricPauseWindow(line: LyricLine, nextLine: LyricLine): Pair<Long, Long>? {
    val lineStart = line.startMs ?: return null
    val nextStart = nextLine.startMs ?: return null

    val gapLength = nextStart - lineStart
    if (gapLength < LYRIC_PAUSE_MIN_MS) return null

    val (lineEnd, hasAccurateEnd) = when {
        line.endMs != null && line.endMs > lineStart -> line.endMs to true
        line.words.isNotEmpty() -> {
            val lastWord = line.words.last()
            if (lastWord.endMs != null && lastWord.endMs > lineStart) {
                lastWord.endMs to true
            } else if (lastWord.startMs > lineStart) {
                (lastWord.startMs + 400L) to true
            } else {
                lineStart to false
            }
        }
        else -> lineStart to false
    }

    val tail = if (hasAccurateEnd) LYRIC_PAUSE_LINE_TAIL_ACCURATE_MS else LYRIC_PAUSE_LINE_TAIL_FALLBACK_MS
    val pauseStart = lineEnd + tail
    val pauseEnd = nextStart
    if (pauseStart >= pauseEnd) return null

    return pauseStart to pauseEnd
}

internal fun buildLyricDisplayItems(lines: List<LyricLine>): List<LyricDisplayItem> = buildList {
    lines.forEachIndexed { index, line ->
        add(LyricDisplayItem.Line(line, index))
        val nextLine = lines.getOrNull(index + 1)
        if (nextLine != null) {
            val pause = lyricPauseWindow(line, nextLine)
            if (pause != null) {
                add(LyricDisplayItem.Pause(index, pause.first, pause.second))
            }
        }
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000f
private const val SEEK_SNAP_THRESHOLD_MS = 250f
private const val CLOCK_CORRECTION_FACTOR = 0.35f
private const val LYRIC_LINE_LEAD_MS = 250f
private const val LYRIC_WORD_LEAD_MS = 80f
private const val HIGHLIGHT_FEATHER = 0.075f

/** Colour ramp mirrored from the desktop `.lyrics-line` rules. */
private const val INACTIVE_ALPHA = 0.24f
private const val UNSUNG_ALPHA = 0.28f
private val LINE_SPACING = 14.dp
