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

class TransitionPlannerTest {

    private fun track(
        id: String = "a",
        title: String = "Song",
        artist: String = "Artist",
        album: String = "",
        albumId: String = "",
        seconds: Double = 240.0,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = (seconds * 1000).toLong(),
    )

    private fun grid(bpm: Double, upTo: Double, confidence: Double = 0.9): TrackAnalysis {
        val beat = 60 / bpm
        val downbeats = generateSequence(0.0) { it + beat * 4 }.takeWhile { it <= upTo }.toList()
        return TrackAnalysis(
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = confidence,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 4 == 0 },
            duration = upTo,
        )
    }

    @Test
    fun `standard mode fades over the requested window at the end of the track`() {
        val plan = planTransition(
            currentTrack = track(seconds = 200.0),
            nextTrack = track(id = "b"),
            currentTime = 195.0,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.STANDARD,
        )
        assertTrue(plan.shouldStart)
        assertEquals(194.0, plan.transitionStart, 1e-9)
        assertEquals(200.0, plan.transitionEnd, 1e-9)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals("standard", plan.reason)
    }

    @Test
    fun `standard mode reports the window before the playhead reaches it`() {
        val plan = planTransition(
            currentTrack = track(seconds = 200.0),
            nextTrack = track(id = "b"),
            currentTime = 10.0,
            mode = CrossfadeMode.STANDARD,
        )
        assertFalse(plan.shouldStart)
        assertTrue(plan.markerVisible)
        assertEquals("before-standard-window", plan.reason)
    }

    @Test
    fun `a track with no duration is blocked outright`() {
        val plan = planTransition(currentTrack = null, mode = CrossfadeMode.SMART)
        assertTrue(plan.blocked)
        assertEquals("no-duration", plan.reason)
    }

    @Test
    fun `smart mode refuses a track too short to mix`() {
        val plan = planTransition(
            currentTrack = track(seconds = 30.0),
            nextTrack = track(id = "b"),
            currentTime = 20.0,
            mode = CrossfadeMode.SMART,
        )
        assertTrue(plan.blocked)
        assertEquals("short-duration-guard", plan.reason)
    }

    @Test
    fun `smart mode refuses speech and live material`() {
        val plan = planTransition(
            analysis = grid(126.0, 240.0),
            nextAnalysis = grid(126.0, 240.0),
            currentTrack = track(title = "Episode 12: The Interview"),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertTrue(plan.blocked)
        assertEquals("blocked-speech-or-live", plan.reason)
    }

    @Test
    fun `album siblings played in order hand off gaplessly`() {
        val plan = planTransition(
            analysis = grid(126.0, 240.0),
            nextAnalysis = grid(126.0, 240.0),
            currentTrack = track(id = "a", albumId = "album-1"),
            nextTrack = track(id = "b", albumId = "album-1"),
            currentTime = 239.8,
            mode = CrossfadeMode.SMART,
            albumSequential = true,
        )
        assertEquals(TransitionStyle.GAPLESS, plan.transitionStyle)
        assertTrue(plan.shouldStart)
        assertEquals(0.12, plan.fadeSeconds, 1e-9)
    }

    @Test
    fun `the same two album siblings in a shuffle get mixed instead`() {
        val plan = planTransition(
            analysis = grid(126.0, 240.0),
            nextAnalysis = grid(126.0, 240.0),
            currentTrack = track(id = "a", albumId = "album-1"),
            nextTrack = track(id = "b", albumId = "album-1"),
            currentTime = 239.8,
            mode = CrossfadeMode.SMART,
            albumSequential = false,
        )
        assertTrue(plan.transitionStyle != TransitionStyle.GAPLESS)
    }

    @Test
    fun `an untrusted grid falls to a plain crossfade at the analyzed anchor`() {
        val plan = planTransition(
            analysis = TrackAnalysis(bpm = 120.0, beatConfidence = 0.0, duration = 240.0),
            nextAnalysis = TrackAnalysis(bpm = 120.0, beatConfidence = 0.0, duration = 240.0),
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(listOf("beat-confidence"), plan.policyReasons)
        assertEquals(240.0, plan.transitionEnd, 1e-9)
        assertEquals(234.0, plan.transitionStart, 1e-9)
        assertEquals("before-plain-crossfade-window", plan.reason)
    }

    @Test
    fun `a plain crossfade cues past the incoming track's lead-in silence`() {
        val plan = planTransition(
            analysis = TrackAnalysis(bpm = 0.0, duration = 240.0),
            nextAnalysis = TrackAnalysis(audibleStartTime = 1.4, duration = 240.0),
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 238.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(1.4, plan.incomingCueTime, 1e-9)
    }

    @Test
    fun `matching tempi and keys blend on the beat with a bass swap`() {
        val outgoing = grid(126.0, 240.0).copy(key = "A minor", keyConfidence = 0.8)
        val incoming = grid(126.0, 240.0).copy(
            key = "A minor",
            keyConfidence = 0.8,
            mixInCandidates = listOf(MixCandidate(time = 16.0, score = 0.7, type = "main_drop")),
            audibleStartTime = 0.0,
        )
        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertTrue(plan.bassSwap)
        assertTrue(plan.fadeSeconds > 0)
        assertTrue(plan.transitionStart < plan.transitionEnd)
    }

    @Test
    fun `a beat-matched plan carries the octave-aligned tempi the renderer needs`() {
        // The native renderer gates on `outgoing.bpm / incoming.bpm` without aligning octaves, so a
        // half-time pairing handed the raw analysed tempi is refused outright -- after both tracks
        // have been decoded for it. The policy aligns octaves before judging, which is why this
        // reaches the renderer at all; the plan has to carry the same counting the policy did.
        val outgoing = grid(126.0, 240.0).copy(key = "A minor", keyConfidence = 0.8)
        val incoming = grid(63.0, 240.0).copy(
            key = "A minor",
            keyConfidence = 0.8,
            mixInCandidates = listOf(MixCandidate(time = 16.0, score = 0.7, type = "main_drop")),
            audibleStartTime = 0.0,
        )
        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertEquals(126.0, plan.outgoingBpm, 1e-9)
        assertEquals("the incoming 63 BPM must be counted at 126", 126.0, plan.incomingBpm, 1e-9)
        assertTrue(
            "the renderer's own transparency gate must pass on what the plan carries",
            abs(plan.outgoingBpm / plan.incomingBpm - 1) <= MAX_STRETCH_DEVIATION,
        )
    }

    @Test
    fun `the overlap is bounded in beats so a faster track does not get a longer mix`() {
        // Sixteen beats at 140 BPM is under seven seconds; the seconds cap must not stretch it.
        val plan = planTransition(
            analysis = grid(140.0, 240.0),
            nextAnalysis = grid(140.0, 240.0),
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )
        val sixteenBeats = (16 * 60) / 140.0
        assertTrue(
            "overlap ${plan.fadeSeconds} exceeded sixteen beats ($sixteenBeats)",
            plan.fadeSeconds <= sixteenBeats + 1e-6,
        )
    }

    @Test
    fun `an outro marker does not become the exit anchor`() {
        // A 48s outro marker would discard far more than the budget allows, so the transition
        // still ends where the content does.
        val analysis = grid(126.0, 240.0).copy(
            contentEndTime = 240.0,
            outroStartTime = 192.0,
            energyCurve = (0 until 240).map { EnergySample(it.toDouble(), 0.8) },
        )
        val plan = planTransition(
            analysis = analysis,
            nextAnalysis = grid(126.0, 240.0),
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals(240.0 - 8 * 60 / 126.0, plan.transitionEnd, 1e-9)
    }

    @Test
    fun `the DJ blend fallback keeps the mobile two-bar calibration`() {
        // Weak key evidence makes the conservative phrase-switch path decline this otherwise
        // excellent tempo match. The fallback is still a DJ_BLEND and must not revert to the old
        // content-end/drop alignment that was late on CN TOWER and early on Whisper My Name.
        val outgoingBpm = 126.86624778039653
        val incomingBpm = 125.07983112913352
        val incomingDrop = 15.4969
        val outgoing = grid(outgoingBpm, 242.0).copy(
            contentEndTime = 242.0,
            key = "B minor",
            keyConfidence = 0.0,
        )
        val incoming = grid(incomingBpm, 222.601).copy(
            mixInTime = incomingDrop,
            mixInCandidates = listOf(
                MixCandidate(time = incomingDrop, score = 0.8, type = "intro_drop"),
            ),
            audibleStartTime = 0.0,
            key = "B minor",
            keyConfidence = 0.0,
        )

        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = track(seconds = 242.0),
            nextTrack = track(id = "b", seconds = 0.0),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )

        assertEquals("before-smart-duration", plan.reason)
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertEquals(242.0 - 8 * 60 / outgoingBpm, plan.transitionEnd, 1e-9)
        assertEquals(incomingDrop + 8 * 60 / incomingBpm, plan.incomingHandoffTime, 1e-9)
        assertEquals(
            plan.incomingHandoffTime,
            plan.incomingCueTime + plan.fadeSeconds * plan.incomingPlaybackRate,
            1e-9,
        )
    }

    @Test
    fun `a stale analysis for another track falls back to a standard fade`() {
        val plan = planTransition(
            analysis = TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = "somebody-else",
                bpm = 126.0,
                beatConfidence = 0.9,
            ),
            nextAnalysis = grid(126.0, 240.0),
            currentTrack = track(id = "a", seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals("before-smart-analysis-fallback-window", plan.reason)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
    }

    @Test
    fun `an analysis still running falls back to a standard fade`() {
        val plan = planTransition(
            analysis = TrackAnalysis(status = "pending", bpm = 126.0, beatConfidence = 0.9),
            nextAnalysis = grid(126.0, 240.0),
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 236.0,
            mode = CrossfadeMode.SMART,
        )
        assertEquals("smart-analysis-fallback", plan.reason)
    }

    @Test
    fun `the incoming cue point prefers a ranked candidate over the scalar mix-in`() {
        val analysis = TrackAnalysis(
            bpm = 120.0,
            audibleStartTime = 0.0,
            mixInTime = 4.0,
            mixInCandidates = listOf(MixCandidate(time = 32.0, score = 0.8, type = "main_drop")),
        )
        assertEquals(32.0, incomingCuePoint(analysis), 1e-9)
    }

    @Test
    fun `the plan exposes its fade in milliseconds for the engine`() {
        val plan = planTransition(
            currentTrack = track(seconds = 200.0),
            nextTrack = track(id = "b"),
            currentTime = 195.0,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.STANDARD,
        )
        assertEquals(6000L, plan.fadeMs)
    }

    @Test
    fun `every smart plan keeps the transition inside the track`() {
        val analysis = grid(126.0, 240.0)
        for (time in 0..239 step 7) {
            val plan = planTransition(
                analysis = analysis,
                nextAnalysis = grid(124.0, 240.0),
                currentTrack = track(seconds = 240.0),
                nextTrack = track(id = "b"),
                currentTime = time.toDouble(),
                mode = CrossfadeMode.SMART,
            )
            assertNotNull(plan)
            if (plan.blocked) continue
            assertTrue("start negative at $time", plan.transitionStart >= 0)
            assertTrue("end past track at $time", plan.transitionEnd <= 240.0 + 1e-6)
            assertTrue("inverted window at $time", plan.transitionStart <= plan.transitionEnd)
            assertTrue("cue negative at $time", plan.incomingCueTime >= 0)
        }
    }

    @Test
    fun `live fallback still nudges incoming audio onto the outgoing grid`() {
        val plan = planTransition(
            analysis = grid(126.0, 240.0).copy(key = "A minor", keyConfidence = 0.8),
            nextAnalysis = grid(124.0, 240.0).copy(
                key = "A minor",
                keyConfidence = 0.8,
                mixInTime = 16.0,
                mixInCandidates = listOf(MixCandidate(16.0, 0.8, "main_drop")),
            ),
            currentTrack = track(seconds = 240.0),
            nextTrack = track(id = "b"),
            currentTime = 200.0,
            mode = CrossfadeMode.SMART,
        )

        assertEquals(126.0 / 124.0, plan.incomingPlaybackRate, 1e-4)
    }

    @Test
    fun `regression blinding lights to dont start now is never beatmatched and duration is 0 to 4s`() {
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
            currentTrack = track(id = "blinding_lights", seconds = 200.0),
            nextTrack = track(id = "dont_start_now", seconds = 183.0),
            currentTime = 170.0,
            mode = CrossfadeMode.SMART,
        )
        assertFalse(plan.blocked)
        assertTrue(plan.transitionStyle != TransitionStyle.DJ_BLEND)
        assertTrue("fade seconds must be <= 4.0s but got ${plan.fadeSeconds}", plan.fadeSeconds <= 4.0)
    }

    @Test
    fun `fixture safe instrumental blend receives 8 to 16 beat staged blend`() {
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
            vocalProbability = 0.04,
        )
        val incoming = TrackAnalysis(
            trackId = "inst_b",
            duration = 210.0,
            bpm = 125.0,
            beatInterval = 0.48,
            beatConfidence = 0.96,
            key = "C major",
            keyConfidence = 0.95,
            contentEndTime = 208.0,
            phraseBoundaries = listOf(0.0, 7.68, 15.36, 23.04),
            downbeats = listOf(0.0, 1.92, 3.84, 5.76, 7.68, 9.6, 11.52, 13.44, 15.36, 17.28, 19.2, 21.12, 23.04),
            mixInCandidates = listOf(
                MixCandidate(0.0, 0.88, "intro"),
                MixCandidate(7.68, 0.96, "drop"),
            ),
            vocalProbability = 0.03,
        )
        val plan = planTransition(
            analysis = outgoing,
            nextAnalysis = incoming,
            currentTrack = track(id = "inst_a", seconds = 210.0),
            nextTrack = track(id = "inst_b", seconds = 210.0),
            currentTime = 180.0,
            mode = CrossfadeMode.SMART,
        )
        assertFalse(plan.blocked)
        assertTrue("expected 8..16 beats, got ${plan.transitionBeats}", plan.transitionBeats in 8..16)
        assertTrue(plan.incomingCueTime <= plan.incomingHandoffTime)
    }
}
