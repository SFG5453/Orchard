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

package dev.sfg.orchard.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAlbumsTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F"

    private fun song(path: String, album: String, albumArtist: String = "", artist: String = "A", disc: Int = 0, track: Int = 0) =
        LocalSong(
            id = localTrackId(tree + path), uri = tree + path, title = path.substringAfterLast("%2F"),
            artist = artist, album = album, albumArtist = albumArtist, discNumber = disc, trackNumber = track,
        )

    @Test
    fun groupsByAlbumArtistOrFolder() {
        val songs = listOf(
            song("dp%2F02.flac", "Discovery", "Daft Punk", disc = 1, track = 2),
            song("other%2F01.flac", "discovery ", "daft punk", disc = 1, track = 1),
            song("hits%2FCD%202%2Fb.mp3", "Hits", artist = "Y", disc = 2, track = 1),
            song("hits%2FCD%201%2Fa.mp3", "Hits", artist = "X", disc = 1, track = 1),
            song("elsewhere%2Fc.mp3", "Hits", artist = "X"),
            song("loose.mp3", ""),
        )
        val albums = LocalSnapshot(songs = songs).albums()
        assertEquals(3, albums.size)
        assertEquals("", localAlbumId(songs[5]))
        assertEquals(localAlbumId(songs[0]), localAlbumId(songs[1]))
        assertEquals(localAlbumId(songs[2]), localAlbumId(songs[3]))
        assertNotEquals(localAlbumId(songs[3]), localAlbumId(songs[4]))

        val discovery = albums.first { it.id == localAlbumId(songs[0]) }
        assertEquals(listOf("01.flac", "02.flac"), discovery.songs.map { it.title })
        assertEquals(discovery.id, discovery.songs.first().toTrack().albumId)
        val hits = albums.first { it.id == localAlbumId(songs[2]) }
        assertEquals("Various Artists", hits.artist)
        assertEquals(listOf("a.mp3", "b.mp3"), hits.songs.map { it.title })
        assertTrue(isLocalCollectionId(hits.id))
    }

    @Test
    fun mediaPickerSongsFallBackToArtist() {
        val picked = { id: String, artist: String ->
            LocalSong(id = id, uri = "content://com.android.providers.media.documents/document/audio%3A$id", title = id, artist = artist, album = "Greatest Hits")
        }
        assertNotEquals(localAlbumId(picked("1", "Queen")), localAlbumId(picked("2", "ABBA")))
        assertEquals(localAlbumId(picked("1", "Queen")), localAlbumId(picked("3", "queen")))
    }

    @Test
    fun leadingNumbersParse() {
        assertEquals(3, LocalMetadataReader.leadingNumber("3/12"))
        assertEquals(2004, LocalMetadataReader.leadingNumber("2004"))
        assertEquals(0, LocalMetadataReader.leadingNumber(null))
    }
}
