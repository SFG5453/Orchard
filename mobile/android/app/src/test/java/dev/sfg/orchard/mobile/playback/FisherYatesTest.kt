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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FisherYatesTest {
    @Test
    fun emptyListReturnsEmpty() {
        val result = FisherYates.shuffle(emptyList<String>())
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun singleItemReturnsIdenticalList() {
        val result = FisherYates.shuffle(listOf("only"))
        assertEquals(listOf("only"), result)
    }

    @Test
    fun preservesAllElementsAndCounts() {
        val original = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        val shuffled = FisherYates.shuffle(original)

        assertEquals(original.size, shuffled.size)
        assertEquals(original.sorted(), shuffled.sorted())
    }

    @Test
    fun deterministicWithSeededRandom() {
        val original = (1..20).toList()
        val seed = 424242L

        val shuffled1 = FisherYates.shuffle(original, Random(seed))
        val shuffled2 = FisherYates.shuffle(original, Random(seed))

        assertEquals(shuffled1, shuffled2)
        assertNotEquals(original, shuffled1)
    }

    @Test
    fun inPlaceShuffleModifiesListDirectly() {
        val list = mutableListOf("1", "2", "3", "4", "5")
        val originalCopy = list.toList()

        FisherYates.shuffleInPlace(list, Random(12345))

        assertEquals(originalCopy.sorted(), list.sorted())
        assertNotEquals(originalCopy, list)
    }

    @Test
    fun statisticalUniformityAcrossAllPermutations() {
        // For 3 items [0, 1, 2], there are 3! = 6 possible permutations.
        // Over 60,000 runs, each permutation should occur ~10,000 times.
        val items = listOf(0, 1, 2)
        val counts = mutableMapOf<List<Int>, Int>()
        val iterations = 60_000
        val random = Random(99999)

        for (i in 0 until iterations) {
            val shuffled = FisherYates.shuffle(items, random)
            counts[shuffled] = (counts[shuffled] ?: 0) + 1
        }

        // All 6 permutations must be generated
        assertEquals(6, counts.size)
        val expected = iterations / 6.0
        for ((_, count) in counts) {
            // Check that every permutation is within 5% of expected count
            val deviation = Math.abs(count - expected) / expected
            assertTrue("Permutation count $count deviated by ${deviation * 100}%", deviation < 0.05)
        }
    }
}
