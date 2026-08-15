/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

package dev.sfg.orchard.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeStreamRequestIdentityTest {
    @Test
    fun `web remix URL receives web headers`() {
        val identity = YouTubeStreamRequestIdentity.fromUrl(
            "https://rr.googlevideo.com/videoplayback?c=WEB_REMIX&cver=1.20260707.12.00",
            "fallback",
        )

        assertEquals(YouTubeStreamRequestIdentity.WEB_REMIX_USER_AGENT, identity.userAgent)
        assertEquals("https://music.youtube.com", identity.origin)
        assertEquals("https://music.youtube.com/", identity.referer)
        assertEquals("WEB_REMIX@1.20260707.12.00", identity.clientKey)
    }

    @Test
    fun `native URL receives matching user agent without web origin`() {
        val identity = YouTubeStreamRequestIdentity.fromUrl(
            "https://rr.googlevideo.com/videoplayback?c=ANDROID_VR&cver=1.61.48",
            "fallback",
        )

        assertEquals(YouTubeStreamRequestIdentity.ANDROID_VR_1_61_USER_AGENT, identity.userAgent)
        assertNull(identity.origin)
        assertNull(identity.referer)
        assertEquals("ANDROID_VR@1.61.48", identity.clientKey)
    }

    @Test
    fun `unknown URL preserves resolver identity`() {
        val identity = YouTubeStreamRequestIdentity.fromUrl(
            "https://example.com/audio",
            "resolver-agent",
        )

        assertEquals("resolver-agent", identity.userAgent)
        assertNull(identity.origin)
        assertNull(identity.referer)
    }
}
