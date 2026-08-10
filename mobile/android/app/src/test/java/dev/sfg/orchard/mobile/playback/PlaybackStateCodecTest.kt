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

package dev.sfg.orchard.mobile.playback

import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackStateCodecTest {
    @Test
    fun queuePositionShuffleAndRepeatSurviveRoundTrip() {
        val queue = listOf(Track("a", "First", "Artist"), Track("b", "Second", "Artist"))
        val restored = PlaybackStateCodec.decode(PlaybackStateCodec.encode(
            RestoredPlayback(
                queue,
                1,
                42_000,
                shuffle = true,
                repeatMode = RepeatMode.ALL,
                contextTitle = "Late-night mix",
                playWhenReady = true,
            ),
        ))

        assertEquals(queue, restored.queue)
        assertEquals(1, restored.currentIndex)
        assertEquals(42_000, restored.positionMs)
        assertEquals(RepeatMode.ALL, restored.repeatMode)
        assertEquals("Late-night mix", restored.contextTitle)
        assertFalse(restored.playWhenReady)
    }

    @Test
    fun malformedFieldsAreClampedToSafeRestorationValues() {
        val root = JSONObject()
            .put("queue", PlaybackStateCodec.encode(RestoredPlayback(listOf(Track("a", "Song", "Artist"))))
                .getJSONArray("queue"))
            .put("currentIndex", 99)
            .put("positionMs", -5)
            .put("repeatMode", "future-mode")

        val restored = PlaybackStateCodec.decode(root)

        assertEquals(0, restored.currentIndex)
        assertEquals(0, restored.positionMs)
        assertEquals(RepeatMode.OFF, restored.repeatMode)
    }

    @Test
    fun unshuffledOrderSurvivesRoundTripSoTheToggleStaysReversible() {
        val restored = PlaybackStateCodec.decode(PlaybackStateCodec.encode(
            RestoredPlayback(
                queue = listOf(Track("a", "First", "Artist"), Track("b", "Second", "Artist")),
                shuffle = true,
                unshuffledOrder = listOf("a", "b"),
            ),
        ))

        assertEquals(listOf("a", "b"), restored.unshuffledOrder)
    }

    @Test
    fun stateSavedBeforeUnshuffledOrderExistedRestoresEmpty() {
        val legacy = PlaybackStateCodec.encode(RestoredPlayback(listOf(Track("a", "Song", "Artist"))))
        legacy.remove("unshuffledOrder")

        assertEquals(emptyList<String>(), PlaybackStateCodec.decode(legacy).unshuffledOrder)
    }
}
