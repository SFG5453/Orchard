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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimatedArtworkCacheTest {

    @Test
    fun highestQualityVariantPrefersResolutionThenBandwidth() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            720/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1920x1080
            1080/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=854x480
            480/playlist.m3u8
        """.trimIndent()

        assertEquals(
            "1080/playlist.m3u8",
            AnimatedArtworkCache.highestQualityVariant(manifest),
        )
    }

    @Test
    fun mediaPlaylistHasNoVariant() {
        val manifest = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            segment-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertNull(AnimatedArtworkCache.highestQualityVariant(manifest))
    }
}
