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

package dev.sfg.orchard.mobile.songlinks

import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLinksRepositoryTest {
    private val repo = SongLinksRepository()

    @Test
    fun buildsDesktopCompatibleLinksWithoutResolver() {
        assertEquals("https://song.link/y/dQw4w9WgXcQ",
            repo.trackUrl(Track(id = "dQw4w9WgXcQ", title = "Song", artist = "Artist")))
        assertEquals("https://album.link/y/OLAK5uy_sample",
            repo.collectionUrl(BrowseDetail(id = "MPREb_internal", kind = CatalogKind.ALBUM,
                title = "Album", audioPlaylistId = "OLAK5uy_sample")))
        assertEquals("https://album.link/y/PL123456789",
            repo.collectionUrl(BrowseDetail(id = "VLPL123456789", kind = CatalogKind.PLAYLIST,
                title = "Playlist")))
        assertEquals("https://music.youtube.com/channel/UC123456789",
            repo.collectionUrl(BrowseDetail(id = "UC123456789", kind = CatalogKind.ARTIST,
                title = "Artist")))
    }

    @Test
    fun doesNotShareInternalOrLocalIdentities() {
        assertNull(repo.trackUrl(Track(id = "local_track", title = "Song", artist = "Artist",
            playbackSource = "local")))
        assertNull(repo.collectionUrl(BrowseDetail(id = "MPREb_internal", kind = CatalogKind.ALBUM,
            title = "Album")))
        assertNull(repo.collectionUrl(BrowseDetail(id = "offline_downloads", kind = CatalogKind.PLAYLIST,
            title = "Downloads")))
    }

    @Test
    fun parsesPublicLinksAndYouTubeLinks() {
        val song = repo.parseLink("https://song.link/y/dQw4w9WgXcQ")
        assertTrue(song is SongLinkTarget.Video)
        assertEquals("dQw4w9WgXcQ", (song as SongLinkTarget.Video).videoId)

        val album = repo.parseLink("album.link/y/OLAK5uy_sample")
        assertTrue(album is SongLinkTarget.Browse)
        assertEquals("OLAK5uy_sample", (album as SongLinkTarget.Browse).browseId)
        assertEquals("album", album.kind)

        val playlist = repo.parseLink("https://album.link/y/PL123456789")
        assertTrue(playlist is SongLinkTarget.Browse)
        assertEquals("PL123456789", (playlist as SongLinkTarget.Browse).browseId)

        assertEquals("dQw4w9WgXcQ",
            (repo.parseLink("https://youtu.be/dQw4w9WgXcQ") as SongLinkTarget.Video).videoId)
        assertEquals("PL123456789",
            (repo.parseLink("https://music.youtube.com/playlist?list=VLPL123456789") as SongLinkTarget.Browse).browseId)
        assertEquals("dQw4w9WgXcQ",
            (repo.parseLink("Song - Artist\nhttps://song.link/y/dQw4w9WgXcQ") as SongLinkTarget.Video).videoId)
    }

    @Test
    fun ignoresUnsupportedResolverAndForeignLinks() {
        assertNull(repo.parseLink("https://songlinks.sfg545.dev/s/legacy-id"))
        assertNull(repo.parseLink("https://example.com/y/dQw4w9WgXcQ"))
        assertNull(repo.parseLink("https://song.link/y/not-a-video-id"))
    }
}
