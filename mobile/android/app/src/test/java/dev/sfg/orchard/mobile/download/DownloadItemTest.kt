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
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadItemTest {

    @Test
    fun jsonRoundTripPreservesFields() {
        val track = Track(
            id = "track_123",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            albumId = "album_456",
            artistId = "artist_789",
            artworkUrl = "https://example.com/art.jpg",
            durationMs = 180000L,
        )

        val item = DownloadItem(
            track = track,
            status = DownloadStatus.COMPLETED,
            progress = 1.0f,
            bytesDownloaded = 5242880L,
            totalBytes = 5242880L,
            filePath = "/path/to/downloaded/track.opus",
            mimeType = "audio/opus",
            downloadedAtMs = 1700000000000L,
            errorMessage = "",
        )

        val json = item.toJson()
        val restored = DownloadItem.fromJson(json)

        assertEquals("track_123", restored.track.id)
        assertEquals("Test Song", restored.track.title)
        assertEquals("Test Artist", restored.track.artist)
        assertEquals("Test Album", restored.track.album)
        assertEquals("https://example.com/art.jpg", restored.track.artworkUrl)
        assertEquals(180000L, restored.track.durationMs)
        assertEquals(DownloadStatus.COMPLETED, restored.status)
        assertEquals(5242880L, restored.bytesDownloaded)
        assertEquals("/path/to/downloaded/track.opus", restored.filePath)
        assertEquals("audio/opus", restored.mimeType)
        assertTrue(restored.isFinished)
    }

    @Test
    fun downloadStatusHelperProperties() {
        val queued = DownloadItem(
            track = Track(id = "1", title = "T", artist = "A"),
            status = DownloadStatus.QUEUED,
        )
        assertTrue(queued.isDownloading)
        assertTrue(!queued.isFinished)

        val finished = DownloadItem(
            track = Track(id = "2", title = "T2", artist = "A2"),
            status = DownloadStatus.COMPLETED,
        )
        assertTrue(finished.isFinished)
        assertTrue(!finished.isDownloading)
    }
}
