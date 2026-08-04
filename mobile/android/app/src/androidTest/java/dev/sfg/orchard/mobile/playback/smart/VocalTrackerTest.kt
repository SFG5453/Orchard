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

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vocal-presence tracking end to end.
 *
 * The failure modes here are quiet ones. The model's tensor is bin-major `[1, 2, bins, frames]`,
 * the padding is a re-stride rather than an append, and the band reduction maps hertz to bins;
 * get any of those wrong and the result is still a plausible-looking curve in [0, 1], just one
 * that describes nothing. So these check the contract and the shape of the output rather than
 * trying to assert what a synthetic signal "should" score.
 */
class VocalTrackerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = VocalSpectrogram.sampleRate

    /** Broadband harmonic content in the vocal band, over a bass line. */
    private fun material(seconds: Double, withMidBand: Boolean): Pair<FloatArray, FloatArray> {
        val random = Random(3)
        val count = (seconds * rate).toInt()
        val left = FloatArray(count)
        val right = FloatArray(count)
        for (index in 0 until count) {
            val time = index / rate
            var value = 0.35 * sin(2.0 * PI * 80.0 * time)
            if (withMidBand) {
                // A formant-ish stack inside the 200 Hz - 4 kHz band the reduction reads.
                for (harmonic in 1..6) {
                    value += 0.12 * sin(2.0 * PI * 260.0 * harmonic * time) / harmonic
                }
            }
            value += random.nextDouble(-0.02, 0.02)
            left[index] = value.toFloat().coerceIn(-1f, 1f)
            // Slightly decorrelated, so a bug that reads one channel twice is visible.
            right[index] = (value * 0.9 + random.nextDouble(-0.02, 0.02)).toFloat().coerceIn(-1f, 1f)
        }
        return left to right
    }

    @Test
    fun theFrontEndMatchesTheModelContract() {
        assertTrue("native library missing", VocalSpectrogram.available)
        assertEquals(2049, VocalSpectrogram.bins)
        assertEquals(44_100.0, VocalSpectrogram.sampleRate, 0.0)
        assertEquals(1024, VocalSpectrogram.hop)
        assertEquals(4096, VocalSpectrogram.fftSize)
    }

    @Test
    fun theSpectrogramIsBinMajorAndCorrectlySized() {
        val (left, right) = material(5.0, withMidBand = true)
        val spectrogram = VocalSpectrogram.compute(left, right)
        assertNotNull("front end produced nothing", spectrogram)
        // Two channels of bins x frames, flattened. A frame-major layout would be the same length
        // but wrong everywhere, so the length check is necessary and not sufficient; the model
        // loading and producing a sane curve below is what covers the ordering.
        assertEquals(
            2 * VocalSpectrogram.bins * spectrogram!!.frames,
            spectrogram.values.size,
        )
        assertTrue(spectrogram.frames > 100)
        assertTrue("magnitudes must be non-negative", spectrogram.values.all { it >= 0f })
    }

    @Test
    fun aWrongSampleRateIsRefused() {
        val (left, right) = material(2.0, withMidBand = true)
        assertNull(VocalSpectrogram.compute(left, right, rate = 22_050.0))
    }

    @Test
    fun producesOneValuePerFrameInRange() {
        val tracker = VocalTracker(context)
        try {
            val seconds = 10.0
            val (left, right) = material(seconds, withMidBand = true)
            val curve = tracker.track(left, right, rate)
            assertNotNull("no vocal curve produced", curve)

            val expected = (seconds * VocalSpectrogram.frameRate).toInt()
            Log.i(TAG, "curve ${curve!!.size} frames, expected ~$expected")
            // Within a couple of frames of the STFT's own framing.
            assertTrue("got ${curve.size} frames for $expected", kotlin.math.abs(curve.size - expected) < 5)
            assertTrue("values outside [0,1]", curve.all { it in 0f..1f })

            val mean = curve.average()
            Log.i(TAG, "mean presence $mean min ${curve.min()} max ${curve.max()}")
            // A degenerate all-zero or all-one curve means the mask arithmetic collapsed.
            assertTrue("curve is degenerate at $mean", mean > 0.0 && mean < 1.0)
        } finally {
            tracker.release()
        }
    }

    @Test
    fun aWindowLongerThanTheModelIsRefusedRatherThanTruncated() {
        val tracker = VocalTracker(context)
        try {
            // The model's width is fixed at 960 frames (~22.3 s). Silently truncating would give a
            // curve that stops early while claiming to describe the whole window.
            val (left, right) = material(30.0, withMidBand = true)
            assertNull(tracker.track(left, right, rate))
        } finally {
            tracker.release()
        }
    }

    @Test
    fun shortInputIsPaddedWithoutContaminatingTheCurve() {
        val tracker = VocalTracker(context)
        try {
            // Well under the fixed width, so most of the tensor is zero padding. The returned curve
            // must cover only the real audio; folding the padding in would drag every short window
            // toward silence.
            val seconds = 3.0
            val (left, right) = material(seconds, withMidBand = true)
            val curve = tracker.track(left, right, rate)!!
            val expected = (seconds * VocalSpectrogram.frameRate).toInt()
            Log.i(TAG, "short window ${curve.size} frames, expected ~$expected")
            assertTrue(kotlin.math.abs(curve.size - expected) < 5)
            assertTrue("padded window collapsed to silence", curve.average() > 0.0)
        } finally {
            tracker.release()
        }
    }

    private companion object {
        const val TAG = "VocalTrackerTest"
    }
}
