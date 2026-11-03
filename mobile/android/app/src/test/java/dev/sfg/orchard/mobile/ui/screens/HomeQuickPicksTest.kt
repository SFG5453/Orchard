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

package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeQuickPicksTest {
    private fun track(id: String) = Track(id, id, "Artist")

    @Test
    fun recentPlaysOnlyFillTheTail() {
        val library = LibrarySnapshot(
            likedTracks = listOf(track("a"), track("b"), track("played")),
            recentlyPlayed = listOf(track("played"), track("other")),
        )
        val feed = listOf(CatalogSection("s", "Songs", listOf(CatalogItem.Song(track("c")))))
        val picks = quickPickTracks(library, feed, day = 1).map { it.id }
        assertEquals(setOf("a", "b", "c"), picks.take(3).toSet())
        assertEquals(listOf("played", "other"), picks.drop(3))
    }

    @Test
    fun orderIsStableWithinADay() {
        val library = LibrarySnapshot(likedTracks = (1..12).map { track("t$it") })
        assertEquals(quickPickTracks(library, emptyList(), 7), quickPickTracks(library, emptyList(), 7))
    }
}
