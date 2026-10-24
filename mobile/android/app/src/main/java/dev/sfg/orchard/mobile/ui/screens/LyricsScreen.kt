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

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Auto-scrolling, word-timed lyric list matching desktop fullscreen lyrics fidelity. */
@Composable
internal fun LyricLines(
    lines: List<LyricLine>,
    songId: String,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
    accent: Color = CanopyColors.LyricActive,
    blurDistant: Boolean = true,
    fadeDistant: Boolean = true,
    staggered: Boolean = true,
) {
    // Playback snapshots arrive twice a second. Keep one frame-clock coroutine alive between
    // them and fold small clock corrections in gently; restarting the animation at every
    // snapshot makes the highlight visibly hop backwards on some devices.
    // Read through the clock inside the coroutine so ticks never recompose the list.
    val clock = dev.sfg.orchard.mobile.ui.components.LocalPlayerClock.current
    val latestPosition by remember(clock) { derivedStateOf { clock.reported().positionMs } }
    val latestPlaying by rememberUpdatedState(playing)
    val synced = remember(lines) { lines.any { it.startMs != null } }
    val initialPosition = remember(lines) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { latestPosition.toFloat() }
    }
    val smoothPosition = produceState(initialPosition, lines) {
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

    val translation = rememberLyricTranslation(lines, songId)
    val translations = remember(translation, lines) { translation.translationsFor(lines) }

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
    val glide = remember(lines) { LyricGlide() }
    LaunchedEffect(dragging) {
        if (dragging) {
            browsing = true
            glide.settle()
        } else if (browsing) {
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
                glide.scrollTo(listState, activeDisplayIndex, staggered && synced)
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
                            active = active && playing,
                            accent = accent,
                            onClick = {
                                browsing = false
                                onSeek(item.startMs)
                            },
                            alignment = if (isAlternate) Alignment.End else Alignment.Start,
                            progress = {
                                val span = (item.endMs - item.startMs).toFloat().coerceAtLeast(1f)
                                ((smoothPosition.value - item.startMs) / span).coerceIn(0f, 1f)
                            },
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

                        // One flat rest level like desktop, so upcoming lines stay readable.
                        val targetAlpha = when {
                            !synced -> UNSYNCED_ALPHA
                            active -> 1.0f
                            browsing -> BROWSING_ALPHA
                            else -> INACTIVE_ALPHA
                        }
                        val animatedAlpha by animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec = tween(280),
                            label = "LyricAlpha",
                        )
                        // Distance from the line being sung; browsing shows every line crisp.
                        val depth = if (synced && !browsing && activeDisplayIndex >= 0) abs(displayIndex - activeDisplayIndex) else 0
                        val depthFade by animateFloatAsState(
                            targetValue = if (fadeDistant && depth > 0) (1f - depth * DEPTH_FADE_STEP).coerceAtLeast(DEPTH_FADE_FLOOR) else 1f,
                            animationSpec = tween(380),
                            label = "LyricDepthFade",
                        )
                        val depthBlur by animateFloatAsState(
                            targetValue = if (blurDistant && canBlurLyrics && depth in 1..DEPTH_BLUR_RANGE) minOf(depth * DEPTH_BLUR_STEP_DP, DEPTH_BLUR_MAX_DP) else 0f,
                            animationSpec = tween(380),
                            label = "LyricDepthBlur",
                        )
                        val density = LocalDensity.current.density

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (isAlternate) 48.dp else 0.dp)
                                .graphicsLayer {
                                    scaleX = emphasis.value
                                    scaleY = emphasis.value
                                    alpha = animatedAlpha * depthFade
                                    translationY = glide.shift(displayIndex)
                                    renderEffect = if (depthBlur > 0.05f) {
                                        val radius = depthBlur * density
                                        BlurEffect(radius, radius, TileMode.Decal)
                                    } else null
                                    transformOrigin = if (isAlternate) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
                                }
                                .clickable(enabled = line.startMs != null) {
                                    browsing = false
                                    line.startMs?.let(onSeek)
                                }
                                .padding(vertical = LINE_SPACING),
                            horizontalAlignment = if (isAlternate) Alignment.End else Alignment.Start,
                        ) {
                            val translated = translations?.getOrNull(item.originalIndex)
                            if (translated != null) {
                                TranslatedLyricLine(
                                    line = line,
                                    translation = translated,
                                    synced = synced,
                                    active = active,
                                    isAlternate = isAlternate,
                                    accent = accent,
                                    positionMs = smoothPosition,
                                )
                            } else if (line.words.isNotEmpty()) {
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
                                    color = Color.White,
                                    textAlign = if (isAlternate) TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth(),
                                    // Unsynced lyrics read as prose, matching desktop's smaller medium weight.
                                    style = (if (synced) lyricTextStyle() else unsyncedLyricStyle()).copy(
                                        shadow = if (active) Shadow(accent.copy(alpha = 0.4f), blurRadius = 24f) else null,
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

        LyricTranslateChip(
            state = translation,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp),
        )

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

private const val NANOS_PER_MILLISECOND = 1_000_000f
private const val SEEK_SNAP_THRESHOLD_MS = 250f
private const val CLOCK_CORRECTION_FACTOR = 0.35f
internal const val LYRIC_LINE_LEAD_MS = 250f
internal const val LYRIC_WORD_LEAD_MS = 80f
internal const val HIGHLIGHT_FEATHER = 0.075f

/** Colour ramp mirrored from the desktop `.lyrics-line` rules. */
internal const val INACTIVE_ALPHA = 0.24f
internal const val UNSUNG_ALPHA = 0.28f
internal const val ADLIB_UNSUNG_ALPHA = 0.48f
private const val BROWSING_ALPHA = 0.68f
private const val UNSYNCED_ALPHA = 0.72f
private val LINE_SPACING = 11.dp
private const val DEPTH_FADE_STEP = 0.14f
private const val DEPTH_FADE_FLOOR = 0.55f
private const val DEPTH_BLUR_RANGE = 6
private const val DEPTH_BLUR_STEP_DP = 1.1f
private const val DEPTH_BLUR_MAX_DP = 5f
private val canBlurLyrics = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
