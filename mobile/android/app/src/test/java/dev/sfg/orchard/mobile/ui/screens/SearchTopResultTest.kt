package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTopResultTest {
    private val track = Track(id = "t1", title = "Song", artist = "Someone")
    private val album = Album(id = "a1", title = "Album", artist = "Someone")
    private val artist = Artist(id = "r1", name = "Someone")

    @Test
    fun exactArtistNameBeatsFirstSong() {
        val results = SearchResults(tracks = listOf(track), artists = listOf(artist))
        assertEquals(CatalogItem.Performer(artist), topResult(results, " someone "))
    }

    @Test
    fun firstSongWinsWithoutExactArtist() {
        val results = SearchResults(tracks = listOf(track), albums = listOf(album), artists = listOf(artist))
        assertEquals(CatalogItem.Song(track), topResult(results, "song"))
    }

    @Test
    fun fallsBackToAlbumThenNull() {
        assertEquals(CatalogItem.Record(album), topResult(SearchResults(albums = listOf(album)), "x"))
        assertNull(topResult(SearchResults(), "x"))
    }
}
