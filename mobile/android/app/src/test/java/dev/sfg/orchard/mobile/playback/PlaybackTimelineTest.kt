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

package dev.sfg.orchard.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun `clipped remainder continues at its full song position`() {
        val clock = SourcePlaybackClock(startMs = 18_000)
        assertEquals(18_000L, clock.sourcePosition(0))
        assertEquals(23_000L, clock.sourcePosition(5_000))
        assertEquals(5_000L, clock.playerPosition(23_000))
    }

    @Test fun `rendered output is mapped back onto the outgoing source grid`() {
        val clock = SourcePlaybackClock(startMs = 200_000, rate = 1.25)
        assertEquals(200_000L, clock.sourcePosition(0))
        assertEquals(207_500L, clock.sourcePosition(6_000))
        assertEquals(210_000L, clock.sourcePosition(8_000))
        assertEquals(6_000L, clock.playerPosition(207_500))
    }
}
