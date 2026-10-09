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

package dev.sfg.orchard.mobile.playback.smart

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Splices a rendered adaptive mix into one player's stream, sample for sample, as desktop's
 * `AudioPipeline` does in its single sink. Android plays the two songs on two players, so each
 * gets one of these at the head of its chain:
 *
 * - the outgoing player swaps its own audio for the render at the planned frame, matched on the
 *   audio both share so the join never repeats or drops a beat;
 * - the incoming player runs muted under the render and opens on the resume frame. While muted it
 *   may drop or insert frames ([shift]) so its resume lands as the render ends; [skippedFrames]
 *   reports the net, which keeps the player's position honest.
 *
 * With no mix armed it carries the standard crossfade's [Fade], keyed to media time so a busy
 * main thread cannot step the ramp.
 *
 * Output is always stereo PCM16, the render's layout; mono songs are doubled.
 */
@UnstableApi
class MixSplicer : BaseAudioProcessor() {
    /** Replace input from [startFrame] (this stream's frames) with [render], stereo PCM16. */
    class Outgoing(val render: ShortArray, val startFrame: Long, val rate: Int)

    /** Silent until [resumeFrame], then the song itself, faded in over the render's last frames. */
    class Incoming(val resumeFrame: Long, val rate: Int)

    /** Equal-power fade over media time [startUs], [durationUs] long. Ignored while a mix is armed. */
    class Fade(val startUs: Long, val durationUs: Long, val fadeIn: Boolean)

    @Volatile var outgoing: Outgoing? = null
    @Volatile var incoming: Incoming? = null

    // A finished fade-in clears itself, or the next song's first seconds would fade in again.
    private val fadeRef = AtomicReference<Fade?>()
    var fade: Fade?
        get() = fadeRef.get()
        set(value) = fadeRef.set(value)

    /** Frames to drop (positive) or insert (negative) while muted. Consumed by the audio thread. */
    private val pendingShift = java.util.concurrent.atomic.AtomicLong()

    /** Net input frames dropped minus silence frames inserted; the sink adds it to the position. */
    @Volatile var skippedFrames = 0L
        private set

    /** Input frame where the render took over, and the render frame that played there. */
    @Volatile var cutFrame = -1L
        private set
    @Volatile var cutRenderFrame = 0L
        private set
    @Volatile var renderDone = false
        private set
    @Volatile var unmuted = false
        private set

    private var inputChannels = 0
    private var rate = 0
    private var position = 0L
    private var renderIndex = -1
    private var fadeIndex = -1
    // Last emitted outgoing frames, for matching the cut on audio both sides share.
    private val recent = ShortArray(ALIGN_WINDOW * 2)
    private var recentFill = 0

    fun shift(frames: Long) { pendingShift.addAndGet(frames) }

