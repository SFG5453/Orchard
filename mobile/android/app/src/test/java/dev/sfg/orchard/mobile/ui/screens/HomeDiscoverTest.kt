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

import dev.sfg.orchard.mobile.model.APPLE_PERSONAL_MIXES_SECTION
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDiscoverTest {
    private fun mix(id: String) = CatalogItem.Collection(Playlist(id, id, artworkUrl = "https://art/$id"))
    private fun album(id: String) = CatalogItem.Record(Album(id, id, "Artist", "https://art/$id"))

    private fun song(id: String) = CatalogItem.Song(Track(id, id, "Artist", artworkUrl = "https://art/$id"))
    private fun artist(id: String) = CatalogItem.Performer(Artist(id, id, "https://art/$id"))

    @Test
    fun personalMixesLeadTheCarousel() {
        val state = LoadState.Content(listOf(
            CatalogSection("apple-recommendation-1", "New Releases for You", listOf(album("am.albums.1"))),
            CatalogSection(APPLE_PERSONAL_MIXES_SECTION, "Playlists Made for You",
                listOf(mix("am.playlists.pl.pm-a"), mix("am.playlists.pl.pm-b"), mix("am.playlists.pl.pm-c"))),
        ))
        val library = LibrarySnapshot(savedPlaylists = listOf(Playlist("am.playlists.p.mine", "Mine", artworkUrl = "https://art/mine")))
        val entries = discoverEntries(state, library)
        assertEquals(
            listOf("am.playlists.pl.pm-a", "am.playlists.pl.pm-b", "am.albums.1", "am.playlists.p.mine"),
            entries.map { it.item.stableId },
        )
        assertEquals("Playlists Made for You", entries.first().caption)
    }

    @Test
    fun kindsTakeTurns() {
        val state = LoadState.Content(listOf(
            CatalogSection("lists", "Playlists", (1..6).map { mix("p$it") }),
            CatalogSection("albums", "Albums", (1..3).map { album("a$it") }),
            CatalogSection("songs", "Songs", (1..3).map { song("s$it") }),
            CatalogSection("artists", "Artists", listOf(artist("r1"))),
        ))
        val kinds = discoverEntries(state, LibrarySnapshot(), day = 3).map { it.item::class.simpleName }
        assertEquals(
            listOf("Record", "Song", "Performer", "Collection", "Record", "Song", "Collection", "Record"),
            kinds,
        )
    }
}
