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
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Originally ported from Orchard desktop's `test/wsolaPlanner.test.js`. The shared planning and
 * renderer invariants remain covered here, while mobile-only anchor expectations document the
 * transition experiment without changing desktop behavior.
 */
class WsolaPlannerTest {

    private fun downbeats(bpm: Double, count: Int, offset: Double = 0.0): List<Double> {
        val bar = (60 / bpm) * 4
        return (0 until count).map { offset + it * bar }
    }

    /**
     * An analyzed mix-out is a silence cliff, so model it as one: the policy only honours an
     * interior anchor when what it skips is silence rather than music.
     */
    private fun energyCurveFor(duration: Double, silentAfter: Double = 0.0): List<EnergySample> {
        val curve = mutableListOf<EnergySample>()
        var time = 0.0
        while (time < duration) {
            curve += EnergySample(time, if (silentAfter > 0 && time >= silentAfter) 0.0 else 0.8)
            time += 1.0
        }
        return curve
    }

    private fun analysisFor(
        bpm: Double = 126.0,
        duration: Double = 240.0,
        mixOutTime: Double = 0.0,
        contentEndTime: Double = 0.0,
        mixInTime: Double = 0.0,
    ) = TrackAnalysis(
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = 0.9,
        energyCurve = energyCurveFor(duration, mixOutTime),
        duration = duration,
        mixOutTime = mixOutTime,
        contentEndTime = if (contentEndTime != 0.0) contentEndTime else duration,
        mixInTime = mixInTime,
        downbeats = downbeats(bpm, floor(duration / ((60 / bpm) * 4)).toInt()),
        mixInCandidates = emptyList(),
    )

    /** The vocal-activity mask indexes against [TrackAnalysis.energyCurve] sample times. */
    private fun TrackAnalysis.withVocalMask(isVocalAt: (Double) -> Boolean) = copy(
        vocalActivityMask = energyCurve.map { if (isVocalAt(it.time)) 0.9 else 0.1 },
    )

    private fun planned(result: WsolaPlanResult): WsolaPlanResult.Planned {
        assertTrue(
            "expected a plan, got ${(result as? WsolaPlanResult.Refused)?.reason}",
            result is WsolaPlanResult.Planned,
        )
        return result as WsolaPlanResult.Planned
    }

    private fun refusal(result: WsolaPlanResult): String {
        assertTrue("expected a refusal, got a plan", result is WsolaPlanResult.Refused)
        return (result as WsolaPlanResult.Refused).reason
    }

