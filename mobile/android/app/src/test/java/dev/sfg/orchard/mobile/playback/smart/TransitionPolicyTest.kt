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

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from Orchard desktop's `test/transitionPolicy.test.js`, expectations included. */
class TransitionPolicyTest {

    private fun energyCurve(duration: Int, silentFrom: Int = Int.MAX_VALUE): List<EnergySample> =
        (0 until duration).map { time ->
            EnergySample(time.toDouble(), if (time >= silentFrom) 0.0 else 0.8)
        }

    private fun maskedAnalysis(values: List<Double>, secondsPerSample: Double = 1.0) = TrackAnalysis(
        energyCurve = values.indices.map { EnergySample(it * secondsPerSample, 0.8) },
        vocalActivityMask = values,
    )

    @Test
    fun `trusted grids within the stretch window rate a beat-matched transition`() {
        val policy = assessTransitionTier(
            TrackAnalysis(bpm = 126.0, beatConfidence = 0.9),
            TrackAnalysis(bpm = 124.0, beatConfidence = 0.7),
        )
        assertEquals(TransitionTier.BEATMATCHED, policy.tier)
        assertEquals(emptyList<String>(), policy.reasons)
        assertEquals(0.7, policy.beatConfidence, 1e-9)
    }

    @Test
    fun `octave-distant tempi are counted on the shared grid before judging`() {
        val policy = assessTransitionTier(
            TrackAnalysis(bpm = 126.0, beatConfidence = 0.9),
            TrackAnalysis(bpm = 63.0, beatConfidence = 0.9),
        )
        assertEquals(TransitionTier.BEATMATCHED, policy.tier)
    }

    @Test
    fun `tempo distance demotes to the DJ-assisted tier`() {
        val policy = assessTransitionTier(
            TrackAnalysis(bpm = 126.0, beatConfidence = 0.9),
            TrackAnalysis(bpm = 100.0, beatConfidence = 0.9),
        )
        assertEquals(TransitionTier.DJ_ASSISTED, policy.tier)
        assertEquals("tempo-distance", policy.reasons.first())
    }

    @Test
    fun `one weak beat grid demotes to the DJ-assisted tier`() {
        val policy = assessTransitionTier(
            TrackAnalysis(bpm = 126.0, beatConfidence = 0.9),
            TrackAnalysis(bpm = 126.0, beatConfidence = 0.3),
        )
        assertEquals(TransitionTier.DJ_ASSISTED, policy.tier)
        assertEquals(listOf("beat-confidence"), policy.reasons)
    }

    @Test
    fun `missing tempo or two untrusted grids bottom out at a plain crossfade`() {
        assertEquals(
            TransitionTier.PLAIN_CROSSFADE,
            assessTransitionTier(
                TrackAnalysis(bpm = 0.0, beatConfidence = 0.9),
                TrackAnalysis(bpm = 126.0, beatConfidence = 0.9),
            ).tier,
        )
        // A catalog BPM lookup merges in at beatConfidence 0, so metadata alone can never
        // authorize beat-matching however plausible the tempo looks.
        val catalogOnly = assessTransitionTier(
            TrackAnalysis(bpm = 120.0, beatConfidence = 0.0),
            TrackAnalysis(bpm = 120.0, beatConfidence = 0.1),
        )
        assertEquals(TransitionTier.PLAIN_CROSSFADE, catalogOnly.tier)
        assertEquals(listOf("beat-confidence"), catalogOnly.reasons)
    }

    @Test
    fun `audible seconds count music and ignore silence`() {
        val analysis = TrackAnalysis(energyCurve = energyCurve(100, 60))
        assertTrue(abs(audibleSecondsBetween(analysis, 0.0, 59.0)!! - 60) < 1.5)
        assertEquals(0.0, audibleSecondsBetween(analysis, 60.0, 99.0)!!, 1e-9)
        assertNull(audibleSecondsBetween(TrackAnalysis(), 0.0, 10.0))
    }

