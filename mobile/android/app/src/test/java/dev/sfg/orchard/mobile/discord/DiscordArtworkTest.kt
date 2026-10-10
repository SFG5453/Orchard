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

package dev.sfg.orchard.mobile.discord

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordArtworkTest {

    @Test
    fun trimsDiscordTextCorrectly() {
        assertEquals("Hello World", trimDiscordText("  Hello    World  "))
        assertEquals("Fallback", trimDiscordText(null, fallback = "Fallback"))
        assertEquals("Fallback", trimDiscordText("   ", fallback = "Fallback"))

        val longText = "A".repeat(200)
        val trimmed = trimDiscordText(longText, maxLen = 128)
        assertEquals(128, trimmed.length)
    }

    @Test
    fun normalizesDiscordUrls() {
        assertEquals("https://example.com/art.jpg", normalizeDiscordUrl("https://example.com/art.jpg"))
        assertEquals("", normalizeDiscordUrl("javascript:alert(1)"))
        assertEquals("", normalizeDiscordUrl("ftp://file.iso"))
        assertEquals("", normalizeDiscordUrl("   "))
    }

    @Test
    fun filtersVideoUrlsInStaticImageNormalizer() {
        assertEquals("", normalizeDiscordImageUrl("https://mvod.itunes.apple.com/apple-assets-us-std-00001/video.mp4"))
        assertEquals("https://example.com/cover.jpg", normalizeDiscordImageUrl("https://example.com/cover.jpg"))
    }
}
