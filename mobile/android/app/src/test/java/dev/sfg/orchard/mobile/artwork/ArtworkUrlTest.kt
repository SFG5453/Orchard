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

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkUrlTest {
    @Test
    fun expandsGoogleThumbnailWithoutDroppingItsCropOptions() {
        assertEquals(
            "https://lh3.googleusercontent.com/art=w540-h540-l90-rj",
            highResolutionArtworkUrl("https://lh3.googleusercontent.com/art=w60-h60-l90-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/art=w1200-h1200-l90-rj",
            highResolutionArtworkUrl("https://lh3.googleusercontent.com/art=w60-h60-l90-rj", 1200),
        )
    }

    @Test
    fun expandsAppleMusicArtworkUrls() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/540x540bb.jpg",
            highResolutionArtworkUrl("https://is1-ssl.mzstatic.com/image/thumb/Music/{w}x{h}bb.jpg"),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/128x128bb.jpg",
            highResolutionArtworkUrl("https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg", 128),
        )
    }
}


