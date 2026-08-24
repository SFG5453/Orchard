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
import org.junit.Assert.assertTrue
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
    ): TrackFeatures.Features = TrackFeatures.Features(
        duration = 210.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
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
        vocalProbability = 0.2,
        downbeats = listOf(0.0, 60.0 / bpm),
        phraseBoundaries = listOf(0.0, 30.0, 60.0),
        vocalActivityMask = listOf(0.0, 1.0),
        energyCurve = listOf(EnergySample(0.0, 0.5), EnergySample(10.0, 0.7)),
        lowEnergyCurve = listOf(EnergySample(0.0, 0.3), EnergySample(10.0, 0.4)),
        mixInCandidates = emptyList(),
        mixOutCandidates = emptyList(),
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
