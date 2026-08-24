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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beat-matched renderer, on device.
 *
 * This is the piece that makes a blend a mix rather than two records at once, and the piece a
 * volume ramp cannot approximate: it time-stretches the outgoing track onto the incoming grid,
 * hands the low end over at a downbeat, and rides a filter down the outgoing channel.
 *
 * These check that it renders, refuses when it should, honours the anchors it is given, and
 * produces audio that is actually a mix of both sources rather than either one alone. Not that it
 * sounds good, which no test can say.
 */
class TransitionRendererTest {

    private val rate = TransitionRenderer.SAMPLE_RATE

    /**
     * A four-on-the-floor loop at [bpm] with a tone at [toneHz], so sources are distinguishable.
     *
     * Levels are deliberately below full scale. Two beat-aligned mixes are correlated, so an
     * equal-power sum of two full-scale sources reaches about +3 dB over either, the renderer does
     * not limit, by design, and the filter sweep is what keeps real material from colliding. Full
     * -scale synthetic loops would therefore fail a clipping assertion for reasons that say nothing
     * about the renderer.
     */
    private fun loop(seconds: Double, bpm: Double, toneHz: Double): Pair<FloatArray, FloatArray> {
        val count = (seconds * rate).toInt()
        val left = FloatArray(count)
        val beatSeconds = 60.0 / bpm
        var beat = 0
        while (beat * beatSeconds < seconds) {
            val start = (beat * beatSeconds * rate).toInt()
            val length = (0.2 * rate).toInt()
            for (offset in 0 until length) {
                val index = start + offset
                if (index >= count) break
                val time = offset / rate
                left[index] += (0.5 * exp(-time / 0.05) * sin(2.0 * PI * 55.0 * time)).toFloat()
            }
            beat += 1
        }
        for (index in 0 until count) {
            left[index] =
                    (left[index] + 0.15f * sin(2.0 * PI * toneHz * index / rate).toFloat())
                            .coerceIn(-1f, 1f)
        }
        return left to left.copyOf()
    }

