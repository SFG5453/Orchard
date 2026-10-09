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

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.components.LocalPlayerClock
import dev.sfg.orchard.mobile.ui.components.Playhead
import dev.sfg.orchard.mobile.ui.components.TRANSITION_LABEL
import dev.sfg.orchard.mobile.ui.components.durationText
import dev.sfg.orchard.mobile.ui.components.playheadStepMs
import dev.sfg.orchard.mobile.ui.components.rememberPlayheadTick
import dev.sfg.orchard.mobile.ui.components.rememberRainbowBrush
import dev.sfg.orchard.mobile.ui.components.rememberTransitionGlow
import dev.sfg.orchard.mobile.ui.components.transitionStyleLabel
import kotlin.math.abs

/** A seek the player has not reported back yet; shown so the bar does not snap back meanwhile. */
private data class SeekHold(val targetMs: Long, val atMs: Long)

/**
 * The scrubber draws straight from [LocalPlayerClock]. Position never enters composition; only
 * the second-granular labels do, so a playing track recomposes this once a second at most.
 */
@Composable
fun PlayerScrubber(
    playback: PlaybackSnapshot,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The planned transition out of this track, drawn as a band on the track. Null when none. */
    transition: TransitionMarker? = null,
    /** Raw overlap progress when the visible track has already changed to the incoming song. */
    mixProgress: Float? = null,
    showBitrate: Boolean = false,
    bitrateKbps: Int = 0,
    isQobuz: Boolean = false,
) {
    val clock = LocalPlayerClock.current
    val trackId = playback.currentTrack?.id
    val marker = transition?.takeIf {
        it.trackId.isNotBlank() && it.trackId == trackId && it.startMs > 0 && it.startMs < playback.durationMs
    }
    // The scrubber ends where the mix starts: the tail after it belongs to the next song.
    val markerEnd = marker?.startMs
    val glow = rememberTransitionGlow(mixProgress ?: 0f)
    // Only spun up during a mix; an idle infinite transition would redraw the app every vsync.
    val rainbow = if (glow > 0.01f) rememberRainbowBrush() else null

    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember(trackId) { mutableStateOf<Float?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var hold by remember(trackId) { mutableStateOf<SeekHold?>(null) }
    LaunchedEffect(hold) {
        if (hold != null) {
            kotlinx.coroutines.delay(SEEK_HOLD_MS)
            hold = null
        }
    }

    val interacting = dragFraction != null || pressed
    val trackHeight = animateDpAsState(
        targetValue = if (interacting) 8.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ScrubberTrackHeight",
    )
    val thumbRadius = animateDpAsState(
        targetValue = if (interacting) 7.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ScrubberThumbRadius",
    )
    val tick = rememberPlayheadTick(
        active = playback.isPlaying && dragFraction == null,
        intervalMs = playheadStepMs(markerEnd ?: playback.durationMs, widthPx),
    )

    // Gesture handlers outlive recompositions, so they read these through updated state.
    val seek by rememberUpdatedState(onSeek)
    val latestEnd by rememberUpdatedState(markerEnd)
    fun end(head: Playhead) = (latestEnd ?: head.durationMs).coerceAtLeast(1)
    fun position(head: Playhead, nowMs: Long): Long {
        val seek = hold ?: return head.positionMs
        if (abs(head.positionMs - seek.targetMs) < SEEK_SETTLE_MS) return head.positionMs
        return seek.targetMs + if (clock.playing) nowMs - seek.atMs else 0
    }
    fun fraction(): Float {
        tick.value
        dragFraction?.let { return it }
        val now = SystemClock.elapsedRealtime()
        val head = clock.projected(now)
        return (position(head, now).toFloat() / end(head)).coerceIn(0f, 1f)
    }
    fun commit(target: Float) {
        val targetMs = (target.coerceIn(0f, 1f) * end(clock.reported())).toLong()
        seek(targetMs)
        hold = SeekHold(targetMs, SystemClock.elapsedRealtime())
    }

    // Elapsed and remaining whole seconds; the derived state only notifies when one changes.
    val labels by remember(trackId, markerEnd) {
        derivedStateOf {
            tick.value
            val now = SystemClock.elapsedRealtime()
            val head = clock.projected(now)
            val total = end(head)
            val shown = dragFraction?.let { (it * total).toLong() } ?: position(head, now)
            val at = shown.coerceIn(0, total)
            at / 1_000 to (total - at + 999) / 1_000
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(trackId) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { commit(it.x / size.width) },
                    )
                }
                .pointerInput(trackId) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let(::commit)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .drawBehind {
                    val played = fraction()
                    val head = clock.reported()
                    val buffered = (head.bufferedMs.toFloat() / end(head)).coerceIn(0f, 1f)
                    drawScrubberTrack(
                        played = played,
                        buffered = buffered,
                        heightPx = trackHeight.value.toPx(),
                        thumbPx = thumbRadius.value.toPx(),
                        rainbow = rainbow,
                        glow = glow,
                    )
                },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (elapsedSec, remainingSec) = labels
            TimeLabel(durationText(elapsedSec * 1_000))
            if (rainbow != null) {
                val styleDesc = transition?.style?.takeIf { it.isNotBlank() }?.let { transitionStyleLabel(it) }
                Text(
                    text = if (styleDesc != null) "$TRANSITION_LABEL • $styleDesc" else TRANSITION_LABEL,
                    modifier = Modifier.alpha(glow),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                        brush = rainbow,
                    ),
                )
            } else if (showBitrate && bitrateKbps > 0) {
                dev.sfg.orchard.mobile.ui.components.LosslessBadge(
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    isQobuz = isQobuz || playback.currentTrack?.isQobuz == true,
                )
            }
            val remaining = "-${durationText(remainingSec * 1_000)}"
            // A rendered mix is waiting at the end of the bar; the shimmer says so before it plays.
            if (marker?.prepared == true && rainbow == null) ReadyTimeLabel(remaining) else TimeLabel(remaining)
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.60f),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
        ),
    )
}

