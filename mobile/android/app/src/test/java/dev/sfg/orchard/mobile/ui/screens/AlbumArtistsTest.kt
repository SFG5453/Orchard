/* Copyright (C) 2026 SFG545. Licensed under the GNU AGPL v3 or later. */

package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumArtistsTest {
    @Test fun compilationCollectsDistinctTrackCredits() {
        val first = Artist("am.artists.1", "First")
        val second = Artist("am.artists.2", "Second")
        val detail = BrowseDetail("album", CatalogKind.ALBUM, "Compilation", artist = "Various Artists",
            artists = listOf(Artist("am.artists.various", "Various Artists")),
            tracks = listOf(
                Track("1", "One", "First", artists = listOf(first, second)),
                Track("2", "Two", "First", artists = listOf(first)),
                Track("3", "Three", "Second", artistId = second.id),
            ))
        assertEquals(listOf(first, second), detail.albumArtists())
    }

    @Test fun ordinaryAlbumUsesAlbumCredit() {
        val artist = Artist("am.artists.1", "Headliner")
        val detail = BrowseDetail("album", CatalogKind.ALBUM, "Album", artist = artist.name,
            artists = listOf(artist), tracks = listOf(Track("1", "One", "Guest", artistId = "guest")))
        assertEquals(listOf(artist), detail.albumArtists())
    }
}
