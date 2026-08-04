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
import org.junit.Test

class PlaybackModelsTest {
    private val tracks = listOf(
        Track("a", "A", "Artist"),
        Track("b", "B", "Artist"),
        Track("c", "C", "Artist"),
    )

    @Test
    fun snapshotSeparatesHistoryAndUpcomingAroundCurrent() {
        val snapshot = PlaybackSnapshot(queue = tracks, currentIndex = 1, currentTrack = tracks[1])

        assertEquals(listOf(tracks[0]), snapshot.history)
        assertEquals(listOf(tracks[2]), snapshot.upcoming)
    }

    @Test
    fun emptySelectionTreatsWholeQueueAsUpcoming() {
        val snapshot = PlaybackSnapshot(queue = tracks, currentIndex = -1)

        assertEquals(emptyList<Track>(), snapshot.history)
        assertEquals(tracks, snapshot.upcoming)
    }
}
