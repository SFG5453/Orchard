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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope and structure half of Smart Crossfade, on device.
 *
 * What matters here is not exact numbers, as the analyzer's thresholds were tuned against real
 * material and a synthetic loop is not that, but that the fields the transition policy gates on are
 * populated and self-consistent. The energy curve in particular is what decides whether an interior
 * mix-out anchor skips silence or skips a minute of music, so a curve that is empty or mis-scaled
 * silently disables the whole discarded-music budget.
 */
class TrackFeaturesTest {

    private val rate = TrackFeatures.sampleRate

    /**
     * A track-shaped signal: a rhythmic body, then trailing silence. The silence is the point, as
     * it is what the content-end detector is supposed to find.
     */
    private fun trackWithTail(
            bodySeconds: Double,
            silenceSeconds: Double,
            bpm: Double = 120.0
    ): FloatArray {
        val random = Random(5)
        val total = ((bodySeconds + silenceSeconds) * rate).toInt()
        val samples = FloatArray(total)
        val body = (bodySeconds * rate).toInt()
        val beatSeconds = 60.0 / bpm

        var beat = 0
        while (beat * beatSeconds < bodySeconds) {
            val start = (beat * beatSeconds * rate).toInt()
            val length = (0.25 * rate).toInt()
            for (offset in 0 until length) {
                val index = start + offset
                if (index >= body) break
                val time = offset / rate
                val envelope = exp(-time / 0.06)
                val tone = sin(2.0 * PI * 60.0 * time)
                val hiss = random.nextDouble(-1.0, 1.0) * 0.25
                samples[index] += (0.8 * envelope * (tone + hiss)).toFloat()
            }
            beat += 1
        }
        for (index in 0 until body) {
            samples[index] =
                    (samples[index] + 0.15f * sin(2.0 * PI * 110.0 * index / rate).toFloat())
                            .coerceIn(-1f, 1f)
        }
        return samples
    }

    @Test
    fun producesAnEnergyCurveOverTheWholeTrack() {
        val duration = 70.0
        val features = TrackFeatures.analyze(trackWithTail(60.0, 10.0), duration)
        assertNotNull("analyzer returned nothing", features)
        Log.i(
                TAG,
                "duration=${features!!.duration} contentEnd=${features.contentEndTime} " +
                        "energy=${features.energyCurve.size} low=${features.lowEnergyCurve.size} " +
                        "mixOut=${features.mixOutCandidates.size} mixIn=${features.mixInCandidates.size} " +
                        "key=${features.key} vocalProb=${features.vocalProbability}",
        )

        assertTrue("energy curve was empty", features.energyCurve.size > 10)
        // The curve has to span the track, since audibleSecondsBetween integrates over it to decide
        // what a mix-out anchor would skip.
        assertTrue("curve stops early", features.energyCurve.last().time > duration * 0.8)
        assertTrue(
                "curve times are not ordered",
                features.energyCurve.zipWithNext().all { it.first.time <= it.second.time }
        )
        assertTrue("energies are negative", features.energyCurve.all { it.energy >= 0 })
    }

    @Test
    fun findsTheEndOfContentBeforeTheEndOfTheFile() {
        // Ten seconds of trailing silence. Without this the transition would fade across silence
        // rather than across the outro.
        val features = TrackFeatures.analyze(trackWithTail(60.0, 10.0), 70.0)!!
        Log.i(TAG, "contentEnd=${features.contentEndTime} of 70.0")
        assertTrue(
                "contentEndTime ${features.contentEndTime} did not land inside the file",
                features.contentEndTime > 0 && features.contentEndTime <= 70.0,
        )
        assertTrue(
                "contentEndTime ${features.contentEndTime} ignored ten seconds of trailing silence",
                features.contentEndTime < 68.0,
        )
    }

    @Test
    fun theEnergyCurveDistinguishesMusicFromSilence() {
        // This is the property the discarded-music budget depends on: silence must measure as
        // silence, or every interior anchor gets charged as though it skipped music.
        val features = TrackFeatures.analyze(trackWithTail(60.0, 10.0), 70.0)!!
        val analysis = TrackAnalysis(energyCurve = features.energyCurve, duration = 70.0)

        val overMusic = audibleSecondsBetween(analysis, 5.0, 55.0)
        val overSilence = audibleSecondsBetween(analysis, 62.0, 69.0)
        Log.i(TAG, "audible over music=$overMusic over silence=$overSilence")

        assertNotNull(overMusic)
        assertNotNull(overSilence)
        assertTrue("music measured as $overMusic seconds audible", overMusic!! > 30.0)
        assertTrue("silence measured as $overSilence seconds audible", overSilence!! < 3.0)
    }

    @Test
    fun shortOrEmptyInputIsRefusedRatherThanGuessed() {
        assertTrue(TrackFeatures.analyze(FloatArray(0), 10.0) == null)
        // A fraction of a second cannot support envelope or structure analysis; whatever comes back
        // must not claim a content end beyond the audio.
        val tiny = TrackFeatures.analyze(FloatArray(256), 0.02)
        if (tiny != null) assertTrue(tiny.contentEndTime <= 1.0)
    }

    private companion object {
        const val TAG = "TrackFeaturesTest"
    }
}
