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

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLinksRepositoryTest {
    private val repo = SongLinksRepository(OkHttpClient())

    @Test
    fun parsesSongLinksUrls() {
        val songTarget = repo.parseLink("https://songlinks.sfg545.dev/s/xyz123")
        assertTrue(songTarget is SongLinkTarget.Song)
        assertEquals("xyz123", (songTarget as SongLinkTarget.Song).id)

        val collectionTarget = repo.parseLink("https://songlinks.sfg545.dev/c/col456")
        assertTrue(collectionTarget is SongLinkTarget.Collection)
        assertEquals("col456", (collectionTarget as SongLinkTarget.Collection).id)
    }

    @Test
    fun parsesOrchardCustomScheme() {
        val song = repo.parseLink("orchard:s/track123")
        assertTrue(song is SongLinkTarget.Song)
        assertEquals("track123", (song as SongLinkTarget.Song).id)

        val col = repo.parseLink("orchard:c/playlist999")
        assertTrue(col is SongLinkTarget.Collection)
        assertEquals("playlist999", (col as SongLinkTarget.Collection).id)

        val album = repo.parseLink("orchard:album/MPRE12345")
        assertTrue(album is SongLinkTarget.Browse)
        assertEquals("album", (album as SongLinkTarget.Browse).kind)
        assertEquals("MPRE12345", album.browseId)
    }

    @Test
    fun parsesYouTubeLinks() {
        val shortLink = repo.parseLink("https://youtu.be/dQw4w9WgXcQ")
        assertTrue(shortLink is SongLinkTarget.Video)
        assertEquals("dQw4w9WgXcQ", (shortLink as SongLinkTarget.Video).videoId)

        val watchLink = repo.parseLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(watchLink is SongLinkTarget.Video)
        assertEquals("dQw4w9WgXcQ", (watchLink as SongLinkTarget.Video).videoId)

        val musicLink = repo.parseLink("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(musicLink is SongLinkTarget.Video)
        assertEquals("dQw4w9WgXcQ", (musicLink as SongLinkTarget.Video).videoId)

        val playlistLink = repo.parseLink("https://www.youtube.com/playlist?list=PL123456789")
        assertTrue(playlistLink is SongLinkTarget.Browse)
        assertEquals("playlist", (playlistLink as SongLinkTarget.Browse).kind)
        assertEquals("PL123456789", playlistLink.browseId)
    }
}
