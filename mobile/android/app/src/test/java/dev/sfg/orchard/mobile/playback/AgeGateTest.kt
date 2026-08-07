package dev.sfg.orchard.mobile.playback

import org.junit.Assert.assertEquals
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
        val ageGatePattern = Regex(
            """(?i)confirm[\s_-]*your[\s_-]*age|age[\s_-]*restrict|inappropriate for some users|LOGIN_REQUIRED""",
        )

        assertTrue(ageGatePattern.containsMatchIn("LOGIN_REQUIRED The following content has been identified by the YouTube community as potentially inappropriate"))
        assertTrue(ageGatePattern.containsMatchIn("This video is age-restricted and only available on YouTube."))
        assertTrue(ageGatePattern.containsMatchIn("Please confirm your age to view this video"))
        assertTrue(ageGatePattern.containsMatchIn("Sign in to confirm your age"))
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
}


