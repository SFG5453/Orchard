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

package dev.sfg.orchard.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackClockTest {
    @Test
    fun projectsPlayingClockForwardFromSample() {
        val clock = PlaybackClock(positionMs = 10_000, playing = true, sampledAtMs = 1_000)
        assertEquals(10_300, clock.projectedTo(nowMs = 1_300, durationMs = 200_000).positionMs)
    }

    @Test
    fun pausedClockDoesNotMove() {
        val clock = PlaybackClock(positionMs = 10_000, playing = false, sampledAtMs = 1_000)
        assertSame(clock, clock.projectedTo(nowMs = 5_000, durationMs = 200_000))
    }

    @Test
    fun projectionStopsAtDurationAndAtTheStallCap() {
        val nearEnd = PlaybackClock(positionMs = 199_900, playing = true, sampledAtMs = 1_000)
        assertEquals(200_000, nearEnd.projectedTo(nowMs = 1_500, durationMs = 200_000).positionMs)

        // A publisher that stopped ticking must not let the playhead run on indefinitely.
        val stalled = PlaybackClock(positionMs = 10_000, playing = true, sampledAtMs = 1_000)
        assertEquals(11_000, stalled.projectedTo(nowMs = 60_000, durationMs = 200_000).positionMs)
    }

    @Test
    fun renderedMixPositionAdvancesWithTheClock() {
        val clock = PlaybackClock(positionMs = 5_000, renderedMixPositionMs = 400, playing = true, sampledAtMs = 1_000)
        assertEquals(600L, clock.projectedTo(nowMs = 1_200, durationMs = 0).renderedMixPositionMs)
    }

    @Test
    fun strippingAndRestoringTheClockRoundTrips() {
        val snapshot = PlaybackSnapshot(positionMs = 42_000, bufferedPositionMs = 60_000, isPlaying = true)
        val stripped = snapshot.withoutClock()
        assertEquals(PlaybackSnapshot(isPlaying = true), stripped)
        assertEquals(snapshot, stripped.withClock(snapshot.clock(sampledAtMs = 0)))
    }
}
