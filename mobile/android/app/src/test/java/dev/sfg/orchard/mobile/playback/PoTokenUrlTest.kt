package dev.sfg.orchard.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * googlevideo refuses direct stream URLs without a proof-of-origin token for a growing share of
 * sessions, which is the Android half of the WebPO work desktop received in 716f5f0. These cover
 * the URL rules; the minting itself needs a WebView and BotGuard, so it is not reachable here.
 */
class PoTokenUrlTest {

    private val streamUrl =
        "https://rr3---sn-oxun-xxxx.googlevideo.com/videoplayback?expire=1770000000&itag=251&c=ANDROID_VR&mime=audio%2Fwebm"

    @Test
    fun attachesTheTokenAsPot() {
        val token = "MlVoWROhR001bGhnqjKUXcQTBDIPRoCrgu4lk1fIvAKKoUuO"
        val protectedUrl = YouTubePoTokenMinter.withPoToken(streamUrl, token)

        assertEquals(token, YouTubePoTokenMinter.poTokenOf(protectedUrl))
        // Everything the CDN already checks has to survive being rebuilt.
        assertTrue(protectedUrl.startsWith("https://rr3---sn-oxun-xxxx.googlevideo.com/videoplayback?"))
        assertTrue(protectedUrl.contains("expire=1770000000"))
        assertTrue(protectedUrl.contains("c=ANDROID_VR"))
        assertTrue(protectedUrl.contains("mime=audio%2Fwebm"))
    }

    /** Web-safe base64 is what the minter returns; escaping it would invalidate the proof. */
    @Test
    fun keepsWebSafeBase64Intact() {
        val token = "abc-_DEF123.xyz"
        val protectedUrl = YouTubePoTokenMinter.withPoToken(streamUrl, token)

        assertEquals(token, YouTubePoTokenMinter.poTokenOf(protectedUrl))
    }

    @Test
    fun withoutATokenTheUrlIsUntouched() {
        assertEquals(streamUrl, YouTubePoTokenMinter.withPoToken(streamUrl, null))
        assertEquals(streamUrl, YouTubePoTokenMinter.withPoToken(streamUrl, ""))
        assertEquals("", YouTubePoTokenMinter.poTokenOf(streamUrl))
    }

    /** A second `pot` would be ignored in favour of the first, hiding a re-minted token. */
    @Test
    fun doesNotAttachASecondToken() {
        val once = YouTubePoTokenMinter.withPoToken(streamUrl, "first")
        val twice = YouTubePoTokenMinter.withPoToken(once, "second")

        assertEquals(once, twice)
        assertEquals("first", YouTubePoTokenMinter.poTokenOf(twice))
    }

    @Test
    fun aMalformedUrlIsReturnedRatherThanThrown() {
        assertEquals("not a url", YouTubePoTokenMinter.withPoToken("not a url", "token"))
        assertEquals("", YouTubePoTokenMinter.poTokenOf("not a url"))
    }

    @Test
    fun bundleAssetIsPresentForTheWebView() {
        val bundle = File("src/main/assets/yt_potoken.bundle.js")
        assertTrue("yt_potoken.bundle.js must exist", bundle.exists())
        assertTrue("yt_potoken.bundle.js must not be empty", bundle.length() > 1000)
    }
}
