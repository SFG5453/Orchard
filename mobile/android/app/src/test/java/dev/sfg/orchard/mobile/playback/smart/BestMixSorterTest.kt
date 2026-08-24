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
import org.junit.Assert.assertEquals
import org.junit.Test

class BestMixSorterTest {

    private fun createTrack(id: String, title: String): Track = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = 210000L,
        artworkUrl = "",
    )

    private fun createFeatures(
        bpm: Double,
        key: String,
        beatConfidence: Double = 0.9,
        keyConfidence: Double = 0.9,
        vocalProbability: Double = 0.2,
        vocalAt: (Double) -> Double = { vocalProbability },
    ): TrackFeatures.Features = TrackFeatures.Features(
        duration = 210.0,
        bpm = bpm,
        beatInterval = if (bpm > 0) 60.0 / bpm else 0.0,
        firstBeat = 0.0,
        beatConfidence = beatConfidence,
        key = key,
        keyConfidence = keyConfidence,
        audibleStartTime = 0.0,
        pickupTime = 0.0,
        introEndTime = 15.0,
        outroStartTime = 195.0,
        contentEndTime = 210.0,
        mixInTime = 10.0,
        mixOutTime = 200.0,
        vocalProbability = vocalProbability,
        downbeats = if (bpm > 0) {
            generateSequence(0.0) { it + (60.0 / bpm) * 4 }
                .takeWhile { it <= 210.0 }
                .toList()
        } else {
            emptyList()
        },
        phraseBoundaries = listOf(0.0, 30.0, 60.0),
        vocalActivityMask = (0..210).map { vocalAt(it.toDouble()) },
        energyCurve = (0..210).map { EnergySample(it.toDouble(), 0.7) },
        lowEnergyCurve = (0..210).map { EnergySample(it.toDouble(), 0.4) },
        mixInCandidates = listOf(MixCandidate(10.0, 0.9, "main_drop")),
        mixOutCandidates = listOf(MixCandidate(200.0, 0.9, "outro_start")),
    )

    @Test
    fun singleOrEmptyListReturnsSame() {
        assertEquals(emptyList<Track>(), BestMixSorter.sort(emptyList(), emptyMap()))

        val single = listOf(createTrack("t1", "Track 1"))
        assertEquals(single, BestMixSorter.sort(single, emptyMap()))
    }

    @Test
    fun sortsHarmonicallyAndByTempo() {
        val t1 = createTrack("t1", "C Major Fast")
        val t2 = createTrack("t2", "G Major Fast")
        val t3 = createTrack("t3", "F# Major Slow")

        val features = mapOf(
            "t1" to createFeatures(128.0, "C Major"),
            "t2" to createFeatures(126.0, "G Major"), // Perfect 5th (Circle of 5ths dist = 1), near tempo
            "t3" to createFeatures(80.0, "F# Major"),  // Tritone distance = 6, distant tempo
        )

        val input = listOf(t1, t3, t2)
        val sorted = BestMixSorter.sort(input, features)

        // Starting with t1 (first track), t2 should follow t1 because G Major is adjacent to C Major (cost 1 vs 6)
        assertEquals(listOf(t1, t2, t3), sorted)
    }

    @Test
    fun smartCrossfadeVocalSafetyOutranksPerfectBpmAndKey() {
        val opener = createTrack("opener", "Vocal Outro")
        val unsafe = createTrack("unsafe", "Vocal Intro")
        val safe = createTrack("safe", "Instrumental Intro")
        val features = mapOf(
            opener.id to createFeatures(
                bpm = 120.0,
                key = "C Major",
                vocalProbability = 0.92,
                vocalAt = { time -> if (time >= 150.0) 0.92 else 0.05 },
            ),
            unsafe.id to createFeatures(
                bpm = 120.0,
                key = "C Major",
                vocalProbability = 0.9,
                vocalAt = { time -> if (time <= 60.0) 0.9 else 0.05 },
            ),
            safe.id to createFeatures(bpm = 122.0, key = "G Major"),
        )

        val sorted = BestMixSorter.sort(listOf(opener, unsafe, safe), features)

        assertEquals(listOf(opener, safe, unsafe), sorted)
    }

    @Test
    fun currentTrackAnalysisAnchorsTheFirstUpcomingChoice() {
        val rough = createTrack("rough", "Rough Transition")
        val smooth = createTrack("smooth", "Smooth Transition")
        val current = createFeatures(bpm = 120.0, key = "C Major")
        val features = mapOf(
            rough.id to createFeatures(bpm = 145.0, key = "F# Major"),
            smooth.id to createFeatures(bpm = 122.0, key = "G Major"),
        )

        val sorted = BestMixSorter.sort(
            tracks = listOf(rough, smooth),
            featuresMap = features,
            initialFeatures = current,
        )

        assertEquals(listOf(smooth, rough), sorted)
    }

    @Test
    fun harmonicGateMatchesThePlaybackFallback() {
        val incompatible = createTrack("incompatible", "Exact Tempo Tritone")
        val compatible = createTrack("compatible", "Nearby Tempo Fifth")
        val current = createFeatures(bpm = 120.0, key = "C Major")
        val features = mapOf(
            incompatible.id to createFeatures(bpm = 120.0, key = "F# Major"),
            compatible.id to createFeatures(bpm = 126.0, key = "G Major"),
        )

        val sorted = BestMixSorter.sort(
            tracks = listOf(incompatible, compatible),
            featuresMap = features,
            initialFeatures = current,
        )

        assertEquals(listOf(compatible, incompatible), sorted)
    }

    @Test
    fun djAssistedFallbackOutranksAPlainCrossfade() {
        val plain = createTrack("plain", "Exact But Untrusted")
        val assisted = createTrack("assisted", "Trusted Incoming Grid")
        val current = createFeatures(
            bpm = 120.0,
            key = "C Major",
            beatConfidence = 0.1,
        )
        val features = mapOf(
            plain.id to createFeatures(
                bpm = 120.0,
                key = "C Major",
                beatConfidence = 0.1,
            ),
            assisted.id to createFeatures(bpm = 126.0, key = "G Major"),
        )

        val sorted = BestMixSorter.sort(
            tracks = listOf(plain, assisted),
            featuresMap = features,
            initialFeatures = current,
        )

        assertEquals(listOf(assisted, plain), sorted)
    }

    @Test
    fun keyOnlyCurrentAnalysisStillAnchorsUpcomingTracks() {
        val rough = createTrack("rough", "Tritone")
        val smooth = createTrack("smooth", "Fifth")
        val current = createFeatures(bpm = 0.0, key = "C Major")
        val features = mapOf(
            rough.id to createFeatures(bpm = 120.0, key = "F# Major"),
            smooth.id to createFeatures(bpm = 120.0, key = "G Major"),
        )

        val sorted = BestMixSorter.sort(
            tracks = listOf(rough, smooth),
            featuresMap = features,
            initialFeatures = current,
        )

        assertEquals(listOf(smooth, rough), sorted)
    }

    @Test
    fun leavesUnanalyzedTracksInPlace() {
        val t1 = createTrack("t1", "Track 1")
        val t2 = createTrack("t2", "Track 2 (Unanalyzed)")
        val t3 = createTrack("t3", "Track 3")
        val t4 = createTrack("t4", "Track 4")

        val features = mapOf(
            "t1" to createFeatures(120.0, "A Minor"),
            "t3" to createFeatures(122.0, "D Minor"),
            "t4" to createFeatures(120.0, "E Minor"),
        )

        val input = listOf(t1, t2, t3, t4)
        val sorted = BestMixSorter.sort(input, features)

        assertEquals(4, sorted.size)
        // t2 was unanalyzed, so it stays at index 1
        assertEquals("t2", sorted[1].id)
    }
}
