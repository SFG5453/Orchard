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

import dev.sfg.orchard.mobile.model.Track
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionChoreographyParityTest {

    private fun createTrack(id: String, duration: Double): Track = Track(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        durationMs = (duration * 1000).toLong(),
        artworkUrl = "",
    )

    @Test
    fun `blinding lights to dont start now is non beatmatched and duration is 0 to 4s`() {
        val outgoing = TrackAnalysis(
            trackId = "blinding_lights",
            duration = 200.0,
            bpm = 85.492,
            beatInterval = 0.70182,
            beatConfidence = 0.947,
            key = "F minor",
            keyConfidence = 0.9,
            contentEndTime = 198.0,
            phraseBoundaries = listOf(168.4368, 179.6659, 190.895),
            downbeats = listOf(168.4368, 171.2441, 174.0514, 176.8586, 179.6659, 182.4732, 185.2805, 188.0877, 190.895),
            mixOutCandidates = listOf(
                MixCandidate(179.6659, 0.92, "phrase"),
                MixCandidate(190.895, 0.88, "outro"),
            ),
            vocalProbability = 0.78,
        )
        val incoming = TrackAnalysis(
            trackId = "dont_start_now",
            duration = 183.0,
            bpm = 123.97,
            beatInterval = 0.483988,
            beatConfidence = 0.95,
            key = "B minor",
            keyConfidence = 0.92,
            audibleStartTime = 0.05,
            pickupTime = 0.1,
            contentEndTime = 181.0,
            phraseBoundaries = listOf(0.1, 7.8438, 15.5876, 23.3314),
            downbeats = listOf(0.1, 2.0359, 3.9719, 5.9078, 7.8438, 9.7797, 11.7157, 13.6516, 15.5876),
            mixInCandidates = listOf(
                MixCandidate(0.1, 0.95, "intro"),
                MixCandidate(7.8438, 0.88, "verse"),
            ),
            vocalProbability = 0.92,
        )

        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = createTrack("blinding_lights", 200.0),
            nextTrack = createTrack("dont_start_now", 183.0),
            currentTime = 170.0,
            mode = CrossfadeMode.SMART,
        )

        assertFalse(plan.blocked)
        assertTrue(plan.transitionStyle != TransitionStyle.DJ_BLEND)
        assertTrue("fade seconds must be <= 4.0s but got ${plan.fadeSeconds}", plan.fadeSeconds <= 4.0 + 1e-4)

        val choreography = plan.choreography
        assertNotNull(choreography)
        assertTrue(choreography!!.duration <= 4.0 + 1e-4)
        assertTrue(
            choreography.strategy == ChoreographyStrategy.FILTERED_HANDOFF ||
            choreography.strategy == ChoreographyStrategy.CLEAN_CUT
        )
    }

    @Test
    fun `safe instrumental blend receives staged blend with single bass owner`() {
        val outgoing = TrackAnalysis(
            trackId = "inst_a",
            duration = 210.0,
            bpm = 124.0,
            beatInterval = 0.483871,
            beatConfidence = 0.96,
            key = "C major",
            keyConfidence = 0.95,
            contentEndTime = 208.0,
            phraseBoundaries = listOf(178.0645, 193.5484, 201.2903),
            downbeats = listOf(178.0645, 180.0, 181.9355, 183.871, 185.8065, 187.7419, 189.6774, 191.6129, 193.5484, 195.4839, 197.4194, 199.3548, 201.2903),
            mixOutCandidates = listOf(
                MixCandidate(193.5484, 0.95, "break"),
                MixCandidate(201.2903, 0.90, "outro"),
            ),
            vocalProbability = 0.05,
        )
        val incoming = TrackAnalysis(
            trackId = "inst_b",
            duration = 220.0,
            bpm = 124.0,
            beatInterval = 0.483871,
            beatConfidence = 0.97,
            key = "G major",
            keyConfidence = 0.96,
            audibleStartTime = 0.0,
            pickupTime = 0.0,
            contentEndTime = 218.0,
            phraseBoundaries = listOf(0.0, 15.4839, 30.9677),
            downbeats = listOf(0.0, 1.9355, 3.8710, 5.8065, 7.7419, 9.6774, 11.6129, 13.5484, 15.4839),
            mixInCandidates = listOf(
                MixCandidate(0.0, 0.95, "intro"),
                MixCandidate(15.4839, 0.90, "drop"),
            ),
            vocalProbability = 0.05,
        )

        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = createTrack("inst_a", 210.0),
            nextTrack = createTrack("inst_b", 220.0),
            currentTime = 180.0,
            mode = CrossfadeMode.SMART,
        )

        assertFalse(plan.blocked)
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertTrue("Beats must be in 8..16 but got ${plan.transitionBeats}", plan.transitionBeats in 8..16)

        val choreography = plan.choreography
        assertNotNull(choreography)
        assertEquals(ChoreographyStrategy.STAGED_BLEND, choreography!!.strategy)

        // Test single bass owner automation
        val outBassStart = evaluateAutomationCurve(choreography.curves.outgoingBass, 0.0)
        val inBassStart = evaluateAutomationCurve(choreography.curves.incomingBass, 0.0)
        assertTrue(abs(outBassStart - 1.0) < 1e-3)
        assertTrue(abs(inBassStart - 0.0) < 1e-3)

        val outBassEnd = evaluateAutomationCurve(choreography.curves.outgoingBass, 1.0)
        val inBassEnd = evaluateAutomationCurve(choreography.curves.incomingBass, 1.0)
        assertTrue(abs(outBassEnd - 0.0) < 1e-3)
        assertTrue(abs(inBassEnd - 1.0) < 1e-3)
    }
}
