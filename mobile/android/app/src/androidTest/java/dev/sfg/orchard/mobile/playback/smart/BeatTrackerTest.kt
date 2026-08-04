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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tracking: synthetic audio in, a beat grid out.
 *
 * This is the test that exercises the whole chain the crossfade planner depends on, the resampler,
 * native mel front end, ONNX session, peak picking, and tempo. Every stage is verified in isolation
 * elsewhere; what this pins down is that they agree with each other about *time*. A front end that
 * is off by a frame, or a mel contract that drifted, produces a grid that is internally consistent
 * and wrong, which is the failure the transition policy cannot detect on its own.
 */
class BeatTrackerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = MelSpectrogram.sampleRate

    /**
     * A crude but genuinely rhythmic pattern: kick on every beat, snare on the backbeat, hats on
     * eighths, and a sustained bass. Not music, but it has the onset structure the model was
     * trained to read, which a bare click track does not.
     */
    private fun drumLoop(seconds: Double, bpm: Double): FloatArray {
        val random = Random(11)
        val samples = FloatArray((seconds * rate).toInt())
        val beatSeconds = 60.0 / bpm

        fun hit(atSeconds: Double, decay: Double, hz: Double, level: Double, noise: Double) {
            val start = (atSeconds * rate).toInt()
            val length = (decay * 4 * rate).toInt()
            for (offset in 0 until length) {
                val index = start + offset
                if (index !in samples.indices) break
                val time = offset / rate
                val envelope = exp(-time / decay)
                val tone = sin(2.0 * PI * hz * time)
                val hiss = random.nextDouble(-1.0, 1.0)
                samples[index] += (level * envelope * (tone * (1 - noise) + hiss * noise)).toFloat()
            }
        }

        var beat = 0
        while (beat * beatSeconds < seconds) {
            val at = beat * beatSeconds
            // Kick every beat, harder on the downbeat so the bar is readable.
            hit(at, decay = 0.09, hz = 55.0, level = if (beat % 4 == 0) 0.95 else 0.7, noise = 0.05)
            // Snare on 2 and 4.
            if (beat % 4 == 1 || beat % 4 == 3)
                    hit(at, decay = 0.06, hz = 190.0, level = 0.5, noise = 0.7)
            // Hats on the eighths.
            hit(at + beatSeconds / 2, decay = 0.015, hz = 8000.0, level = 0.18, noise = 0.9)
            beat += 1
        }

        // A sustained bass under it all, so the spectrum is not purely transient.
        for (index in samples.indices) {
            samples[index] += (0.12 * sin(2.0 * PI * 82.0 * index / rate)).toFloat()
            samples[index] = samples[index].coerceIn(-1f, 1f)
        }
        return samples
    }

    private fun trackAt(bpm: Double, seconds: Double = 28.0): BeatTracker.Grid? {
        val tracker = BeatTracker(context)
        return try {
            tracker.track(drumLoop(seconds, bpm))
        } finally {
            tracker.release()
        }
    }

    @Test
    fun tracksASteadyTempo() {
        val grid = trackAt(120.0)
        assertNotNull("no grid returned for a 120 BPM loop", grid)
        Log.i(TAG, "120 BPM -> ${grid!!.bpm} conf ${grid.beatConfidence} beats ${grid.beats.size}")

        assertTrue("reported ${grid.bpm} for a 120 BPM loop", abs(grid.bpm - 120.0) < 2.0)
        assertTrue(
                "confidence ${grid.beatConfidence} too low to be usable",
                grid.beatConfidence > 0.4
        )
    }

    @Test
    fun tracksAFasterTempo() {
        // A second tempo guards against a grid that happens to be right at one rate because of an
        // off-by-one that cancels there.
        val grid = trackAt(140.0)
        assertNotNull("no grid returned for a 140 BPM loop", grid)
        Log.i(TAG, "140 BPM -> ${grid!!.bpm} conf ${grid.beatConfidence}")
        assertTrue("reported ${grid.bpm} for a 140 BPM loop", abs(grid.bpm - 140.0) < 2.5)
    }

    @Test
    fun beatsAreEvenlySpacedAndCoverTheAudio() {
        val grid = trackAt(120.0)!!
        val expected = 60.0 / 120.0
        val gaps = grid.beats.zipWithNext { left, right -> right - left }
        val irregular = gaps.count { abs(it - expected) > expected * 0.15 }
        assertTrue("$irregular of ${gaps.size} gaps were not one beat", irregular < gaps.size / 10)
        // A 28 s window at 120 BPM holds ~56 beats; well over half should be found.
        assertTrue("only ${grid.beats.size} beats across 28 s", grid.beats.size > 40)
    }

    @Test
    fun downbeatsAreASubsetOfBeatsAndFallOnBars() {
        val grid = trackAt(120.0)!!
        for (downbeat in grid.downbeats) {
            assertTrue(
                    "downbeat $downbeat is not one of the beats",
                    grid.beats.any { abs(it - downbeat) < 1e-9 },
            )
        }
        // Four beats to the bar at 120 BPM is 2 s. The model may miss bars, but the gaps it does
        // report should be whole numbers of bars.
        val gaps = grid.downbeats.zipWithNext { left, right -> right - left }
        val offBar = gaps.count { gap -> abs(gap / 2.0 - (gap / 2.0).let(Math::round)) > 0.12 }
        Log.i(TAG, "downbeats ${grid.downbeats.size}, off-bar gaps $offBar of ${gaps.size}")
        assertTrue(
                "$offBar of ${gaps.size} downbeat gaps were not a whole bar",
                offBar <= gaps.size / 4
        )
    }

    @Test
    fun theOffsetMapsTheGridBackOntoTheTrack() {
        // A grid tracked on a decoded region has to report times on the full track's timeline,
        // or every transition would be placed at the wrong point of the song.
        val tracker = BeatTracker(context)
        try {
            val pcm = drumLoop(20.0, 120.0)
            val base = tracker.track(pcm) ?: error("no grid")
            val shifted = tracker.track(pcm, offsetSeconds = 100.0) ?: error("no shifted grid")
            assertTrue(shifted.beats.size == base.beats.size)
            for (index in base.beats.indices) {
                assertTrue(abs((shifted.beats[index] - 100.0) - base.beats[index]) < 1e-6)
            }
            assertTrue(abs(shifted.firstBeat - (base.firstBeat + 100.0)) < 1e-6)
        } finally {
            tracker.release()
        }
    }

    @Test
    fun silenceProducesNoGridRatherThanAWrongOne() {
        val tracker = BeatTracker(context)
        try {
            // The policy treats a null grid as no evidence and degrades; it has no defence against
            // a confident grid invented from nothing.
            val grid = tracker.track(FloatArray((10 * rate).toInt()))
            if (grid != null) {
                Log.i(TAG, "silence produced bpm ${grid.bpm} conf ${grid.beatConfidence}")
                assertTrue(
                        "silence produced a trusted grid",
                        grid.beatConfidence < MIN_BEATMATCH_CONFIDENCE
                )
            }
        } finally {
            tracker.release()
        }
    }

    private companion object {
        const val TAG = "BeatTrackerTest"
    }
}