    @Test
    fun `an outro marker never anchors a transition ahead of real music`() {
        // The native analyzer marks an outro up to 48s before content end.
        val anchor = resolveMixOutAnchor(
            TrackAnalysis(
                contentEndTime = 240.0,
                outroStartTime = 192.0,
                energyCurve = energyCurve(240),
            ),
        )
        assertEquals("content_end", anchor.type)
        assertEquals(240.0, anchor.time, 1e-9)
    }

    @Test
    fun `a genuine silence cliff still anchors the transition`() {
        val anchor = resolveMixOutAnchor(
            TrackAnalysis(
                contentEndTime = 264.0,
                mixOutTime = 188.0,
                energyCurve = energyCurve(264, 188),
            ),
        )
        assertEquals("interior_mix_out", anchor.type)
        assertEquals(188.0, anchor.time, 1e-9)
        assertEquals(0.0, anchor.discardedMusicSeconds, 1e-9)
    }

    @Test
    fun `a silence cliff followed by more music is not a mix-out`() {
        // A four-second gap, then the final chorus. Skipping here would cut the song short even
        // though the analyzer scored the boundary highly.
        val curve = energyCurve(264).map {
            if (it.time >= 188 && it.time < 192) it.copy(energy = 0.0) else it
        }
        val anchor = resolveMixOutAnchor(
            TrackAnalysis(contentEndTime = 264.0, mixOutTime = 188.0, energyCurve = curve),
        )
        assertEquals("content_end", anchor.type)
    }

    @Test
    fun `an analysis with no energy curve charges the whole gap against the budget`() {
        val anchor = resolveMixOutAnchor(
            TrackAnalysis(contentEndTime = 240.0, mixOutTime = 190.0),
        )
        assertEquals("content_end", anchor.type)
    }

    @Test
    fun `vocal activity is averaged over the window and absent masks read as no evidence`() {
        val analysis = maskedAnalysis(listOf(0.0, 0.0, 1.0, 1.0))
        assertEquals(0.5, vocalActivityBetween(analysis, 0.0, 3.0)!!, 1e-9)
        assertEquals(1.0, vocalActivityBetween(analysis, 2.0, 3.0)!!, 1e-9)
        assertNull(vocalActivityBetween(TrackAnalysis(), 0.0, 3.0))
    }

    @Test
    fun `a clash needs evidence on both sides`() {
        assertTrue(isVocalClash(0.9, 0.8))
        assertTrue(!isVocalClash(0.9, null))
        assertTrue(!isVocalClash(null, null))
        assertTrue(!isVocalClash(0.9, 0.2))
    }

    @Test
    fun `drops outrank phrase lines as entry points`() {
        val ranked = rankMixInCandidates(
            TrackAnalysis(
                bpm = 120.0,
                audibleStartTime = 0.0,
                mixInCandidates = listOf(
                    MixCandidate(time = 16.0, score = 0.5, type = "phrase"),
                    MixCandidate(time = 32.0, score = 0.5, type = "main_drop"),
                ),
            ),
        )
        assertEquals("main_drop", ranked.first().type)
        assertEquals(32.0, ranked.first().time, 1e-9)
    }

    @Test
    fun `a cold open is penalized against an entry with an intro to bed under`() {
        val analysis = TrackAnalysis(
            bpm = 120.0,
            audibleStartTime = 0.0,
            mixInCandidates = listOf(
                MixCandidate(time = 0.5, score = 0.6, type = "main_drop"),
                MixCandidate(time = 32.0, score = 0.5, type = "main_drop"),
            ),
        )
        assertEquals(32.0, rankMixInCandidates(analysis).first().time, 1e-9)
    }

    @Test
    fun `tempo octave alignment folds a half-time reading onto the shared grid`() {
        assertEquals(126.0, alignTempoOctave(126.0, 63.0), 1e-9)
        assertEquals(126.0, alignTempoOctave(126.0, 252.0), 1e-9)
        assertEquals(124.0, alignTempoOctave(126.0, 124.0), 1e-9)
    }
}
