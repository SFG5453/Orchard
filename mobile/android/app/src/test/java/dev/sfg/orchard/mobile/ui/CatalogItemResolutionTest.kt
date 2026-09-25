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

package dev.sfg.orchard.mobile.ui

import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogItemResolutionTest {

    @Test
    fun `song catalog item delegates to play action instead of open detail`() {
        val songItem = CatalogItem.Song(Track(id = "song-123", title = "Quick Song", artist = "Artist"))
        var playedTrackId: String? = null
        var openedDetailId: String? = null

        val handleItem: (CatalogItem) -> Unit = { item ->
            when (item) {
                is CatalogItem.Song -> playedTrackId = item.track.id
                else -> openedDetailId = item.stableId
            }
        }

        handleItem(songItem)

        assertEquals("song-123", playedTrackId)
        assertEquals(null, openedDetailId)
    }

    @Test
    fun `album collection and artist catalog items delegate to open detail`() {
        val recordItem = CatalogItem.Record(Album(id = "MPREalbum-1", title = "Album Title", artist = "Artist"))
        val collectionItem = CatalogItem.Collection(Playlist(id = "VLplaylist-1", title = "Playlist Title"))
        val artistItem = CatalogItem.Performer(Artist(id = "UCartist-1", name = "Artist Name"))

        val openedDetails = mutableListOf<String>()
        val handleItem: (CatalogItem) -> Unit = { item ->
            when (item) {
                is CatalogItem.Song -> Unit
                else -> openedDetails.add(item.stableId)
            }
        }

        handleItem(recordItem)
        handleItem(collectionItem)
        handleItem(artistItem)

        assertEquals(listOf("MPREalbum-1", "VLplaylist-1", "UCartist-1"), openedDetails)
    }
}
