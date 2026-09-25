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

package dev.sfg.orchard.mobile.app

import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ReverseConnectTransferTest {
    private fun track(id: String, title: String = id) = Track(id = id, title = title, artist = "Artist")

    @Test
    fun currentTrackIsPrependedWhenDesktopQueueContainsOnlyUpcomingTracks() {
        val current = track("current", "Current")
        val plan = desktopTransferPlan(
            track = current,
            queue = listOf(track("next-1"), track("next-2")),
            repeatMode = "queue",
        )

        assertEquals(listOf("current", "next-1", "next-2"), plan.tracks.map(Track::id))
        assertEquals(0, plan.startIndex)
        assertEquals(RepeatMode.ALL, plan.repeatMode)
    }

    @Test
    fun fullQueuePayloadDoesNotDuplicateCurrentTrack() {
        val current = track("current")
        val plan = desktopTransferPlan(
            track = current,
            queue = listOf(current, track("next"), current),
            repeatMode = "one",
        )

        assertEquals(listOf("current", "next"), plan.tracks.map(Track::id))
        assertEquals(0, plan.startIndex)
        assertEquals(RepeatMode.ONE, plan.repeatMode)
    }
}
