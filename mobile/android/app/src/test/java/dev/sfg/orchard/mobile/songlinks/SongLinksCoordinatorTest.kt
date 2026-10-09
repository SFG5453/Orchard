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

import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLinksCoordinatorTest {
    private val coordinator = SongLinksCoordinator(SongLinksRepository())

    @Test
    fun initialStateIsNull() {
        assertNull(coordinator.shareState.value)
    }

    @Test
    fun dismissClearsState() {
        val track = Track(
            id = "dQw4w9WgXcQ",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000,
            artworkUrl = "https://example.com/art.jpg",
        )
        coordinator.shareTrack(track)
        val ready = coordinator.shareState.value as SongShareState.Ready
        assertEquals("https://song.link/y/dQw4w9WgXcQ", ready.shareUrl)

        coordinator.dismissShare()
        assertNull(coordinator.shareState.value)
    }

    @Test
    fun parsesDirectVideoLinks() {
        val resolution = coordinator.resolveLink("https://song.link/y/dQw4w9WgXcQ")
        assertTrue(resolution is LinkResolution.PlayTrack)
        assertEquals("dQw4w9WgXcQ", (resolution as LinkResolution.PlayTrack).track.id)
    }

    @Test
    fun parsesBrowsePlaylistLinks() {
        val resolution = coordinator.resolveLink("https://album.link/y/PL123456789")
        assertTrue(resolution is LinkResolution.OpenCollection)
        assertEquals("PL123456789", (resolution as LinkResolution.OpenCollection).browseId)
    }

    @Test
    fun nonLinksReturnNull() {
        val resolution = coordinator.resolveLink("random search text")
        assertNull(resolution)
    }
}
