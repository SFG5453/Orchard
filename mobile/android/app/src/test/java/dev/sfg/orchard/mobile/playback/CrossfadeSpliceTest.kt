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

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The playlist a rendered transition hands to the standby player.
 *
 * That player becomes authoritative as soon as the transition finishes, and the service persists
 * the queue from it, so anything this drops is gone from the queue and from disk. It used to hand
 * over two items and take the rest of the album with it.
 */
@UnstableApi
class CrossfadeSpliceTest {
    private val queue = listOf("a", "b", "c", "d", "e")

    @Test
    fun `splice keeps every other item in place`() {
        val spliced = CrossfadeEngine.spliceInPlace(queue, currentIndex = 1, mix = "mix", remainder = "rest")

        assertEquals(listOf("a", "mix", "rest", "d", "e"), spliced)
    }

    @Test
    fun `splice preserves queue length so indices stay valid`() {
        for (current in 0 until queue.lastIndex) {
            val spliced = CrossfadeEngine.spliceInPlace(queue, current, mix = "mix", remainder = "rest")

            assertEquals("length changed splicing at $current", queue.size, spliced.size)
            assertEquals("mix landed off the current index at $current", "mix", spliced[current])
            assertEquals("remainder landed off the next index at $current", "rest", spliced[current + 1])
        }
    }

    @Test
    fun `splice preserves history before the transition`() {
        val spliced = CrossfadeEngine.spliceInPlace(queue, currentIndex = 3, mix = "mix", remainder = "rest")

        assertEquals(listOf("a", "b", "c"), spliced.take(3))
    }

    @Test
    fun `splice preserves the tail after the transition`() {
        val spliced = CrossfadeEngine.spliceInPlace(queue, currentIndex = 0, mix = "mix", remainder = "rest")

        assertEquals(listOf("c", "d", "e"), spliced.drop(2))
    }

    @Test
    fun `splice at the last pair leaves nothing trailing`() {
        val spliced = CrossfadeEngine.spliceInPlace(queue, currentIndex = 3, mix = "mix", remainder = "rest")

        assertEquals(listOf("a", "b", "c", "mix", "rest"), spliced)
    }

    @Test
    fun `splice rejects a pair that runs off the end of the queue`() {
        assertThrows(IllegalArgumentException::class.java) {
            CrossfadeEngine.spliceInPlace(queue, currentIndex = queue.lastIndex, mix = "mix", remainder = "rest")
        }
    }
}
