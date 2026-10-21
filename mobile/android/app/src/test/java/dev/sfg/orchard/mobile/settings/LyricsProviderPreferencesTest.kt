/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.settings

import dev.sfg.orchard.mobile.model.LyricsProvider
import dev.sfg.orchard.mobile.model.LyricsProviderConfig
import dev.sfg.orchard.mobile.model.visibleLyricsProviders
import org.junit.Assert.*
import org.junit.Test

class LyricsProviderPreferencesTest {
    @Test
    fun defaultsAndCorruptPreferencesOfferEveryProvider() {
        for (value in listOf(null, "", "broken")) {
            val decoded = decodeLyricsProviders(value)
            assertEquals(LyricsProvider.entries, decoded.map { it.provider })
            assertTrue(decoded.all { it.enabled })
        }
    }

    @Test
    fun orderAndDisabledProvidersSurviveRoundTrip() {
        val providers = LyricsProvider.entries.reversed().map { LyricsProviderConfig(it, it == LyricsProvider.BETTER_LYRICS) }
        assertEquals(providers, decodeLyricsProviders(encodeLyricsProviders(providers)))
        val disabled = LyricsProvider.entries.map { LyricsProviderConfig(it, false) }
        assertEquals(disabled, decodeLyricsProviders(encodeLyricsProviders(disabled)))
    }

    @Test
    fun unknownAndDuplicateEntriesDoNotHideAvailableProviders() {
        val decoded = decodeLyricsProviders("""[
            {"provider":"unknown"}, {"provider":"betterlyrics","enabled":false},
            {"provider":"betterlyrics","enabled":true}
        ]""")
        assertEquals(LyricsProviderConfig(LyricsProvider.APPLE), decoded.first())
        assertEquals(LyricsProviderConfig(LyricsProvider.BETTER_LYRICS, false), decoded[1])
        assertEquals(LyricsProvider.entries.size, decoded.size)
        assertEquals(LyricsProvider.entries.toSet(), decoded.map { it.provider }.toSet())
    }

    @Test
    fun appleLeadsMigratedPreferencesWithoutChangingSavedOrderOrSwitches() {
        val decoded = decodeLyricsProviders("""[
            {"provider":"youtube","enabled":false}, {"provider":"lrclib"},
            {"provider":"bini","enabled":false}
        ]""")
        assertEquals(listOf(LyricsProvider.APPLE, LyricsProvider.YOUTUBE, LyricsProvider.LRCLIB,
            LyricsProvider.BINI, LyricsProvider.BETTER_LYRICS, LyricsProvider.LYRICS_PLUS), decoded.map { it.provider })
        assertFalse(decoded[1].enabled)
        assertFalse(decoded[3].enabled)
        assertEquals(decoded, decodeLyricsProviders(encodeLyricsProviders(decoded)))
    }

    @Test
    fun connectionReplacesOnlyAppleMirrorsAndRetainsHiddenPreferences() {
        val saved = LyricsProvider.entries.reversed().map {
            LyricsProviderConfig(it, it != LyricsProvider.APPLE && it != LyricsProvider.BINI)
        }
        val connected = visibleLyricsProviders(saved, true)
        assertEquals(listOf(LyricsProvider.YOUTUBE, LyricsProvider.LRCLIB, LyricsProvider.APPLE),
            connected.map { it.provider })
        assertFalse(connected.last().enabled)
        val disconnected = visibleLyricsProviders(saved, false)
        assertEquals(saved.filter { it.provider != LyricsProvider.APPLE }, disconnected)
        assertFalse(disconnected.first { it.provider == LyricsProvider.BINI }.enabled)
        assertEquals(saved, decodeLyricsProviders(encodeLyricsProviders(saved)))
    }
}
