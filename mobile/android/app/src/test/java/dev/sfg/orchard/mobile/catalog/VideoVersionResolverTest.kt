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
import dev.sfg.orchard.mobile.model.MUSIC_VIDEO_TYPE_UGC
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoVersionResolverTest {
    private val audio = Track(
        id = "album-audio",
        title = "Midnight Drive",
        artist = "Orchard Artist",
        durationMs = 210_000,
        explicit = true,
        musicVideoType = MUSIC_VIDEO_TYPE_ATV,
    )

    @Test
    fun `official video with decorated title matches album audio`() {
        val video = video(
            id = "official-video",
            title = "Midnight Drive (Official Music Video)",
            durationMs = 224_000,
        )

        assertEquals("official-video", bestVideoMatch(audio, listOf(video))?.id)
    }

    @Test
    fun `official video wins over otherwise equal user upload`() {
        val upload = video("upload", "Midnight Drive", 210_000, MUSIC_VIDEO_TYPE_UGC)
        val official = video("official", "Midnight Drive", 210_000)

        assertEquals("official", bestVideoMatch(audio, listOf(upload, official))?.id)
    }

    @Test
    fun `clean video cannot replace explicit audio`() {
        assertNull(bestVideoMatch(audio, listOf(video("clean", "Midnight Drive", 210_000, explicit = false))))
    }

    @Test
    fun `different recording with implausible runtime is rejected`() {
        assertNull(bestVideoMatch(audio, listOf(video("concert", "Midnight Drive", 410_000))))
    }

    private fun video(
        id: String,
        title: String,
        durationMs: Long,
        type: String = MUSIC_VIDEO_TYPE_OMV,
        explicit: Boolean = true,
    ) = Track(
        id = id,
        title = title,
        artist = "Orchard Artist",
        durationMs = durationMs,
        explicit = explicit,
        musicVideoType = type,
    )
}
