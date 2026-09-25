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

import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun storePersistsAndLoadsCompletedDownloads() {
        val baseDir = tempFolder.newFolder("downloads_test")
        val store = DownloadStore(baseDir = baseDir)

        val trackFile = File(store.downloadDir, "track_1.opus").apply {
            writeText("dummy-audio-bytes-1234567890")
        }

        val track = Track(
            id = "track_1",
            title = "Awesome Song",
            artist = "Awesome Artist",
            album = "Awesome Album",
            albumId = "album_1",
            artistId = "artist_1",
            artworkUrl = "https://example.com/cover.png",
            durationMs = 210000L,
        )

        val item = DownloadItem(
            track = track,
            status = DownloadStatus.COMPLETED,
            progress = 1.0f,
            bytesDownloaded = trackFile.length(),
            totalBytes = trackFile.length(),
            filePath = trackFile.absolutePath,
            mimeType = "audio/opus",
            downloadedAtMs = System.currentTimeMillis(),
        )

        store.save(item)

        // Reload fresh from disk using a new store instance pointing to same baseDir
        val freshStore = DownloadStore(baseDir = baseDir)
        val loaded = freshStore.loadAll()

        assertEquals(1, loaded.size)
        val loadedItem = loaded["track_1"]
        assertNotNull(loadedItem)
        assertEquals("Awesome Song", loadedItem?.track?.title)
        assertEquals("Awesome Artist", loadedItem?.track?.artist)
        assertEquals(DownloadStatus.COMPLETED, loadedItem?.status)
        assertEquals(trackFile.length(), loadedItem?.bytesDownloaded)
        assertTrue(File(loadedItem!!.filePath).exists())
    }

    @Test
    fun removeDeletesPersistedEntryAndPhysicalFile() {
        val baseDir = tempFolder.newFolder("downloads_remove_test")
        val store = DownloadStore(baseDir = baseDir)

        val trackFile = File(store.downloadDir, "track_remove.opus").apply {
            writeText("audio-data-to-be-removed")
        }
        assertTrue(trackFile.exists())

        val item = DownloadItem(
            track = Track(id = "track_remove", title = "To Remove", artist = "Artist"),
            status = DownloadStatus.COMPLETED,
            filePath = trackFile.absolutePath,
            bytesDownloaded = trackFile.length(),
        )

        store.save(item)
        assertEquals(1, store.loadAll().size)

        // Remove
        store.remove("track_remove")

        assertEquals(0, store.loadAll().size)
        assertTrue(!trackFile.exists())
    }

    @Test
    fun totalBytesCalculatedCorrectly() {
        val baseDir = tempFolder.newFolder("downloads_bytes_test")
        val store = DownloadStore(baseDir = baseDir)

        val f1 = File(store.downloadDir, "t1.opus").apply { writeBytes(ByteArray(1024)) }
        val f2 = File(store.downloadDir, "t2.opus").apply { writeBytes(ByteArray(2048)) }

        store.save(DownloadItem(track = Track(id = "t1", title = "T1", artist = "A1"), status = DownloadStatus.COMPLETED, filePath = f1.absolutePath, bytesDownloaded = 1024L))
        store.save(DownloadItem(track = Track(id = "t2", title = "T2", artist = "A2"), status = DownloadStatus.COMPLETED, filePath = f2.absolutePath, bytesDownloaded = 2048L))

        assertEquals(3072L, store.totalBytesUsed())
    }

    @Test
    fun completedDownloadRequiresARealNonEmptyFile() {
        val track = Track(id = "stale", title = "Stale", artist = "Artist")
        val missing = File(tempFolder.root, "removed.webm")
        val empty = tempFolder.newFile("empty.webm")
        val audio = tempFolder.newFile("audio.webm").apply { writeText("audio") }

        assertNull(
            DownloadItem(track, DownloadStatus.COMPLETED, filePath = missing.absolutePath)
                .completedFileOrNull(),
        )
        assertNull(
            DownloadItem(track, DownloadStatus.COMPLETED, filePath = empty.absolutePath)
                .completedFileOrNull(),
        )
        assertNull(
            DownloadItem(track, DownloadStatus.DOWNLOADING, filePath = audio.absolutePath)
                .completedFileOrNull(),
        )
        assertEquals(
            audio.absolutePath,
            DownloadItem(track, DownloadStatus.COMPLETED, filePath = audio.absolutePath)
                .completedFileOrNull()?.absolutePath,
        )
    }
}
