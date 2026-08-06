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

package dev.sfg.orchard.mobile.download

import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDetailSynthesizerTest {

    private val track1 = Track(
        id = "t1",
        title = "Track One",
        artist = "Artist A",
        artistId = "art_a",
        album = "Album 1",
        albumId = "alb_1",
        durationMs = 180000,
    )

    private val track2 = Track(
        id = "t2",
        title = "Track Two",
        artist = "Artist A",
        artistId = "art_a",
        album = "Album 2",
        albumId = "alb_2",
        durationMs = 200000,
    )

    private val track3 = Track(
        id = "t3",
        title = "Track Three",
        artist = "Artist B",
        artistId = "art_b",
        album = "Album 1",
        albumId = "alb_1",
        durationMs = 220000,
    )

    private val downloadItems = listOf(
        DownloadItem(
            track = track1,
            status = DownloadStatus.COMPLETED,
            filePath = "/data/downloads/t1.mp3",
        ),
        DownloadItem(
            track = track2,
            status = DownloadStatus.COMPLETED,
            filePath = "/data/downloads/t2.mp3",
        ),
        DownloadItem(
            track = track3,
            status = DownloadStatus.DOWNLOADING, // Not completed yet
            filePath = "",
        ),
    )

    private val library = LibrarySnapshot(
        savedPlaylists = listOf(
            Playlist(
                id = "pl_custom",
                title = "My Playlist",
                tracks = listOf(track1, track2, track3),
            )
        ),
        savedArtists = listOf(
            Artist(
                id = "art_a",
                name = "Artist A",
                artworkUrl = "https://example.com/artist_a.jpg",
            )
        ),
        savedAlbums = listOf(
            Album(
                id = "alb_1",
                title = "Album 1",
                artist = "Artist A",
                artworkUrl = "https://example.com/alb_1.jpg",
            )
        ),
    )

    @Test
    fun `synthesizes artist with ONLY downloaded tracks`() {
        val result = OfflineDetailSynthesizer.synthesize(
            id = "art_a",
            seed = CatalogItem.Performer(Artist(id = "art_a", name = "Artist A")),
            downloadedItems = downloadItems,
            library = library,
        )

        assertNotNull(result)
        assertEquals(CatalogKind.ARTIST, result!!.kind)
        assertEquals("Artist A", result.title)
        // t1 and t2 are completed for Artist A; t3 is for Artist B
        assertEquals(listOf(track1, track2), result.tracks)
        assertEquals("https://example.com/artist_a.jpg", result.artworkUrl)
    }

    @Test
    fun `synthesizes saved playlist with only completed downloaded tracks`() {
        val result = OfflineDetailSynthesizer.synthesize(
            id = "pl_custom",
            seed = null,
            downloadedItems = downloadItems,
            library = library,
        )

        assertNotNull(result)
        assertEquals(CatalogKind.PLAYLIST, result!!.kind)
        assertEquals("My Playlist", result.title)
        // Only t1 and t2 are completed
        assertEquals(listOf(track1, track2), result.tracks)
    }

    @Test
    fun `synthesizes album with only completed downloaded tracks`() {
        val result = OfflineDetailSynthesizer.synthesize(
            id = "alb_1",
            seed = null,
            downloadedItems = downloadItems,
            library = library,
        )

        assertNotNull(result)
        assertEquals(CatalogKind.ALBUM, result!!.kind)
        assertEquals("Album 1", result.title)
        // Only t1 belongs to alb_1 and is completed (t3 is in alb_1 but still DOWNLOADING)
        assertEquals(listOf(track1), result.tracks)
    }

    @Test
    fun `synthesizes generic downloads list`() {
        val result = OfflineDetailSynthesizer.synthesize(
            id = "offline_downloads",
            seed = null,
            downloadedItems = downloadItems,
            library = library,
        )

        assertNotNull(result)
        assertEquals("Downloaded Music", result!!.title)
        assertEquals(listOf(track1, track2), result.tracks)
    }

    @Test
    fun `returns null when no completed downloads exist`() {
        val result = OfflineDetailSynthesizer.synthesize(
            id = "art_a",
            seed = null,
            downloadedItems = emptyList(),
            library = library,
        )

        assertNull(result)
    }
}
