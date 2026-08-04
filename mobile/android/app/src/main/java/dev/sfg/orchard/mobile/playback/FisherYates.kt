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

import kotlin.random.Random

/**
 * Fisher-Yates (Knuth) shuffle algorithm.
 *
 * Produces an unbiased, uniformly distributed permutation of items in O(n) time.
 * For each element from the end down to index 1, an index j in [0, i] is selected
 * uniformly at random and swapped with index i.
 */
object FisherYates {
    /**
     * Returns a new list containing all elements of [items] shuffled using
     * the Fisher-Yates algorithm.
     */
    fun <T> shuffle(items: List<T>, random: Random = Random.Default): List<T> {
        if (items.size <= 1) return items
        val result = items.toMutableList()
        shuffleInPlace(result, random)
        return result
    }

    /**
     * Shuffles [list] in place using the Fisher-Yates algorithm.
     */
    fun <T> shuffleInPlace(list: MutableList<T>, random: Random = Random.Default) {
        for (i in list.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            if (i != j) {
                val temp = list[i]
                list[i] = list[j]
                list[j] = temp
            }
        }
    }
}