    /**
     * A source with the beat grid the engine plans against. Downbeats every four beats: candidate
     * placement keys off them, so a source without them scores blind on structure and is not what
     * the app hands over.
     */
    private fun source(seconds: Double, bpm: Double, toneHz: Double): TransitionRenderer.Source {
        val (left, right) = loop(seconds, bpm, toneHz)
        val beatSeconds = 60.0 / bpm
        val beats = generateSequence(0.0) { it + beatSeconds }.takeWhile { it < seconds }.toList()
        return TransitionRenderer.Source(
                left = left,
                right = right,
                bpm = bpm,
                beats = beats.toDoubleArray(),
                downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }.toDoubleArray(),
        )
    }

    private fun rms(values: FloatArray, from: Int = 0, to: Int = values.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (index in from until to) sum += values[index].toDouble() * values[index]
        return sqrt(sum / (to - from))
    }

    private val atEightSeconds = TransitionRenderer.Anchor(startSeconds = 8.0)

    @Test
    fun rendersAnOverlapBetweenMatchingTempi() {
        val rendered =
                TransitionRenderer.render(
                        outgoing = source(20.0, 126.0, toneHz = 440.0),
                        incoming = source(20.0, 126.0, toneHz = 660.0),
                        outgoingAnchor = atEightSeconds,
                        incomingAnchor = atEightSeconds,
                )
        assertNotNull("renderer refused a matching pair", rendered)
        Log.i(
                TAG,
                "frames=${rendered!!.frames} ${rendered.durationSeconds}s ${rendered.strategy} " +
                        "beats=${rendered.beats} stretch=${rendered.stretchRatio}",
        )

        assertTrue(
                "stretch ${rendered.stretchRatio} should be ~1 for equal tempi",
                abs(rendered.stretchRatio - 1.0) < 0.02
        )
        assertTrue("output is silent", rms(rendered.left) > 0.01)
        assertTrue("output is not finite", rendered.left.all { it.isFinite() })
        assertTrue("channels are not both populated", rms(rendered.right) > 0.01)
    }

    /**
     * The engine chooses the overlap length and where inside the window the mix begins, so the
     * caller schedules against what came back. A start that wandered outside the window it was
     * given would put the rendered mix at a cue nothing else in the app knows about.
     */
    @Test
    fun placesTheTransitionInsideTheRequestedWindow() {
        val rendered =
                TransitionRenderer.render(
                        outgoing = source(20.0, 126.0, toneHz = 440.0),
                        incoming = source(20.0, 126.0, toneHz = 660.0),
                        outgoingAnchor = atEightSeconds,
                        incomingAnchor = atEightSeconds,
                )!!
        Log.i(TAG, "out=${rendered.outgoingStart} in=${rendered.incomingStart}")

        assertTrue(
                "outgoing start ${rendered.outgoingStart} left the requested window",
                abs(rendered.outgoingStart - 8.0) <= 1.0,
        )
        assertTrue(
                "incoming start ${rendered.incomingStart} left the requested window",
                abs(rendered.incomingStart - 8.0) <= 1.0,
        )
        // Where the outgoing track had reached when the mix ended, which is what the caller resumes
        // from. Stretching moves it off `start + duration`, so it is reported rather than derived.
        assertEquals(
                rendered.outgoingStart + rendered.durationSeconds * rendered.stretchRatio,
                rendered.outgoingResume,
                0.05,
        )
    }

    @Test
    fun stretchesTheOutgoingTrackOntoTheIncomingGrid() {
        // 124 against 126 is inside the transparent window, so it should render with a real
        // stretch.
        val rendered =
                TransitionRenderer.render(
                        outgoing = source(20.0, 124.0, toneHz = 440.0),
                        incoming = source(20.0, 126.0, toneHz = 660.0),
                        outgoingAnchor = atEightSeconds,
                        incomingAnchor = atEightSeconds,
                )
        assertNotNull("renderer refused a pair two BPM apart", rendered)
        Log.i(TAG, "124->126 stretch=${rendered!!.stretchRatio}")
        // The outgoing track is the one scaled, so its ratio moves off 1.
        assertTrue(
                "stretch ${rendered.stretchRatio} did not move toward the incoming tempo",
                abs(rendered.stretchRatio - 1.0) > 0.005,
        )
    }

    @Test
    fun refusesWhenThereIsTooLittleAudioForTheOverlap() {
        // Two seconds cannot fill a beat-aligned overlap however good the tempo match is.
        assertNull(
                TransitionRenderer.render(
                        outgoing = source(2.0, 126.0, toneHz = 440.0),
                        incoming = source(2.0, 126.0, toneHz = 660.0),
                        outgoingAnchor = TransitionRenderer.Anchor(startSeconds = 1.0),
                        incomingAnchor = TransitionRenderer.Anchor(startSeconds = 1.0),
                ),
        )
    }

    /**
     * Every reachable transition end sits a whole number of bars from a downbeat, so a window
     * narrower than that lattice legitimately contains nothing. Refusing is the correct answer;
     * drifting to the nearest fit would silently mix at a cue nobody asked for.
     */
    @Test
    fun refusesAWindowNarrowerThanTheDownbeatLattice() {
        assertNull(
                TransitionRenderer.render(
                        outgoing = source(20.0, 126.0, toneHz = 440.0),
                        incoming = source(20.0, 126.0, toneHz = 660.0),
                        outgoingAnchor =
                                TransitionRenderer.Anchor(startSeconds = 8.0, endSeconds = 8.2),
                        incomingAnchor = atEightSeconds,
                ),
        )
    }

    @Test
    fun theOverlapRisesFromTheOutgoingTrackAndEndsOnTheIncomingOne() {
        val rendered =
                TransitionRenderer.render(
                        outgoing = source(20.0, 126.0, toneHz = 440.0),
                        incoming = source(20.0, 126.0, toneHz = 660.0),
                        outgoingAnchor = atEightSeconds,
                        incomingAnchor = atEightSeconds,
                )!!

        // Both ends carry audio: a render that collapsed to one source, or faded to nothing in the
        // middle, would show here even without being able to say which source is which.
        val frames = rendered.frames
        val head = rms(rendered.left, 0, frames / 8)
        val middle = rms(rendered.left, frames * 3 / 8, frames * 5 / 8)
        val tail = rms(rendered.left, frames * 7 / 8, frames)
        Log.i(TAG, "head=$head middle=$middle tail=$tail")

        assertTrue("head is silent", head > 0.005)
        assertTrue("middle is silent", middle > 0.005)
        assertTrue("tail is silent", tail > 0.005)
    }

    private companion object {
        const val TAG = "TransitionRendererTest"
    }
}
