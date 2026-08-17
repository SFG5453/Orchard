package dev.sfg.orchard.mobile.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A YouTube Music upload is refused by every anonymous player client. Recognising the refusal is
 * what lets the resolver stop walking the guest catalog and hand the track to the signed-in
 * player, which is the only one that can see it.
 */
class AccountOnlyPlaybackTest {

    private val pattern = YouTubeStreamResolver.ACCOUNT_ONLY_PATTERN

    @Test
    fun uploadRefusalsAreRecognised() {
        assertTrue(pattern.containsMatchIn("This video is private"))
        assertTrue(pattern.containsMatchIn("Video unavailable. This video has been set to private."))
        assertTrue(pattern.containsMatchIn("Private video"))
        assertTrue(pattern.containsMatchIn("This content is only available to the owner"))
    }

    @Test
    fun ageGatesAndOrdinaryFailuresAreNotTreatedAsAccountOnly() {
        // These already route through the age-gate path, which keeps its own retry behaviour.
        assertFalse(pattern.containsMatchIn("Sign in to confirm your age"))
        assertFalse(pattern.containsMatchIn("The following content has been identified by the YouTube community as potentially inappropriate"))
        // A genuinely gone video must keep costing the normal fallback chain, not a shortcut.
        assertFalse(pattern.containsMatchIn("Video unavailable"))
        assertFalse(pattern.containsMatchIn("This video is no longer available due to a copyright claim"))
        assertFalse(pattern.containsMatchIn("HTTP 403"))
    }
}