    @Test
    fun `plans a fade on the shared grid`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0, mixOutTime = 220.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        // A 20s intro at 126 BPM covers far more than four bars, so the fade is held to the cap
        // rather than spending the whole intro.
        assertEquals(16, plan.fadeBeats)
        assertEquals(16, plan.beats)
        val outgoingOverlap = plan.beats * (60 / 126.0)
        assertTrue(plan.transitionStart <= 220 - outgoingOverlap + 0.001)
        val bar = (60 / 126.0) * 4
        assertTrue(
            abs(plan.transitionStart % bar) < 0.01 || abs((plan.transitionStart % bar) - bar) < 0.01,
        )
        assertEquals(outgoingOverlap, plan.transitionEnd - plan.transitionStart, 1e-9)
        assertEquals(1.0, plan.stretchRatio, 1e-9)
    }

    @Test
    fun `the incoming arrangement arrives two bars before the blend finishes`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.5),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        val bar = (60 / 126.0) * 4
        assertTrue(abs(plan.incomingDropTime - 20.5) <= bar / 2 + 0.01)
        assertEquals(plan.incomingHandoffTime, plan.incomingCueTime + plan.overlapSeconds, 1e-9)
        assertEquals(8 * (60 / 126.0), plan.incomingHandoffTime - plan.incomingDropTime, 1e-9)
        assertTrue(plan.incomingCueTime >= 0)
        assertEquals(plan.incomingHandoffTime, plan.incomingResumeTime, 1e-9)
        // One continuous equal-power fade rather than a bed followed by a tail.
        assertEquals(0.5, plan.handoffFraction, 1e-9)
        assertEquals(0.5, plan.bedPosition, 1e-9)
    }

    @Test
    fun `caps the fade when the incoming intro is very long`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 90.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertTrue("overlap ${plan.overlapSeconds}", plan.overlapSeconds <= 16.001)
        // A long intro is not an invitation to play all of it; the drop stays put and the fade
        // starts later.
        assertEquals(16, plan.fadeBeats)
        assertEquals(plan.incomingHandoffTime, plan.incomingCueTime + plan.overlapSeconds, 1e-9)
    }

    @Test
    fun `slow tempos stay within the overlap ceiling`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 80.0, duration = 300.0, mixOutTime = 280.0),
                nextAnalysis = analysisFor(bpm = 80.0, duration = 300.0, mixInTime = 60.0),
                duration = 300.0,
                nextDuration = 300.0,
            ),
        )

        assertTrue("overlap ${plan.overlapSeconds}", plan.overlapSeconds <= 16.001)
        assertEquals("the fade stays quantized to whole bars", 0, plan.fadeBeats % 4)
    }

    @Test
    fun `a short intro shortens the fade instead of refusing the pairing`() {
        // Refusing here is what made this shape fail the first time it was tried: a track that
        // starts singing early lost beat-matching altogether.
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 3.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertTrue("fade ${plan.fadeBeats} beats should fit a 3s intro", plan.fadeBeats <= 8)
        assertTrue("the fade bottoms out at one bar rather than vanishing", plan.fadeBeats >= 4)
        assertEquals(plan.incomingHandoffTime, plan.incomingCueTime + plan.overlapSeconds, 1e-9)
    }

    @Test
    fun `shortens the fade rather than opening on lead-in silence`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0)
                    .copy(audibleStartTime = 14.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertTrue(
            "cue ${plan.incomingCueTime} must not precede the audible start",
            plan.incomingCueTime >= 14 - 1e-9,
        )
        assertEquals(plan.incomingHandoffTime, plan.incomingCueTime + plan.overlapSeconds, 1e-9)
    }

    @Test
    fun `never overruns the drop when the intro is shorter than the one-bar floor`() {
        // A cold open: the track makes sound barely a beat before its drop, so the MIN_FADE_BEATS
        // floor asks for four beats the intro cannot cover.
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0)
                    .copy(audibleStartTime = 18.4),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertTrue(
            "cue ${plan.incomingCueTime} must not precede the audible start",
            plan.incomingCueTime >= 18.4 - 1e-9,
        )
        assertEquals(plan.incomingHandoffTime, plan.incomingCueTime + plan.overlapSeconds, 1e-9)
        assertTrue("expected the floor to yield to the intro, got ${plan.beats}", plan.beats < 4)
        assertEquals(plan.beats, plan.fadeBeats)
    }

    @Test
    fun `prefers an analyzed drop over the plain mix-in time`() {
        val analysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 12.0).copy(
            mixInCandidates = listOf(
                MixCandidate(45.0, 0.4, "phrase"),
                MixCandidate(30.476, 0.2, "main_drop"),
            ),
        )
        val point = incomingMixInPoint(analysis)
        assertTrue("expected drop-anchored mix-in, got $point", abs((point ?: 0.0) - 30.476) < 1)
    }

    @Test
    fun `octave-doubles a half-time incoming tempo onto the shared grid`() {
        assertEquals(126.0, alignTempoOctave(126.0, 63.0), 1e-9)
        assertEquals(126.0, alignTempoOctave(126.0, 252.0), 1e-9)
        assertEquals(98.0, alignTempoOctave(100.0, 98.0), 1e-9)

        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 63.0, duration = 200.0, mixInTime = 20.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )
        assertEquals(126.0, plan.incomingBpm, 1e-9)
        assertEquals(1.0, plan.stretchRatio, 1e-9)
    }

    @Test
    fun `the low end hands over late in the fade`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )
        // Bass swap occurs at BASS_SWAP_FRACTION (0.4) of the overlap.
        assertTrue(plan.bassSwapFraction > 0)
        assertTrue(plan.bassSwapFraction <= plan.handoffFraction)
    }

    @Test
    fun `a long overlap caps the bass hold in absolute seconds`() {
        // Not in the desktop suite, but it pins the behaviour `bassSwapFractionFor` exists for and
        // that the mobile render site used to miss by passing a fixed 0.7: past 15s of
        // overlap the fraction has to shrink, or the outgoing bass sits under a track that has
        // already taken over.
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 80.0, duration = 300.0, mixOutTime = 280.0),
                nextAnalysis = analysisFor(bpm = 80.0, duration = 300.0, mixInTime = 60.0),
                duration = 300.0,
                nextDuration = 300.0,
            ),
        )
        assertTrue("overlap ${plan.overlapSeconds} should exceed the 6s hold", plan.overlapSeconds > 6)
        assertEquals(4.8, plan.bassSwapFraction * plan.overlapSeconds, 1e-9)
    }

    @Test
    fun `an outro of real music is mixed over rather than skipped`() {
        // No silence cliff: the analyzer's mix-out marker sits 20s before content end with music
        // still playing there, so the overlap moves to the end.
        val analysis = analysisFor(bpm = 126.0, duration = 240.0, mixOutTime = 220.0)
        val plan = planned(
            planWsolaTransition(
                analysis = analysis.copy(energyCurve = energyCurveFor(240.0)),
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertEquals("content_end", plan.mixOutType)
        assertTrue("transition ended at ${plan.transitionEnd}", plan.transitionEnd > 220)
    }

    @Test
    fun `both tracks singing through the fade shortens it to one bar`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0, mixOutTime = 220.0)
                    .withVocalMask { true },
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0)
                    .withVocalMask { true },
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertTrue(plan.vocalClash)
        assertEquals(4, plan.fadeBeats)
    }

    @Test
    fun `a clash confined to part of the window trims back a bar rather than jumping to the floor`() {
        // The incoming track sings throughout its intro (always vocal-active), so it is the
        // outgoing side that decides whether each candidate window clashes. The outgoing track
        // sings across [204, 215) -- squarely inside the 16-beat window ending at the mix-out
        // anchor (220s, at 60 BPM 1s/beat) but mostly outside the tail-anchored 12-beat window,
        // which starts at 208. The 16-beat check must clash; the 12-beat one must not. The old code
        // checked only the full candidate and, on any clash at all, fell straight to the one-bar
        // floor -- discarding three-quarters of a fade that a 12-beat window would have rendered
        // clash-free. That is what made transitions too short.
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 60.0, duration = 240.0, mixOutTime = 220.0)
                    .withVocalMask { it >= 204 && it < 215 },
                nextAnalysis = analysisFor(bpm = 60.0, duration = 200.0, mixInTime = 20.0)
                    .withVocalMask { true },
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertEquals(
            "expected the fade trimmed to 12 beats, got ${plan.fadeBeats}",
            12,
            plan.fadeBeats,
        )
        assertEquals(
            "the 12-beat window that was actually used must not itself be a clash",
            false,
            plan.vocalClash,
        )
    }

    @Test
    fun `instrumental pairings keep the full fade`() {
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0, mixOutTime = 220.0)
                    .withVocalMask { false },
                nextAnalysis = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0)
                    .withVocalMask { false },
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertEquals(16, plan.fadeBeats)
        assertEquals(false, plan.vocalClash)
    }

    @Test
    fun `fades out during the end of outgoing vocals into an instrumental intro without clashing`() {
        // Outgoing vocals conclude with a final phrase in [210, 216) with mix-out at 220s.
        // The 16-beat fade (at 60 BPM = 16s) runs from 204 to 220, touching the vocal tail in [210, 216).
        // With an instrumental incoming intro, this should be planned without false clash.
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 60.0, duration = 240.0, mixOutTime = 220.0)
                    .withVocalMask { it in 210.0..216.0 },
                nextAnalysis = analysisFor(bpm = 60.0, duration = 200.0, mixInTime = 20.0)
                    .withVocalMask { false },
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertEquals(16, plan.fadeBeats)
        assertEquals(false, plan.vocalClash)
    }

    @Test
    fun `trims fade when outgoing vocals extend too deep into the candidate window`() {
        // Outgoing vocals sing all the way across [200, 220).
        // Since fading across 16 beats of full vocals would overdo it, it trims to 8 beats (end of vocals).
        val plan = planned(
            planWsolaTransition(
                analysis = analysisFor(bpm = 60.0, duration = 240.0, mixOutTime = 220.0)
                    .withVocalMask { it in 200.0..220.0 },
                nextAnalysis = analysisFor(bpm = 60.0, duration = 200.0, mixInTime = 20.0)
                    .withVocalMask { false },
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertEquals(8, plan.fadeBeats)
        assertEquals(false, plan.vocalClash)
    }

    @Test
    fun `an untrusted beat grid cannot authorize a beat-matched render`() {
        val incoming = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0)
        val reason = refusal(
            planWsolaTransition(
                analysis = analysisFor(bpm = 126.0, duration = 240.0, mixOutTime = 220.0),
                nextAnalysis = incoming.copy(beatConfidence = 0.3),
                duration = 240.0,
                nextDuration = 200.0,
            ),
        )

        assertEquals("beat-confidence", reason)
    }

    @Test
    fun `refuses pairings that cannot be rendered transparently`() {
        val base = analysisFor(bpm = 126.0, duration = 240.0)
        val incoming = analysisFor(bpm = 126.0, duration = 200.0, mixInTime = 20.0)

        assertEquals(
            "outgoing-tempo",
            refusal(planWsolaTransition(base.copy(bpm = 0.0), incoming, 240.0, 200.0)),
        )
        assertEquals(
            "tempo-distance",
            refusal(
                planWsolaTransition(
                    base,
                    analysisFor(bpm = 100.0, duration = 200.0, mixInTime = 20.0),
                    240.0,
                    200.0,
                ),
            ),
        )
        assertEquals(
            "incoming-too-short",
            refusal(
                planWsolaTransition(
                    base,
                    analysisFor(bpm = 126.0, duration = 24.0, mixInTime = 20.0),
                    240.0,
                    24.0,
                ),
            ),
        )
        assertEquals(
            "outgoing-too-short",
            refusal(
                planWsolaTransition(analysisFor(bpm = 126.0, duration = 12.0), incoming, 12.0, 200.0),
            ),
        )
        assertEquals(
            "incoming-mix-in",
            refusal(
                planWsolaTransition(
                    base,
                    incoming.copy(mixInTime = 0.0, mixInCandidates = emptyList()),
                    240.0,
                    200.0,
                ),
            ),
        )
    }
}
