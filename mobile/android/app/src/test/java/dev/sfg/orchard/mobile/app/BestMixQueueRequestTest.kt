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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.app

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BestMixQueueRequestTest {

    private fun track(id: String, title: String = id): Track = Track(
        id = id,
        title = title,
        artist = "Artist",
        durationMs = 180_000,
    )

    private fun snapshot(
        current: Track,
        upcoming: List<Track>,
    ): PlaybackSnapshot = PlaybackSnapshot(
        currentTrack = current,
        queue = listOf(current) + upcoming,
        currentIndex = 0,
    )

    @Test
    fun completedAnalysisStillMatchesTheQueueItStartedFrom() {
        val current = track("current")
        val request = BestMixQueueRequest.capture(snapshot(current, listOf(track("a"), track("b"))))

        val latest = snapshot(current.copy(title = "Updated metadata"), listOf(track("a"), track("b")))

        assertTrue(request.matches(latest))
    }

    @Test
    fun playbackAdvancingDuringAnalysisInvalidatesTheRequest() {
        val current = track("current")
        val first = track("first")
        val second = track("second")
        val request = BestMixQueueRequest.capture(snapshot(current, listOf(first, second)))
        val advanced = PlaybackSnapshot(
            currentTrack = first,
            queue = listOf(current, first, second),
            currentIndex = 1,
        )

        assertFalse(request.matches(advanced))
    }

    @Test
    fun queueEditsDuringAnalysisInvalidateTheRequest() {
        val current = track("current")
        val request = BestMixQueueRequest.capture(snapshot(current, listOf(track("a"), track("b"))))
        val appended = snapshot(current, listOf(track("a"), track("b"), track("refill")))

        assertFalse(request.matches(appended))
    }
}