/** Own scope so the per-frame shimmer recomposes this label alone. */
@Composable
private fun ReadyTimeLabel(text: String) {
    val twinkle by rememberInfiniteTransition(label = "ReadySparkle").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ReadySparkleAlpha",
    )
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = 12.sp,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        brush = rememberRainbowBrush(spanPx = 160f, periodMs = 2_400),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "\u2726 ", modifier = Modifier.alpha(twinkle), style = style.copy(fontSize = 10.sp))
        Text(text = text, style = style)
    }
}

private fun DrawScope.drawScrubberTrack(
    played: Float,
    buffered: Float,
    heightPx: Float,
    thumbPx: Float,
    rainbow: Brush?,
    glow: Float,
) {
    val top = (size.height - heightPx) / 2f
    val radius = CornerRadius(heightPx / 2f)
    val playedWidth = size.width * played
    fun bar(width: Float, brush: Brush, alpha: Float = 1f, grow: Float = 0f) = drawRoundRect(
        brush = brush,
        topLeft = Offset(0f, top - grow),
        size = Size(width, heightPx + grow * 2f),
        cornerRadius = CornerRadius(radius.x + grow),
        alpha = alpha,
    )

    // Stacked translucent bands stand in for a blur: cheap enough to draw every tick of a mix.
    if (rainbow != null) {
        for (step in 1..3) bar(playedWidth, rainbow, alpha = glow * 0.22f, grow = step * 3.dp.toPx())
    }
    bar(size.width, SolidColor(TrackIdle))
    if (buffered > 0f) bar(size.width * buffered, SolidColor(TrackBuffered))
    if (played > 0f) {
        bar(playedWidth, SolidColor(Color.White))
        // Rainbow rides over the white fill so the bar keeps its normal colour the instant the mix ends.
        if (rainbow != null) bar(playedWidth, rainbow, alpha = glow)
    }
    if (thumbPx > 0.5f) {
        val centre = Offset(playedWidth, size.height / 2f)
        drawCircle(Color.Black.copy(alpha = 0.25f), thumbPx + 1.5.dp.toPx(), centre)
        drawCircle(Color.White, thumbPx, centre)
    }
}

private val TrackIdle = Color.White.copy(alpha = 0.20f)
private val TrackBuffered = Color.White.copy(alpha = 0.18f)

/** Long enough for a stream to land the seek and publish; short enough to recover from a refusal. */
private const val SEEK_HOLD_MS = 1_500L

/** Within this of the target, the reported clock is trusted again. */
private const val SEEK_SETTLE_MS = 1_200L
