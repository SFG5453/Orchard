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
package dev.sfg.orchard.mobile.artwork

import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RejectedTallArtworkTest {
    private val tall = "https://mvod.itunes.apple.com/a/P1_default.m3u8"
    private val track = Track(
        id = "t",
        title = "Song",
        artist = "Artist",
        animatedArtworkUrl = "https://mvod.itunes.apple.com/a/P2_default.m3u8",
        animatedArtworkVerticalUrl = "$tall?token=1",
    )

    @Test
    fun rejectedTallLoopFallsBackToSquare() {
        val shown = track.withoutRejectedTallArt(setOf(tall))
        assertEquals("", shown.animatedArtworkVerticalUrl)
        assertEquals(track.animatedArtworkUrl, shown.animatedArtworkUrl)
    }

    @Test
    fun otherTallLoopsAreKept() {
        assertSame(track, track.withoutRejectedTallArt(setOf("https://mvod.itunes.apple.com/b/P3_default.m3u8")))
    }

    @Test
    fun snapshotDropsRejectedLoopFromCurrentAndQueue() {
        val snapshot = PlaybackSnapshot(currentTrack = track, queue = listOf(track))
        val shown = snapshot.withoutRejectedTallArt(setOf(tall))
        assertEquals("", shown.currentTrack?.animatedArtworkVerticalUrl)
        assertEquals("", shown.queue.single().animatedArtworkVerticalUrl)
    }
}
