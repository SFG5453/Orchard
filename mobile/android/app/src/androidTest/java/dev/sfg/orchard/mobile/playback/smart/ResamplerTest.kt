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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the sample-rate conversion feeding the beat model.
 *
 * The interesting case is not that a tone survives conversion, it is that a tone *above* the output
 * Nyquist does not survive it as something else. Decimation without a filter folds 15 kHz down to 7
 * kHz, which is inaudible as a fault but lands squarely in the mel bands the beat tracker reads, so
 * it would produce a confidently wrong grid.
 */
class ResamplerTest {

    private fun tone(seconds: Double, hz: Double, rate: Double): FloatArray {
        val count = (seconds * rate).toInt()
        return FloatArray(count) { index -> sin(2.0 * PI * hz * index / rate).toFloat() * 0.5f }
    }

    private fun rms(samples: FloatArray, skip: Int = 0): Double {
        var sum = 0.0
        var count = 0
        for (index in skip until samples.size - skip) {
            sum += samples[index].toDouble() * samples[index]
            count += 1
        }
        return if (count > 0) sqrt(sum / count) else 0.0
    }

    /** Zero crossings per second, which recovers a pure tone's frequency without an FFT. */
    private fun frequency(samples: FloatArray, rate: Double, skip: Int): Double {
        var crossings = 0
        for (index in skip + 1 until samples.size - skip) {
            if (samples[index - 1] < 0f && samples[index] >= 0f) crossings += 1
        }
        val span = (samples.size - 2 * skip) / rate
        return crossings / span
    }

    @Test
    fun matchingRatesArePassedThrough() {
        val samples = tone(1.0, 440.0, 22_050.0)
        val out = MelSpectrogram.resample(samples, 22_050.0, 22_050.0)
        assertNotNull(out)
        assertTrue(samples.contentEquals(out!!))
    }

    @Test
    fun outputLengthFollowsTheRatio() {
        val samples = tone(2.0, 440.0, 44_100.0)
        val out = MelSpectrogram.resample(samples, 44_100.0, 22_050.0)!!
        assertTrue(
                "halving gave ${out.size} from ${samples.size}",
                abs(out.size - samples.size / 2) <= 2
        )

        val up = MelSpectrogram.resample(tone(1.0, 440.0, 22_050.0), 22_050.0, 44_100.0)!!
        assertTrue("doubling gave ${up.size}", abs(up.size - 22_050 * 2) <= 2)
    }

    @Test
    fun anInBandToneKeepsItsPitchAndLevel() {
        val rate = 44_100.0
        val samples = tone(2.0, 440.0, rate)
        val out = MelSpectrogram.resample(samples, rate, 22_050.0)!!

        // Edges are excluded: the kernel is clamped there, so the first and last few hundred
        // samples are not representative of steady-state behaviour.
        val skip = 512
        assertEquals(440.0, frequency(out, 22_050.0, skip), 2.0)
        // A sine at amplitude 0.5 has RMS 0.5/sqrt(2) ≈ 0.354.
        assertEquals(0.354, rms(out, skip), 0.02)
    }

    @Test
    fun a48kSourceConvertsCleanly() {
        // 48000 -> 22050 is 160:147, the awkward ratio; 44.1k is an easy 2:1 and would hide a
        // resampler that only handles integer factors.
        val out = MelSpectrogram.resample(tone(2.0, 1000.0, 48_000.0), 48_000.0, 22_050.0)!!
        val skip = 512
        assertEquals(1000.0, frequency(out, 22_050.0, skip), 5.0)
        assertEquals(0.354, rms(out, skip), 0.02)
    }

    @Test
    fun contentAboveTheOutputNyquistIsRemovedRatherThanFolded() {
        val rate = 44_100.0
        // 15 kHz is well above the 11.025 kHz output Nyquist. Naive decimation would alias it to
        // 44100/2 = 22050, i.e. straight into the mel bands the model reads.
        val out = MelSpectrogram.resample(tone(2.0, 15_000.0, rate), rate, 22_050.0)!!
        val skip = 1024
        val level = rms(out, skip)

        // Against an input RMS of ~0.354, anything surviving here is aliasing.
        assertTrue(
                "15 kHz survived conversion at RMS $level, the anti-alias filter is not working",
                level < 0.02,
        )
    }

    @Test
    fun aToneJustBelowNyquistStillSurvives() {
        // The filter must not be so aggressive that it eats the top of the usable band; the mel
        // filterbank reads up to 11 kHz.
        val out = MelSpectrogram.resample(tone(2.0, 8_000.0, 44_100.0), 44_100.0, 22_050.0)!!
        assertTrue("8 kHz should pass", rms(out, 1024) > 0.2)
    }

    @Test
    fun silenceStaysSilent() {
        val out = MelSpectrogram.resample(FloatArray(44_100), 44_100.0, 22_050.0)!!
        assertTrue(out.all { abs(it) < 1e-6f })
    }
}
