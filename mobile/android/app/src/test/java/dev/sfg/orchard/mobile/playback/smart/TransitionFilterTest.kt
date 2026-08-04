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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter ride, as pure DSP on the JVM.
 *
 * A sweep that does not actually attenuate is a silent failure: the transition still happens, still
 * sounds like a plain crossfade, and nothing reports a fault, which is exactly the bug that made
 * Smart Crossfade sound like nothing for most pairs. So these measure attenuation rather than
 * checking that the code ran.
 */
class TransitionFilterTest {

    private val rate = 44_100
    private val channels = 2

    private fun configured(): TransitionFilter = TransitionFilter().apply {
        configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        flush()
    }

    /** Runs a sine through the processor and returns the output RMS. */
    private fun through(filter: TransitionFilter, hz: Double, seconds: Double = 0.5): Double {
        val frames = (seconds * rate).toInt()
        val input = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val value = (0.5 * sin(2.0 * PI * hz * frame / rate) * 32767).toInt().toShort()
            repeat(channels) { input.putShort(value) }
        }
        input.flip()

        var sum = 0.0
        var count = 0
        // The first tenth is skipped: a biquad settles, and its transient is not its response.
        val settle = frames / 10
        filter.queueInput(input)
        val output = filter.output
        var frame = 0
        while (output.remaining() >= channels * 2) {
            val value = output.short / 32768.0
            repeat(channels - 1) { output.short }
            if (frame >= settle) {
                sum += value * value
                count += 1
            }
            frame += 1
        }
        return if (count > 0) sqrt(sum / count) else 0.0
    }

    @Test
    fun anOpenFilterPassesTheSignalUntouched() {
        // The resting state has to be transparent: a filter always in circuit colours playback even
        // when no transition is happening.
        val filter = configured()
        assertTrue(filter.transparent)
        val level = through(filter, 1_000.0)
        assertEquals("open filter changed the level", 0.354, level, 0.02)
    }

    @Test
    fun aClosedSweepTakesTheTopAwayAndKeepsTheBottom() {
        // The point of the sweep: high content goes first, low content survives. A filter that
        // attenuated both equally would just be a fade with extra steps.
        val high = configured().apply { lowPassHz = 400.0 }
        val low = configured().apply { lowPassHz = 400.0 }

        val highLevel = through(high, 6_000.0)
        val lowLevel = through(low, 150.0)

        assertTrue("6 kHz survived a 400 Hz corner at $highLevel", highLevel < 0.02)
        assertTrue("150 Hz was removed by a 400 Hz corner at $lowLevel", lowLevel > 0.25)
    }

    @Test
    fun theSweepIsProgressiveRatherThanASwitch() {
        // The ear follows the movement; a corner that jumped would be heard as an effect.
        val levels = listOf(12_000.0, 4_000.0, 1_000.0).map { corner ->
            through(configured().apply { lowPassHz = corner }, 3_000.0)
        }
        assertTrue("attenuation did not increase as the corner closed: $levels", levels[0] > levels[1])
        assertTrue("attenuation did not increase as the corner closed: $levels", levels[1] > levels[2])
    }

    @Test
    fun theBassShelfTakesTheLowEndWithoutTouchingTheRest() {
        // The handover is a level change on the low band, not its removal, and it must leave the
        // mids alone; otherwise both tracks lose body at the swap.
        val lowBand = through(configured().apply { bassGain = 0.12 }, 80.0)
        val midBand = through(configured().apply { bassGain = 0.12 }, 2_000.0)

        assertTrue("80 Hz was not ducked, at $lowBand", lowBand < 0.12)
        assertTrue("2 kHz was affected by the bass shelf, at $midBand", midBand > 0.30)
    }

    @Test
    fun gainScalesTheWholeSignal() {
        val level = through(configured().apply { gain = 0.5 }, 1_000.0)
        assertEquals(0.177, level, 0.02)
    }

    @Test
    fun clearingAutomationReturnsItToTransparent() {
        val filter = configured().apply {
            lowPassHz = 300.0
            bassGain = 0.1
            gain = 0.4
        }
        assertTrue(!filter.transparent)
        filter.clearAutomation()
        assertTrue(filter.transparent)
        assertEquals(0.354, through(filter, 1_000.0), 0.02)
    }

    @Test
    fun unsupportedEncodingsAreRefusedRatherThanCorrupted() {
        // Float PCM through a 16-bit inner loop would be noise, not a quiet failure worth risking.
        try {
            TransitionFilter().configure(
                AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT),
            )
            assertTrue("float PCM should have been refused", false)
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
            // Media3 falls back to another processor chain rather than failing playback.
        }
    }
}
