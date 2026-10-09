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

import dev.sfg.orchard.mobile.model.CatalogJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun song(id: String, cover: String = "") = LocalSong(
        id = id, uri = "content://music/$id", title = "Title $id", artist = "Artist", durationMs = 187_000,
        bitrateKbps = 320, mimeType = "audio/mpeg", coverPath = cover,
    )

    @Test
    fun `ids are stable and prefixed`() {
        val id = localTrackId("content://music/1")
        assertTrue(isLocalTrackId(id))
        assertEquals(id, localTrackId("content://music/1"))
        assertNotEquals(id, localTrackId("content://music/2"))
        assertTrue(isLocalPlaylistId(LOCAL_PLAYLIST_PREFIX + "abc"))
    }

    @Test
    fun `a song becomes a playable local track with its probed bitrate`() {
        val track = song("local:1", "/store/covers/a.jpg").toTrack()
        assertTrue(track.isLocal)
        assertEquals("content://music/local:1", track.localUri)
        assertEquals(320, track.localBitrateKbps)
        assertEquals("mpeg", track.codec)
        assertEquals("file:///store/covers/a.jpg", track.artworkUrl)
        // Survives the JSON used for queue persistence.
        val restored = CatalogJson.track(CatalogJson.track(track))
        assertEquals(track.localUri, restored.localUri)
        assertTrue(restored.isLocal)
    }

    @Test
    fun `the store round trips and drops songs that no longer exist from playlists`() {
        val store = LocalStore(folder.newFolder("local"))
        val playlist = LocalPlaylist("local-playlist:p", "Road trip", trackIds = listOf("local:1", "local:2", "local:gone"))
        store.save(LocalSnapshot(listOf(song("local:1"), song("local:2")), listOf(playlist)))

        val loaded = LocalStore(store.root).load()
        assertEquals(listOf("local:1", "local:2"), loaded.songs.map { it.id })
        assertEquals(listOf("local:1", "local:2"), loaded.playlists.single().trackIds)
        assertEquals("Road trip", loaded.playlists.single().title)
    }

    @Test
    fun `a missing or corrupt library file loads as empty`() {
        val root = folder.newFolder("empty")
        assertTrue(LocalStore(root).load().songs.isEmpty())
        File(root, "library.json").writeText("{not json")
        assertTrue(LocalStore(root).load().playlists.isEmpty())
    }

    @Test
    fun `moving keeps the order the drag asked for`() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "d", "a"), ids.movedTo(0, 3))
        assertEquals(listOf("d", "a", "b", "c"), ids.movedTo(3, 0))
        assertNull(ids.movedTo(1, 1))
        assertNull(ids.movedTo(0, 9))
    }

    @Test
    fun `file names stand in for missing tags`() {
        val parsed = LocalMetadataReader.metadataFromFileName("01 - Daft Punk - Digital Love.mp3")
        assertEquals("Daft Punk", parsed.artist)
        assertEquals("Digital Love", parsed.title)
        val plain = LocalMetadataReader.metadataFromFileName("just_a_title.flac")
        assertEquals("", plain.artist)
        assertEquals("just a title", plain.title)
    }

    @Test
    fun `the collage uses the first four distinct covers that exist`() {
        val a = folder.newFile("a.jpg").absolutePath
        val b = folder.newFile("b.jpg").absolutePath
        val sources = LocalCollage.sources(listOf(a, a, "", "/missing.jpg", b))
        assertEquals(listOf(a, b), sources)
        assertEquals(LocalCollage.key(sources), LocalCollage.key(sources))
    }
}
