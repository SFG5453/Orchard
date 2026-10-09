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

package dev.sfg.orchard.mobile.youtube

import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.auth.YouTubeSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {
    private val row = """{"id":"abcdefghijk","type":"track","musicVideoType":"MUSIC_VIDEO_TYPE_ATV",
        "title":"Duet","artists":["First","Second"],"artistBrowseIds":["UCfirst","UCsecond"],
        "artist":"First","album":"","albumId":null,"durationSeconds":190,"explicit":true,
        "thumbnail":"https://i.ytimg.com/vi/abcdefghijk/hq.jpg","isUpload":false}"""

    @Test fun `provider session carries selected account index and channel`() {
        val session = YouTubeSession("SAPISID=cookie", dataSyncId = "brand-id", accountIndex = 2)
        val payload = session.providerJson()
        assertEquals("brand-id", payload.getString("dataSyncId"))
        assertEquals(2, payload.getInt("accountIndex"))
    }

    @Test fun `tracks keep every credit and treat null as empty`() {
        val track = checkNotNull(JSONObject(row).providerTrack())
        assertEquals("First, Second", track.artist)
        assertEquals(listOf("UCfirst", "UCsecond"), track.artists.map { it.id })
        assertEquals("", track.albumId)
        assertEquals(190_000L, track.durationMs)
        assertTrue(track.explicit && track.isAudioOnly)
    }

    @Test fun `browse tiles become albums artists and playlists`() {
        fun tile(browseId: String, type: String) = JSONObject("""{"id":null,"browseId":"$browseId",
            "type":"$type","title":"T","subtitle":"Album • SZA • 2022","thumbnail":"art"}""").providerItem()
        val album = tile("MPREb_x", "album") as CatalogItem.Record
        assertEquals("SZA", album.album.artist)
        assertEquals("2022", album.album.year)
        assertTrue(tile("UCartist", "artist") is CatalogItem.Performer)
        assertTrue(tile("VLPLlist", "playlist") is CatalogItem.Collection)
        assertNull(JSONObject("""{"id":null,"browseId":null,"title":"Nothing"}""").providerItem())
    }

    @Test fun `album rows inherit the album and replace video stills with its cover`() {
        val album = JSONObject("""{"kind":"album","browseId":"MPREb_x","audioPlaylistId":"OLAK5uy_sos","title":"SOS","artist":"SZA",
            "thumbnail":"cover","tracks":[$row],"sections":[]}""")
        val detail = album.providerDetail("MPREb_x", CatalogKind.ALBUM)
        val track = detail.tracks.single()
        assertEquals("SOS", track.album)
        assertEquals("MPREb_x", track.albumId)
        assertEquals("OLAK5uy_sos", detail.audioPlaylistId)
        assertEquals("cover", track.artworkUrl)
    }

    @Test fun `search sections split by key`() {
        val results = JSONObject("""{"sections":[
            {"key":"songs","items":[$row]},
            {"key":"videos","items":[${row.replace("abcdefghijk", "videovideo1")}]},
            {"key":"artists","items":[{"browseId":"UCa","type":"artist","title":"A"}]}]}""").providerSearch()
        assertEquals(listOf("abcdefghijk"), results.tracks.map { it.id })
        assertEquals(listOf("videovideo1"), results.videos.map { it.id })
        assertEquals(listOf("UCa"), results.artists.map { it.id })
    }
}
