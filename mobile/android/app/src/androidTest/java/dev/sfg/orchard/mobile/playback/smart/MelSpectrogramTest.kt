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
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridge tests for the native mel front end.
 *
 * The DSP itself is a byte-for-byte port of Orchard desktop's validated
 * Earmark's shared Rust mel frontend, so its correctness is inherited rather than re-derived
 * here. What is new on Android, and therefore what these test, is the JNI marshalling and the
 * frame/time mapping the beat grid is read through, a spectrogram that is right but off by a frame
 * would put every predicted beat 20 ms out.
 */
class MelSpectrogramTest {

    private fun tone(
            seconds: Double,
            hz: Double,
            rate: Double = MelSpectrogram.sampleRate
    ): FloatArray {
        val count = (seconds * rate).toInt()
        return FloatArray(count) { index -> sin(2.0 * PI * hz * index / rate).toFloat() * 0.5f }
    }

    @Test
    fun nativeLibraryLoads() {
        assertTrue("Rust analyzer and resampler libraries did not load", MelSpectrogram.available)
        assertEquals(128, MelSpectrogram.mels)
        assertEquals(22_050.0, MelSpectrogram.sampleRate, 0.0)
        assertEquals(441, MelSpectrogram.hop)
        // 441 samples at 22.05 kHz is exactly 20 ms, so the model's frame rate is 50/s. Beat
        // times are frame indices divided by this, so it is part of the contract.
        assertEquals(50.0, MelSpectrogram.frameRate, 1e-9)
    }

    @Test
    fun framesFollowTheHopWithCentredPadding() {
        val seconds = 5.0
        val samples = tone(seconds, 440.0)
        val spectrogram = MelSpectrogram.compute(samples)
        assertNotNull(spectrogram)

        // center=True reflect-pads by fft/2 on both sides, so the frame count is
        // (n + 2*(fft/2) - fft)/hop + 1, which reduces to n/hop + 1.
        val expected = samples.size / MelSpectrogram.hop + 1
        assertEquals(expected, spectrogram!!.frames)
        assertEquals(spectrogram.frames * MelSpectrogram.mels, spectrogram.values.size)
        assertEquals(seconds, spectrogram.durationSeconds, 0.05)
    }

    @Test
    fun outputIsFiniteAndNonNegative() {
        // log1p(1000 * magnitude) cannot be negative; a NaN here means the FFT marshalled wrong.
        val spectrogram = MelSpectrogram.compute(tone(2.0, 440.0))!!
        for (value in spectrogram.values) {
            assertTrue("non-finite value $value", value.isFinite())
            assertTrue("negative value $value", value >= 0f)
        }
    }

    @Test
    fun aToneLandsInTheExpectedBand() {
        // Two tones an octave and a half apart must peak in different bands, and the higher tone
        // in the higher band. This catches a mel filterbank marshalled in reverse or off by an
        // index without needing to hardcode Slaney band edges.
        val low = MelSpectrogram.compute(tone(2.0, 220.0))!!
        val high = MelSpectrogram.compute(tone(2.0, 3520.0))!!

        fun peakBand(spectrogram: MelSpectrogram.Spectrogram): Int {
            val middle = spectrogram.frames / 2
            var best = 0
            for (band in 0 until spectrogram.mels) {
                val value = spectrogram.values[middle * spectrogram.mels + band]
                if (value > spectrogram.values[middle * spectrogram.mels + best]) best = band
            }
            return best
        }

        val lowBand = peakBand(low)
        val highBand = peakBand(high)
        assertTrue("220 Hz peaked at band $lowBand, expected the low end", lowBand < 40)
        assertTrue(
                "3520 Hz peaked at band $highBand, below the 220 Hz band $lowBand",
                highBand > lowBand
        )
    }

    @Test
    fun theWrongSampleRateIsRefusedRatherThanResampled() {
        assertNull(MelSpectrogram.compute(tone(2.0, 440.0, rate = 44_100.0), sampleRate = 44_100.0))
    }

    @Test
    fun tooShortAnInputIsRefused() {
        assertNull(MelSpectrogram.compute(FloatArray(64)))
        assertNull(MelSpectrogram.compute(FloatArray(0)))
    }

    @Test
    fun repeatedCallsAgree() {
        // The bridge allocates a fresh array per call; a stale or shared buffer would show here.
        val samples = tone(1.5, 660.0)
        val first = MelSpectrogram.compute(samples)!!
        val second = MelSpectrogram.compute(samples)!!
        assertTrue(first.values.contentEquals(second.values))
    }
}
