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

package dev.sfg.orchard.mobile

import dev.sfg.orchard.mobile.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioCacheVariantTest {
    @Test
    fun disablingQobuzSelectsSeparateYouTubeBytes() {
        val qobuz = audioCacheVariant(AudioQuality.MAX, true, "hires")
        val youtube = audioCacheVariant(AudioQuality.MAX, false, "hires")
        assertNotEquals(qobuz, youtube)
        assertEquals(audioCacheVariant(AudioQuality.HIGH, false, "auto"), youtube)
    }

    @Test
    fun qobuzTiersAndYouTubeQualitiesHaveSeparateKeys() {
        assertNotEquals(
            audioCacheVariant(AudioQuality.MAX, true, "hires"),
            audioCacheVariant(AudioQuality.MAX, true, "lossless"),
        )
        assertNotEquals(
            audioCacheVariant(AudioQuality.DATA_SAVER, false, "auto"),
            audioCacheVariant(AudioQuality.HIGH, false, "auto"),
        )
        assertEquals(
            audioCacheVariant(AudioQuality.HIGH, false, "auto"),
            audioCacheVariant(AudioQuality.ATMOS, false, "auto"),
        )
    }

    @Test
    fun appleMusicSongsIgnoreProviderSettings() {
        val youtube = { audioCacheVariant(AudioQuality.MAX, true, "hires") }
        assertEquals(APPLE_MUSIC_CACHE_VARIANT, sourceCacheVariant("am.songs.697195462", providerVariant = youtube))
        assertEquals(APPLE_MUSIC_CACHE_VARIANT, sourceCacheVariant("am.songs.697195462") { audioCacheVariant(AudioQuality.DATA_SAVER, false, "auto") })
        assertEquals(youtube(), sourceCacheVariant("dQw4w9WgXcQ", true, youtube))
    }

    @Test
    fun appleMusicStandardKeepsItsOwnBytes() {
        val youtube = { audioCacheVariant(AudioQuality.HIGH, false, "auto") }
        assertEquals(APPLE_MUSIC_STANDARD_CACHE_VARIANT, sourceCacheVariant("am.songs.1", true, youtube))
        assertTrue(appleMusicStandard(AudioQuality.DATA_SAVER))
        assertTrue(appleMusicStandard(AudioQuality.NORMAL))
        assertFalse(appleMusicStandard(AudioQuality.HIGH))
        assertFalse(appleMusicStandard(AudioQuality.ATMOS))
        assertFalse(appleMusicStandard(AudioQuality.MAX))
    }
}
