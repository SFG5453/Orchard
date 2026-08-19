package dev.sfg.orchard.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgeGateTest {

    @Test
    fun testBundleAssetExists() {
        val bundleFile = File("src/main/assets/yt_solver.bundle.js")
        assertTrue("yt_solver.bundle.js must exist", bundleFile.exists())
        assertTrue("yt_solver.bundle.js must not be empty", bundleFile.length() > 1000)
    }

    @Test
    fun testAgeGatePatternMatching() {
        val ageGatePattern = YouTubeStreamResolver.AGE_GATE_PATTERN

        assertTrue(ageGatePattern.containsMatchIn("LOGIN_REQUIRED The following content has been identified by the YouTube community as potentially inappropriate"))
        assertTrue(ageGatePattern.containsMatchIn("This video is age-restricted and only available on YouTube."))
        assertTrue(ageGatePattern.containsMatchIn("Please confirm your age to view this video"))
        assertTrue(ageGatePattern.containsMatchIn("Sign in to confirm your age"))
    }

    /**
     * Probed against live YouTube on 2026-08-18: ANDROID_MUSIC, IOS_MUSIC and ANDROID_UNPLUGGED
     * all answer `LOGIN_REQUIRED / "Please sign in"` for an unrestricted public video, purely
     * because the request carries a guest identity. Matching the status alone marked ordinary
     * tracks as age-gated, which sent the playback retry down the signed-in itag 18 path instead
     * of rotating to the next client.
     */
    @Test
    fun testGuestSignInRefusalIsNotAnAgeGate() {
        val ageGatePattern = YouTubeStreamResolver.AGE_GATE_PATTERN

        assertFalse(ageGatePattern.containsMatchIn("LOGIN_REQUIRED Please sign in"))
        assertFalse(ageGatePattern.containsMatchIn("UNPLAYABLE This video is not available"))
        assertFalse(ageGatePattern.containsMatchIn("UNPLAYABLE The page needs to be reloaded."))
    }

    @Test
    fun testCipherQueryParsing() {
        val cipher = "url=https%3A%2F%2Frr1---sn-oxun-xx.googlevideo.com%2Fvideoplayback%3Fexpire%3D12345&s=abc123sig&sp=sig"
        val map = cipher.split('&').mapNotNull { param ->
            val parts = param.split('=', limit = 2)
            if (parts.isNotEmpty()) {
                parts[0] to (if (parts.size > 1) parts[1] else "")
            } else null
        }.toMap()

        assertEquals("abc123sig", map["s"])
        assertEquals("sig", map["sp"])
        assertEquals("https%3A%2F%2Frr1---sn-oxun-xx.googlevideo.com%2Fvideoplayback%3Fexpire%3D12345", map["url"])
    }

    @Test
    fun testLivePlayerConfigExtractionKeepsBuildAndTimestampTogether() {
        val iframeApi = "var scriptUrl = 'https:\\/\\/www.youtube.com\\/s\\/player\\/8d2a370b\\/www-widgetapi.js';"
        val playerJs = "const config={signatureTimestamp:20672};"

        assertEquals(
            "/s/player/8d2a370b/player_ias.vflset/en_US/base.js",
            YouTubeChallengeSolver.extractPlayerUrl(iframeApi),
        )
        assertEquals(20672, YouTubeChallengeSolver.extractSignatureTimestamp(playerJs))
    }
}

