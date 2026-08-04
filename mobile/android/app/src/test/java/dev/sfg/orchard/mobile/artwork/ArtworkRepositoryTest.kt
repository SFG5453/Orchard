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

package dev.sfg.orchard.mobile.artwork

import dev.sfg.orchard.mobile.model.Track
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkRepositoryTest {
    private val repository = ArtworkRepository(OkHttpClient())
    private val espresso = Track("id", "Espresso", "Sabrina Carpenter", "Short n' Sweet")

    @Test
    fun m8tecResponseAcceptsCompatibleDeluxeAlbumName() {
        val result = repository.normalizeResponse(
            "m8tec",
            JSONObject()
                .put("artist", "Sabrina Carpenter")
                .put("album", "Short n' Sweet (Deluxe)")
                .put("url", "https://example.test/artwork.m3u8")
                .put("url_tall", "https://example.test/artwork_tall.m3u8"),
            espresso,
        )

        assertEquals("https://example.test/artwork.m3u8", result?.videoUrl)
        assertEquals("https://example.test/artwork_tall.m3u8", result?.videoUrlVertical)
        assertEquals("https://example.test/artwork_tall.m3u8", result?.preferredVideoUrl)
    }

    @Test
    fun genericProviderRejectsArtworkForDifferentTitle() {
        val result = repository.normalizeResponse(
            "boidu",
            JSONObject().put("name", "Please Please Please")
                .put("artist", "Sabrina Carpenter")
                .put("videoUrl", "https://example.test/wrong.mp4"),
            espresso,
        )

        assertNull(result)
    }

    @Test
    fun genericProviderPreservesStaticAndAnimatedUrls() {
        val result = repository.normalizeResponse(
            "orchard",
            JSONObject().put("name", "Espresso")
                .put("artist", "Sabrina Carpenter")
                .put("static", "https://example.test/poster.jpg")
                .put("animated", "https://example.test/master.m3u8")
                .put("animatedVertical", "https://example.test/master_tall.m3u8"),
            espresso,
        )

        assertEquals("https://example.test/poster.jpg", result?.staticUrl)
        assertEquals("https://example.test/master.m3u8", result?.videoUrl)
        assertEquals("https://example.test/master_tall.m3u8", result?.videoUrlVertical)
    }

    @Test
    fun genericProviderAcceptsAlbumNameMatch() {
        val result = repository.normalizeResponse(
            "orchard",
            JSONObject().put("name", "Short n' Sweet")
                .put("artist", "Sabrina Carpenter")
                .put("animated", "https://example.test/album_master.m3u8"),
            Track("id", "Taste", "Sabrina Carpenter", "Short n' Sweet"),
        )

        assertEquals("https://example.test/album_master.m3u8", result?.videoUrl)
    }
}

