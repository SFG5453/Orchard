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

package dev.sfg.orchard.mobile.playback.slop

const val SLOP_THRESHOLD = 0.9f

enum class SlopAction { OFF, MARK, SKIP, REMOVE }

internal object SlopPolicy {
    const val AHEAD = 3

    fun windowIndices(size: Int, index: Int): IntRange =
        if (index !in 0 until size) IntRange.EMPTY else index..minOf(size - 1, index + AHEAD)

    fun flagged(id: String, probabilities: Map<String, Float>): Boolean =
        (probabilities[id] ?: -1f) >= SLOP_THRESHOLD

    fun nextAllowed(ids: List<String>, index: Int, probabilities: Map<String, Float>): Int? =
        ((index + 1) until ids.size).firstOrNull { !flagged(ids[it], probabilities) }
}
