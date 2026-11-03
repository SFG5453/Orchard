/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class MixSplicerTest {
    private val rate = 48_000

    // Noise, so a join off by even one frame shows up as a mismatch.
    private fun noise(frames: Int, seed: Int) = Random(seed).let { random ->
        ShortArray(frames * 2) { random.nextInt(-12_000, 12_000).toShort() }
    }

    private fun splicer(startSeconds: Double): MixSplicer = MixSplicer().apply {
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.Builder()
            .setPositionOffsetUs((startSeconds * 1_000_000).toLong())
            .build())
    }

    /** Feeds [input] in decoder-sized pieces and collects everything the splicer emits. */
    private fun run(splicer: MixSplicer, input: ShortArray, piece: Int = 1_111): ShortArray {
        val out = ArrayList<Short>()
        fun drain() {
            val buffer = splicer.output.order(ByteOrder.nativeOrder()).asShortBuffer()
            while (buffer.hasRemaining()) out += buffer.get()
        }
        var frame = 0
        val frames = input.size / 2
        while (frame < frames) {
            val count = minOf(piece, frames - frame)
            val bytes = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(input, frame * 2, count * 2)
            splicer.queueInput(bytes)
            drain()
            frame += count
        }
        return out.toShortArray()
    }

    @Test
    fun `idle stereo preserves pcm in a bounded read only buffer`() {
        val samples = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -1, 1, 0, 1234)
        val input = ByteBuffer.allocateDirect(samples.size * 2 + 8).order(ByteOrder.nativeOrder())
        input.position(4)
        samples.forEach { input.putShort(it) }
        input.limit(input.position()).position(4)
        val bounded = input.asReadOnlyBuffer()
        val splicer = splicer(0.0)
        splicer.queueInput(bounded)
        assertEquals(bounded.limit(), bounded.position())
        val actual = ShortArray(samples.size)
        splicer.output.order(ByteOrder.nativeOrder()).asShortBuffer().get(actual)
        assertArrayEquals(samples, actual)
    }

    @Test
    fun `a fade armed after idle buffers uses the running media clock`() {
        val splicer = splicer(10.0)
        val first = noise(rate, seed = 5)
        assertArrayEquals(first, run(splicer, first))
        splicer.fade = MixSplicer.Fade(11_000_000, 500_000, fadeIn = false)
        val out = run(splicer, ShortArray(rate * 2) { 10_000 })
        assertEquals(10_000.toShort(), out[0])
        assertEquals(7_071.0, out[rate / 2].toDouble(), 2.0)
        assertTrue(out.copyOfRange(rate, out.size).all { it == 0.toShort() })
    }

    @Test
    fun `multichannel input bypasses the stereo splicer`() {
        val splicer = MixSplicer()
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, splicer.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT)))
        splicer.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(splicer.isActive)
    }

    @Test
    fun `idle mono still expands to stereo`() {
        val splicer = MixSplicer().apply {
            configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE).flip()
        splicer.queueInput(input)
        val actual = ShortArray(4)
        splicer.output.order(ByteOrder.nativeOrder()).asShortBuffer().get(actual)
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MAX_VALUE), actual)
    }

    @Test
    fun `the render takes over on the audio both sides share`() {
        val song = noise(rate * 4, seed = 1)
        val start = rate // one second into this decode
        // The render opens on the outgoing song at unity, 10 ms later than planned, then turns
        // into something else entirely.
        val drift = 480
        val render = song.copyOfRange((start + drift) * 2, (start + rate) * 2) + noise(rate, seed = 2)
        val splicer = splicer(10.0)
        splicer.outgoing = MixSplicer.Outgoing(render, 10L * rate + start, rate)
        val out = run(splicer, song)

        val cut = (splicer.cutFrame - 10L * rate).toInt()
        assertTrue("render never took over", cut > start)
        // Native audio up to the cut, then the render continues the same audio without a seam.
        assertArrayEquals(song.copyOfRange(0, (start + rate - drift) * 2), out.copyOfRange(0, (start + rate - drift) * 2))
        assertEquals(cut - start - drift, splicer.cutRenderFrame.toInt())
    }

    @Test
    fun `the incoming song opens on its resume frame, faded in`() {
        val song = noise(rate, seed = 3)
        val splicer = splicer(0.0)
        val resume = rate / 2L
        splicer.incoming = MixSplicer.Incoming(resume, rate)
        val out = run(splicer, song)
        val opening = (resume - MixSplicer.HANDOFF_FADE).toInt()
        assertTrue(out.copyOfRange(0, opening * 2).all { it == 0.toShort() })
        assertArrayEquals(song.copyOfRange(resume.toInt() * 2, song.size), out.copyOfRange(resume.toInt() * 2, out.size))
        assertTrue(splicer.unmuted)
    }

    @Test
    fun `retiming while muted moves the opening and is reported as skipped`() {
        val song = noise(rate, seed = 4)
        val splicer = splicer(0.0)
        splicer.incoming = MixSplicer.Incoming(rate / 2L, rate)
        splicer.shift(300) // the incoming clock ran late: drop 300 frames
        val out = run(splicer, song)
        assertEquals(song.size - 600, out.size)
        assertEquals(300L, splicer.skippedFrames)
        // The resume frame now plays 300 output frames earlier.
        val resume = rate / 2
        assertArrayEquals(song.copyOfRange(resume * 2, song.size), out.copyOfRange((resume - 300) * 2, out.size))
    }

    @Test
    fun `a fade-out follows media time and ends in silence`() {
        val song = ShortArray(rate * 4) { 10_000 } // two seconds of constant stereo
        val splicer = splicer(10.0)
        // Armed early, as the engine does: from 11 s, half a second long.
        splicer.fade = MixSplicer.Fade(11_000_000, 500_000, fadeIn = false)
        val out = run(splicer, song)
        assertTrue(out.copyOfRange(0, rate * 2).all { it == 10_000.toShort() })
        val middle = (rate + rate / 4) * 2 // halfway: cos(pi/4)
        assertEquals(7_071.0, out[middle].toDouble(), 2.0)
        assertTrue(out.copyOfRange((rate + rate / 2) * 2, out.size).all { it == 0.toShort() })
    }

    @Test
    fun `a finished fade-in clears itself so the next song starts at full level`() {
        val splicer = splicer(0.0)
        splicer.fade = MixSplicer.Fade(0, 250_000, fadeIn = true)
        val out = run(splicer, ShortArray(rate) { 10_000 })
        assertEquals(0.toShort(), out[0])
        assertEquals(10_000.toShort(), out[out.size - 1])
        assertEquals(null, splicer.fade)
        // The next item flushes back to zero; nothing fades again.
        splicer.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue(run(splicer, ShortArray(rate / 10) { 10_000 }).all { it == 10_000.toShort() })
    }
}