    /** Drops both roles and returns to pass-through. Safe from any thread. */
    fun disarm() {
        outgoing = null
        incoming = null
        fade = null
        pendingShift.set(0)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputChannels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        return AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // The sink hands over the media time of the next input sample; frames count from there.
        position = if (streamMetadata.positionOffsetUs == C.TIME_UNSET) 0L
            else (streamMetadata.positionOffsetUs * rate / 1_000_000.0).roundToLong()
        skippedFrames = 0
        renderIndex = -1
        fadeIndex = -1
        recentFill = 0
        cutFrame = -1
        renderDone = false
        unmuted = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * inputChannels)
        if (frames == 0) return
        val input = ShortArray(frames * 2)
        val shorts = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        if (inputChannels == 2) shorts.get(input) else for (i in 0 until frames) {
            val v = shorts.get(i); input[2 * i] = v; input[2 * i + 1] = v
        }
        inputBuffer.position(inputBuffer.limit())
        val out = outgoing
        val into = incoming
        val produced = when {
            out != null && out.rate == rate -> spliceOutgoing(out, input, frames)
            into != null && into.rate == rate -> spliceIncoming(into, input, frames)
            else -> input.also { fade?.let { applyFade(it, input, frames) } }
        }
        position += frames
        val output = replaceOutputBuffer(produced.size * 2)
        output.order(ByteOrder.nativeOrder()).asShortBuffer().put(produced)
        output.position(produced.size * 2)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        // A slowed outgoing side can outlast its file; the render still plays to its end.
        val out = outgoing ?: return
        if (renderIndex < 0 || renderDone) return
        val rest = (out.render.size / 2 - renderIndex).coerceAtLeast(0)
        val tail = ShortArray(rest * 2)
        renderInto(out, tail, 0, rest)
        val output = replaceOutputBuffer(tail.size * 2)
        output.order(ByteOrder.nativeOrder()).asShortBuffer().put(tail)
        output.position(tail.size * 2)
        output.flip()
    }

    private fun applyFade(fade: Fade, samples: ShortArray, frames: Int) {
        val start = fade.startUs * rate / 1_000_000
        val length = max(1L, fade.durationUs * rate / 1_000_000)
        val end = start + length
        if (position + frames <= start) {
            if (fade.fadeIn) samples.fill(0)
            return
        }
        if (position >= end) {
            if (fade.fadeIn) fadeRef.compareAndSet(fade, null) else samples.fill(0)
            return
        }
        for (frame in 0 until frames) {
            val progress = ((position + frame - start).toDouble() / length).coerceIn(0.0, 1.0) * PI / 2
            // Equal power: linear gains dip the perceived loudness mid-fade.
            val gain = if (fade.fadeIn) sin(progress) else cos(progress)
            samples[2 * frame] = (samples[2 * frame] * gain).toInt().toShort()
            samples[2 * frame + 1] = (samples[2 * frame + 1] * gain).toInt().toShort()
        }
    }

    private fun spliceOutgoing(out: Outgoing, input: ShortArray, frames: Int): ShortArray {
        if (renderDone) return ShortArray(frames * 2)
        val total = out.render.size / 2
        if (renderIndex < 0) {
            val switch = out.startFrame + SWITCH_LEAD
            val end = position + frames
            if (end <= switch) return input.also { remember(it, 0, frames) }
            // Armed after the planned start: join the render where it now is, if it still can.
            val cut = max(position, switch)
            val nominal = cut - out.startFrame
            if (nominal + HANDOFF_FADE >= total) { outgoing = null; return input }
            val native = (cut - position).toInt()
            remember(input, 0, native)
            renderIndex = matchingEnd(out.render, total, nominal.toInt())
            cutFrame = cut
            cutRenderFrame = renderIndex.toLong()
            val output = input.copyOf()
            renderInto(out, output, native, frames - native)
            return output
        }
        val output = ShortArray(frames * 2)
        renderInto(out, output, 0, frames)
        return output
    }

    /** Writes [count] render frames at [from], fading out the last [HANDOFF_FADE] into silence. */
    private fun renderInto(out: Outgoing, output: ShortArray, from: Int, count: Int) {
        val total = out.render.size / 2
        for (frame in from until from + count) {
            if (renderIndex >= total) {
                output[2 * frame] = 0; output[2 * frame + 1] = 0
                renderDone = true
                continue
            }
            val fromEnd = total - renderIndex
            // The incoming player fades in over the same frames, carrying the same audio.
            val weight = if (fromEnd <= HANDOFF_FADE) fromEnd.toFloat() / (HANDOFF_FADE + 1) else 1f
            output[2 * frame] = (out.render[2 * renderIndex] * weight).toInt().toShort()
            output[2 * frame + 1] = (out.render[2 * renderIndex + 1] * weight).toInt().toShort()
            renderIndex++
        }
        if (renderIndex >= total) renderDone = true
    }

    private fun spliceIncoming(into: Incoming, input: ShortArray, frames: Int): ShortArray {
        if (fadeIndex >= HANDOFF_FADE) return input
        val opening = into.resumeFrame - HANDOFF_FADE
        var start = 0
        var inserted = 0
        if (fadeIndex < 0) {
            // Retiming is free while nothing is audible.
            val shift = pendingShift.getAndSet(0)
            if (shift > 0) {
                start = min(shift, frames.toLong()).toInt()
                if (shift > start) pendingShift.addAndGet(shift - start)
                skippedFrames += start
            } else if (shift < 0) {
                inserted = min(-shift, MAX_INSERT.toLong()).toInt()
                if (-shift > inserted) pendingShift.addAndGet(shift + inserted)
                skippedFrames -= inserted
            }
        }
        val output = ShortArray((inserted + frames - start) * 2)
        for (frame in start until frames) {
            val absolute = position + frame
            val target = 2 * (inserted + frame - start)
            val gain = when {
                fadeIndex >= HANDOFF_FADE -> 1f
                absolute < opening -> 0f
                else -> {
                    if (fadeIndex < 0) { fadeIndex = 0; unmuted = true }
                    (++fadeIndex).toFloat() / (HANDOFF_FADE + 1)
                }
            }
            output[target] = (input[2 * frame] * gain).toInt().toShort()
            output[target + 1] = (input[2 * frame + 1] * gain).toInt().toShort()
        }
        return output
    }

    private fun remember(source: ShortArray, from: Int, count: Int) {
        val keep = min(count, ALIGN_WINDOW)
        val drop = min(recentFill, ALIGN_WINDOW - keep)
        System.arraycopy(recent, (recentFill - drop) * 2, recent, 0, drop * 2)
        System.arraycopy(source, (from + count - keep) * 2, recent, drop * 2, keep * 2)
        recentFill = drop + keep
    }

    /**
     * Render frame whose preceding [ALIGN_WINDOW] frames best match the outgoing audio just
     * played, within [ALIGN_SEARCH] of [nominal]. Port of desktop's `adaptiveMatchingEnd`.
     */
    private fun matchingEnd(render: ShortArray, total: Int, nominal: Int): Int {
        if (recentFill < ALIGN_WINDOW) return nominal
        val samples = ALIGN_WINDOW * 2
        var needleEnergy = 0.0
        for (i in 0 until samples) needleEnergy += recent[i].toDouble() * recent[i]
        if (needleEnergy < SILENCE) return nominal
        fun score(end: Int): Double {
            if (end < ALIGN_WINDOW || end > total) return -1.0
            val base = (end - ALIGN_WINDOW) * 2
            var dot = 0.0
            var energy = 0.0
            for (i in 0 until samples) {
                val candidate = render[base + i].toDouble()
                dot += recent[i] * candidate
                energy += candidate * candidate
            }
            return if (energy > 0) dot / sqrt(needleEnergy * energy) else -1.0
        }
        // Below 0.5 the "match" is two unrelated passages agreeing by accident.
        var best = nominal
        var bestScore = max(0.5, score(nominal))
        for (distance in 1..ALIGN_SEARCH) {
            for (end in intArrayOf(nominal - distance, nominal + distance)) {
                val value = score(end)
                if (value > bestScore + 1e-9) { best = end; bestScore = value }
            }
        }
        return best
    }

    // Always in circuit: the pipeline is fixed at configure time, and a mix arms mid-stream.
    override fun isActive(): Boolean = super.isActive() && rate != 0

    override fun onReset() {
        disarm()
        inputChannels = 0
        rate = 0
    }

    companion object {
        /** Desktop's `kAlignWindow`, `kAlignSearch` and `kHandoffFade`, in frames. */
        const val ALIGN_WINDOW = 2048
        const val ALIGN_SEARCH = 1440
        const val HANDOFF_FADE = 256
        /** Native frames played past the planned start, so the cut is matched on shared audio. */
        const val SWITCH_LEAD = (ALIGN_WINDOW + ALIGN_SEARCH).toLong()
        /** Silence inserted per buffer while retiming; bounds one output buffer's growth. */
        private const val MAX_INSERT = 4096
        // PCM16 units squared: desktop's 1e-6 float floor.
        private const val SILENCE = 1e-6 * 32768.0 * 32768.0
    }
}

/**
 * Media3's default chain with a [MixSplicer] in front. Frames the splicer drops or inserts while
 * retiming count as skipped output, so the player's position stays on the song's own clock.
 */
@UnstableApi
class SplicingAudioChain(
    private val splicer: MixSplicer,
    vararg processors: AudioProcessor,
) : androidx.media3.common.audio.AudioProcessorChain {
    private val inner = androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
        splicer, *processors,
    )

    override fun getAudioProcessors(): Array<AudioProcessor> = inner.audioProcessors
    override fun applyPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) =
        inner.applyPlaybackParameters(playbackParameters)
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean) = inner.applySkipSilenceEnabled(skipSilenceEnabled)
    override fun getMediaDuration(playoutDuration: Long) = inner.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount() = inner.skippedOutputFrameCount + splicer.skippedFrames
}
