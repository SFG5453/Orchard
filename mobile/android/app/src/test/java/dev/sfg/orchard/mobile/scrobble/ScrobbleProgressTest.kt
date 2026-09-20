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

package dev.sfg.orchard.mobile.scrobble

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleProgressTest {
    @Test
    fun `uses half-track threshold for ordinary songs`() {
        assertTrue(shouldScrobble(durationMs = 180_000, playedMs = 90_000))
    }

    @Test
    fun `caps long tracks at four minutes`() {
        assertTrue(shouldScrobble(durationMs = 900_000, playedMs = 240_000))
    }

    @Test
    fun `does not count a seek as listening`() {
        val tracker = ScrobbleProgress()
        val initial = snapshot(positionMs = 1_000)
        assertEquals(1, tracker.update(initial, nowMs = 1_000).size)
        assertTrue(tracker.update(snapshot(positionMs = 100_000), nowMs = 2_000).isEmpty())
    }

    @Test
    fun `emits one completion after audible progress`() {
        val tracker = ScrobbleProgress()
        tracker.update(snapshot(positionMs = 0), nowMs = 1_000)
        val completed = tracker.update(snapshot(positionMs = 60_000), nowMs = 61_000)
        assertTrue(completed.single() is ScrobbleProgressEvent.Completed)
        assertTrue(tracker.update(snapshot(positionMs = 90_000), nowMs = 91_000).isEmpty())
    }

    private fun snapshot(positionMs: Long) = PlaybackSnapshot(
        currentTrack = Track("track", "Song", "Artist", durationMs = 120_000),
        positionMs = positionMs,
        durationMs = 120_000,
        isPlaying = true,
    )
}
