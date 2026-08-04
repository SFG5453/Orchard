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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of beat tracking: peak picking and what is derived from a grid.
 *
 * These need neither the model nor the device, so they run on the JVM. What they pin down is the
 * arithmetic the transition policy ultimately gates on: a confidence that drifts, or a tempo read
 * an octave out, changes which tier every transition lands in.
 */
class BeatGridMathTest {

    /** Logits with a clear peak every [period] frames. */
    private fun peaked(frames: Int, period: Int, height: Float = 3f): FloatArray {
        val logits = FloatArray(frames) { -1f }
        var frame = 0
        while (frame < frames) {
            logits[frame] = height
            if (frame - 1 >= 0) logits[frame - 1] = height / 3
            if (frame + 1 < frames) logits[frame + 1] = height / 3
            frame += period
        }
        return logits
    }

    @Test
    fun peaksAreFoundWhereTheLogitsPeak() {
        val peaks = BeatTracker.pickPeaks(peaked(frames = 500, period = 25))
        assertEquals(20, peaks.size)
        // Symmetric neighbours mean the parabolic refinement should not move the peak.
        assertEquals(0.0, peaks.first(), 0.01)
        assertEquals(25.0, peaks[1], 0.01)
    }

    @Test
    fun negativeLogitsAreNeverPeaks() {
        assertTrue(BeatTracker.pickPeaks(FloatArray(200) { -2f }).isEmpty())
        assertTrue(BeatTracker.pickPeaks(FloatArray(200)).isEmpty())
    }

    @Test
    fun anAsymmetricPeakIsRefinedOffTheFrameGrid() {
        // The model's frame rate is 50 Hz, so an integer peak quantizes beats to 20 ms. The
        // refinement is what recovers where the maximum actually sits.
        val logits = FloatArray(60) { -1f }
        logits[30] = 3f
        logits[29] = 1f
        logits[31] = 2f
        val peak = BeatTracker.pickPeaks(logits).single()
        assertTrue("expected the peak pulled toward frame 31, got $peak", peak > 30.0 && peak < 30.5)
    }

    @Test
    fun adjacentTiedFramesCollapseToOnePeak() {
        val logits = FloatArray(60) { -1f }
        logits[30] = 3f
        logits[31] = 3f
        assertEquals(1, BeatTracker.pickPeaks(logits).size)
    }

    @Test
    fun tempoComesFromTheMedianInterval() {
        val beats = (0 until 32).map { it * 0.5 }
        assertEquals(120.0, BeatTracker.tempoFromBeats(beats), 0.01)

        val fast = (0 until 32).map { it * (60.0 / 174.0) }
        assertEquals(174.0, BeatTracker.tempoFromBeats(fast), 0.01)
    }

    @Test
    fun aFewDroppedBeatsDoNotDragTheTempo() {
        // A hole in the grid leaves a double-length gap; the second pass over gaps close to the
        // first estimate is what keeps it from halving the reported tempo.
        val beats = (0 until 32).map { it * 0.5 }.filterIndexed { index, _ -> index !in setOf(7, 18) }
        assertEquals(120.0, BeatTracker.tempoFromBeats(beats), 1.0)
    }

    @Test
    fun implausibleTemposAreRejectedRatherThanReported() {
        // The planner takes a returned BPM at face value, so "no answer" has to beat a wrong one.
        assertEquals(0.0, BeatTracker.tempoFromBeats((0 until 32).map { it * 2.0 }), 0.0)
        assertEquals(0.0, BeatTracker.tempoFromBeats((0 until 32).map { it * 0.05 }), 0.0)
        assertEquals(0.0, BeatTracker.tempoFromBeats(listOf(0.0, 0.5, 1.0)), 0.0)
    }

    @Test
    fun confidenceRewardsRegularityAndDecisiveness() {
        val even = (0 until 32).map { it * 0.5 }
        val strong = BeatTracker.gridConfidence(even, List(even.size) { 3.0 })
        val weak = BeatTracker.gridConfidence(even, List(even.size) { 0.0 })
        assertTrue("decisive peaks should score higher: $strong vs $weak", strong > weak)
        assertTrue("confidence is capped below certainty, got $strong", strong <= 0.95)

        val ragged = List(32) { it * 0.5 + (if (it % 3 == 0) 0.18 else 0.0) }
        assertTrue(
            "an irregular grid should score below an even one",
            BeatTracker.gridConfidence(ragged, List(ragged.size) { 3.0 }) < strong,
        )
    }

    @Test
    fun tooShortAGridCarriesNoConfidence() {
        // Below this the policy must see 0, which is what keeps a handful of scattered peaks from
        // authorizing a beat-matched transition.
        assertEquals(0.0, BeatTracker.gridConfidence(listOf(0.0, 0.5, 1.0), listOf(3.0, 3.0, 3.0)), 0.0)
    }

    @Test
    fun aCatalogTempoCouldNeverReachTheBeatmatchGate() {
        // Cross-check against the policy: even a perfect grid caps at 0.95, and the gate is 0.55,
        // so the two ends of this pipeline are consistent.
        val even = (0 until 64).map { it * 0.5 }
        val best = BeatTracker.gridConfidence(even, List(even.size) { 8.0 })
        assertTrue(best > MIN_BEATMATCH_CONFIDENCE)
        assertTrue(best < 1.0)
    }
}
