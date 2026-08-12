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

import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistDetailStateTest {
    @Test
    fun `confirmed removal updates the visible playlist and preserves duplicate rows`() {
        val duplicate = Track("song-1", "Song", "Artist")
        val detail = BrowseDetail(
            id = "VLPL123",
            kind = CatalogKind.PLAYLIST,
            title = "Playlist",
            tracks = listOf(duplicate, Track("song-2", "Other", "Artist"), duplicate),
        )

        val updated = detail.withPlaylistTrackRemoved("song-1")

        assertEquals(listOf("song-2", "song-1"), updated.tracks.map(Track::id))
    }

    @Test
    fun `missing track leaves playlist state unchanged`() {
        val detail = BrowseDetail("VLPL123", CatalogKind.PLAYLIST, "Playlist")

        assertSame(detail, detail.withPlaylistTrackRemoved("missing"))
    }
}
