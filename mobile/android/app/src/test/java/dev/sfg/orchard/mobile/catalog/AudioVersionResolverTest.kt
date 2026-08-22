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

package dev.sfg.orchard.mobile.catalog

import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_ATV
import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_OMV
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVersionResolverTest {
    private val explicitVideo = Track(
        id = "explicit-video",
        title = "The Song (Official Video)",
        artist = "The Artist",
        durationMs = 180_000,
        explicit = true,
        musicVideoType = MUSIC_VIDEO_TYPE_OMV,
    )

    @Test
    fun `explicit video resolves only to explicit album audio`() {
        val cleanExactDuration = audio("clean-audio", explicit = false, durationMs = 180_000)
        val explicitCloseDuration = audio("explicit-audio", explicit = true, durationMs = 184_000)

        val match = bestAudioMatch(explicitVideo, listOf(cleanExactDuration, explicitCloseDuration))

        assertEquals("explicit-audio", match?.id)
    }

    @Test
    fun `explicit video stays unchanged when search finds only clean audio`() {
        val clean = audio("clean-audio", explicit = false, durationMs = 180_000)

        assertNull(bestAudioMatch(explicitVideo, listOf(clean)))
    }

    @Test
    fun `explicit album audio is checked even when catalog already labels it ATV`() {
        val explicitAudio = explicitVideo.copy(musicVideoType = MUSIC_VIDEO_TYPE_ATV)

        assertTrue(explicitAudio.needsAudioVersionLookup())
    }

    @Test
    fun `clean album audio does not add a redundant search`() {
        val cleanAudio = explicitVideo.copy(explicit = false, musicVideoType = MUSIC_VIDEO_TYPE_ATV)

        assertFalse(cleanAudio.needsAudioVersionLookup())
    }

    @Test
    fun `already correct explicit audio id wins over an equally good alternate`() {
        val target = explicitVideo.copy(id = "current", musicVideoType = MUSIC_VIDEO_TYPE_ATV)
        val alternate = audio("alternate", explicit = true, durationMs = target.durationMs)
        val current = audio("current", explicit = true, durationMs = target.durationMs)

        assertEquals("current", bestAudioMatch(target, listOf(alternate, current))?.id)
    }

    @Test
    fun `clean id carrying stale explicit metadata is replaced by explicit audio`() {
        val mislabeled = explicitVideo.copy(id = "clean-audio", musicVideoType = MUSIC_VIDEO_TYPE_ATV)
        val cleanCurrent = audio("clean-audio", explicit = false, durationMs = mislabeled.durationMs)
        val explicit = audio("explicit-audio", explicit = true, durationMs = mislabeled.durationMs)

        assertEquals("explicit-audio", bestAudioMatch(mislabeled, listOf(cleanCurrent, explicit))?.id)
    }

    private fun audio(id: String, explicit: Boolean, durationMs: Long) = Track(
        id = id,
        title = "The Song",
        artist = "The Artist",
        durationMs = durationMs,
        explicit = explicit,
        musicVideoType = MUSIC_VIDEO_TYPE_ATV,
    )
}
