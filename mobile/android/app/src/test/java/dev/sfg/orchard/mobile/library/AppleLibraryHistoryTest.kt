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

package dev.sfg.orchard.mobile.library

import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLibraryHistoryTest {
    private fun song(id: String) = Track(id, id, "Artist")

    @Test
    fun keepsOnlyAppleSongsAndPutsOrchardPlaysFirst() {
        val own = LibrarySnapshot(
            recentlyPlayed = listOf(song("am.songs.1"), song("dQw4w9WgXcQ"), song("am.songs.2")),
            playCounts = mapOf("am.songs.1" to 3),
        )
        val apple = LibrarySnapshot(recentlyPlayed = listOf(song("am.songs.2"), song("am.songs.3")))
        val merged = apple.withHistory(own)
        assertEquals(listOf("am.songs.1", "am.songs.2", "am.songs.3"), merged.recentlyPlayed.map { it.id })
        assertEquals(own.playCounts, merged.playCounts)
    }
}
