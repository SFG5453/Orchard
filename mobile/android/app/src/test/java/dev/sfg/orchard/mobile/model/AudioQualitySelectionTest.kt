/* Copyright (C) 2026 SFG545. Licensed under the GNU Affero General Public License v3 or later. */
package dev.sfg.orchard.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualitySelectionTest {
    @Test fun atmosphereRequiresAppleMusicAccountAndDecoder() {
        assertTrue(atmosQualityAvailable(APPLE_MUSIC_SOURCE, signedIn = true, decoderAvailable = true))
        assertFalse(atmosQualityAvailable(YOUTUBE_SOURCE, signedIn = true, decoderAvailable = true))
        assertFalse(atmosQualityAvailable(APPLE_MUSIC_SOURCE, signedIn = false, decoderAvailable = true))
        assertFalse(atmosQualityAvailable(APPLE_MUSIC_SOURCE, signedIn = true, decoderAvailable = false))
    }

    @Test fun unavailableAtmosDisplaysAndStreamsAsHigh() {
        assertFalse(audioQualityOptions(false).contains(AudioQuality.ATMOS))
        assertTrue(audioQualityOptions(true).contains(AudioQuality.ATMOS))
        assertEquals(AudioQuality.HIGH, visibleAudioQuality(AudioQuality.ATMOS, false, true))
        assertEquals(AudioQuality.ATMOS, visibleAudioQuality(AudioQuality.ATMOS, true, false))
        assertEquals(AudioQuality.HIGH, youtubeAudioQuality(AudioQuality.ATMOS))
    }

    @Test fun maxWithoutQobuzDisplaysAndStreamsAsHigh() {
        assertTrue(audioQualityOptions(false).contains(AudioQuality.MAX))
        assertEquals(AudioQuality.HIGH, visibleAudioQuality(AudioQuality.MAX, true, false))
        assertEquals(AudioQuality.MAX, visibleAudioQuality(AudioQuality.MAX, false, true))
        assertEquals(AudioQuality.HIGH, youtubeAudioQuality(AudioQuality.MAX))
    }

    @Test fun legacyAtmosMigratesWithoutOverridingFutureQualityChoices() {
        assertEquals(AudioQuality.ATMOS, storedAudioQuality("NORMAL", legacyAtmos = true))
        assertEquals(AudioQuality.NORMAL, storedAudioQuality("NORMAL", legacyAtmos = false))
        assertEquals(AudioQuality.ATMOS, storedAudioQuality("ATMOS", legacyAtmos = false))
        assertEquals(AudioQuality.HIGH, storedAudioQuality("obsolete", legacyAtmos = false))
    }

    @Test fun wifiAndMobileDataChooseTheirOwnQuality() {
        val settings = OrchardSettings(audioQuality = AudioQuality.ATMOS, cellularAudioQuality = AudioQuality.NORMAL)
        assertEquals(AudioQuality.ATMOS, settings.audioQualityForNetwork(metered = false))
        assertEquals(AudioQuality.NORMAL, settings.audioQualityForNetwork(metered = true))
    }
}
