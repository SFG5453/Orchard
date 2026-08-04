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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordModelsTest {

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

    @Test
    fun formatsAnimatedArtworkProxyUrls() {
        val appleMotionUrl = "https://mvod.itunes.apple.com/apple-assets-us-std-00001/video.mp4"
        val proxyUrl = discordAnimatedArtworkUrl(appleMotionUrl)
        assertTrue(proxyUrl.startsWith("https://artwork-proxy.sfg545.dev/convert.gif?v=7&url="))
        assertTrue(proxyUrl.contains("mvod.itunes.apple.com"))

        val nonAppleMotionUrl = "https://example.com/video.mp4"
        assertEquals("", discordAnimatedArtworkUrl(nonAppleMotionUrl))

        val staticImageUrl = "https://mvod.itunes.apple.com/cover.jpg"
        assertEquals("", discordAnimatedArtworkUrl(staticImageUrl))
    }

    @Test
    fun serializesDiscordPresenceActivityToJson() {
        val activity = DiscordPresenceActivity(
            name = "Radiohead",
            type = 2,
            details = "Karma Police",
            state = "OK Computer",
            timestamps = DiscordPresenceTimestamps(start = 1700000000000L, end = 1700000260000L),
            assets = DiscordPresenceAssets(
                largeImage = "mp:external/abc123xyz",
                largeText = "OK Computer",
                smallImage = null,
                smallText = "Playing",
            ),
            buttons = listOf(
                DiscordPresenceButton("Listen on Your Platform", "https://songlinks.sfg545.dev/s/123"),
                DiscordPresenceButton("View the Orchard Project", "https://sfg545.dev/orchard"),
            ),
            applicationId = DISCORD_APPLICATION_ID,
            platform = "android",
        )

        val json = activity.toJson()
        assertEquals("Radiohead", json.getString("name"))
        assertEquals(2, json.getInt("type"))
        assertEquals("Karma Police", json.getString("details"))
        assertEquals("OK Computer", json.getString("state"))
        assertEquals(DISCORD_APPLICATION_ID, json.getString("application_id"))
        assertEquals("android", json.getString("platform"))

        val timestamps = json.getJSONObject("timestamps")
        assertEquals(1700000000000L, timestamps.getLong("start"))
        assertEquals(1700000260000L, timestamps.getLong("end"))

        val assets = json.getJSONObject("assets")
        assertEquals("mp:external/abc123xyz", assets.getString("large_image"))
        assertEquals("OK Computer", assets.getString("large_text"))

        val buttons = json.getJSONArray("buttons")
        assertEquals(2, buttons.length())
        assertEquals("Listen on Your Platform", buttons.getString(0))
        assertEquals("View the Orchard Project", buttons.getString(1))

        val metadata = json.getJSONObject("metadata")
        val metadataUrls = metadata.getJSONArray("button_urls")
        assertEquals(2, metadataUrls.length())
        assertEquals("https://songlinks.sfg545.dev/s/123", metadataUrls.getString(0))
        assertEquals("https://sfg545.dev/orchard", metadataUrls.getString(1))
    }

    @Test
    fun computesDiscordAvatarUrls() {
        val pngAccount = DiscordAccount(id = "12345", username = "testuser", avatar = "abcdef123456")
        assertEquals("https://cdn.discordapp.com/avatars/12345/abcdef123456.png?size=128", pngAccount.avatarUrl)

        val gifAccount = DiscordAccount(id = "12345", username = "testuser", avatar = "a_animated123")
        assertEquals("https://cdn.discordapp.com/avatars/12345/a_animated123.gif?size=128", gifAccount.avatarUrl)

        val defaultAccount = DiscordAccount(id = "12345", username = "testuser", avatar = null)
        assertNull(defaultAccount.avatarUrl)
    }
}
